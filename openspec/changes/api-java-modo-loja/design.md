# Design

## Context

- **Regra do dono do produto: só a parte Java muda.**
  - Nada em `API/NETCORE/` nem em `GATEWAY/` é alterado.
  - No banco de produção da nuvem, a Java só liga o Change Tracking e o isolamento snapshot e cria tabelas próprias.
    As tabelas da .NET só são escritas pelo aplicador da sincronização.
- **Pastas:** API Java em `JAVA/api`, Caixa JavaFX em `JAVA/caixa`, Quack Estoque em `JAVA/estoque`.
- **O que existe (Fases 1 a 4).** Leia os archives `openspec/changes/archive/2026-11-0*-api-java-*`, principalmente o
  design de `2026-11-04-api-java-vendas`: D4 (numeração), D6 (fronteira fiscal) e D9 (por que Change Tracking).
  - **Esquema:** `V1__esquema_inicial.sql` é o esquema da .NET (59 tabelas, todas com chave primária). `V2`/`V3` são
    dados de referência; `V4` é `LoteBaixaPendencias` (só Java). O `EsquemaEquivalenteIT` prova a equivalência.
  - **Numeração:** `plataforma/numeracao/NumeradorDocumentos` é o único ponto que numera venda, NFC-e e inventário.
  - **Fila fiscal:** `fiscal/fila/app/FilaFiscalMinima`, com a propriedade `quack.fiscal.fila-minima.ativa`.
  - **Segurança:** `security/JwtService` (segredo `quack.auth.jwt-secret`), `SecretProtector` (`enc:v1:`, chave
    `quack.security.encryption-key`), `UsuarioAutenticado`, `@PerfisPermitidos`. Os segredos cifrados estão em
    `Empresas`: `EmailSmtpPassword`, `CscCifrado`, `CertificadoPfxCifrado`, `CertificadoSenhaCifrada`.
  - **E-mail:** `email/app/EmailService`.
  - **Agendamento:** `config/AgendamentoConfig`.
- **Nuvem hoje:**
  - `docker-compose.yml` da raiz: `pdv-sqlserver`, `pdv-api` (.NET) e `pdv-frontend`, atrás do Traefik
    (`api-pdv.quacksistemas.com.br`), publicados pelo GitHub Actions (`DEPLOY.md`);
  - a API Java já tem `Dockerfile` e `docker-compose.homologacao.yml`.
- **Clientes:** o Caixa e o Quack Estoque leem `apiBaseUrl` do `config.properties` (`AppConfig`). Trocar o endereço
  basta.
- **Comportamento da .NET na nuvem que molda o desenho** (`Services/Fiscal/NfceOutboxWorker.cs`, mesma lógica de
  `FilaFiscalRepository`):
  - o worker pega toda venda com `EmitirNfce = 1` sem `DocumentosFiscais` modelo 65 e cria a nota com número da
    sequência **da nuvem**;
  - emite toda nota com `Status IN (1,2,8)` de empresa com emitente.
  - Não dá para desligar isso por empresa sem mexer na .NET.

## Goals / Non-Goals

**Goals:**
- Loja inteira operando sem internet; nuvem convergindo sozinha ao voltar, sem perda nem duplicação.
- Dono editando cadastros, preços e configurações pela nuvem como hoje (painel .NET e MCP), com a loja aplicando.
- Nenhuma mudança na API .NET, no Gateway nem nos clientes (só a configuração do endereço).
- Problemas visíveis: conferência, situação, alertas.

**Non-Goals:**
- Emissão fiscal na loja e rotas `api/Nfce/*` (Fase 7).
- Painel web servido pela loja.
- Mais de um servidor por empresa, ou duas lojas de uma empresa no mesmo servidor.
- Sair do modo loja (volta da autoridade para a nuvem). Fica manual e documentado como procedimento de emergência.
- HTTPS na rede local.

## Decisions

### D1. Perfis e propriedade `quack.modo`

- **Valores:** `padrao` (omisso), `loja` e `nuvem`, em `application-loja.yml` e `application-nuvem.yml`. A ativação
  é por `SPRING_PROFILES_ACTIVE=loja|nuvem`, e o perfil define `quack.modo`.
- **`nuvem`:**
  - um filtro devolve 404 para toda rota fora de `/api/loja-sync/**` e `/actuator/health`;
  - `quack.fiscal.fila-minima.ativa=false` e nenhum agendamento de negócio (retentativa de lotes desligada);
  - Flyway com `locations=classpath:db/nuvem` e `table=flyway_quack_sync`. **Não** roda `db/migration`: a Java nunca
    migra tabela da .NET em produção;
  - usa os mesmos `QUACK_AUTH_JWT_SECRET` e `QUACK_SECURITY_ENCRYPTION_KEY` da .NET, para validar login de
    administrador e decifrar segredos.
- **`loja`:** API completa, mais o envio, os comandos, a conferência e as migrações `db/migration` (V5+ com as tabelas
  da loja). Usa segredo de JWT e chave de cifra próprios, gerados pelo instalador.
- **Por quê:** um só artefato (a mesma imagem ou jar) com papéis diferentes, como a § 3.3 do plano pede. A alternativa
  de um serviço separado só para sincronizar duplicaria o acesso a dados e o catálogo.

### D2. Catálogo de sincronização (a peça central)

`plataforma/sincronizacao/catalogo/CatalogoSincronizacao`, em código e testado, define cada tabela.

**Classe da tabela:**

| Classe | Significado |
| --- | --- |
| `LOJA` | só a loja grava. Sobe por sincronização. Edição na nuvem vai para a quarentena e é revertida |
| `EDITAVEL` | sobe por sincronização. Edição na nuvem vira comando com conferência de base (D6) |
| `ACRESCIMO` | só inserção, as duas pontas criam linhas e ninguém reverte nada (só `AuditLog`) |
| `FORA` | não sincroniza |

**Classe por coluna** (em tabela `EDITAVEL` ou `LOJA`):

| Classe | Significado |
| --- | --- |
| `LOJA` | padrão na tabela `LOJA`. Em tabela `EDITAVEL`, coluna que só operação da loja muda |
| `EDITAVEL` | padrão na tabela `EDITAVEL` |
| `NUVEM` | a nuvem é a autora. A sincronização da loja só a escreve na inserção; mudança na nuvem desce como comando forçado, sem base |
| `CIFRADA` | como `NUVEM`, mas o valor é recifrado com a chave da loja |
| `LOCAL` | cada ponta tem o seu valor. Só é copiada na inserção |

**Regra por linha:** opcional, por predicado. Exemplo: `DocumentosFiscais` e `FiscalSequencias` com `Modelo = 55` são
autoria da nuvem (todas as colunas `NUVEM`), porque a NF-e de devolução é emitida no painel.

**Tabelas filhas sem `CompanyId`:** `VendaItens`, `PedidoItens`, `OrdemCompraItens` e `PromocaoProdutos` declaram a
mãe e a coluna de ligação. O escopo da empresa é validado por ela.

**Classificação inicial.** A tarefa 1.1 confere cada uma lendo, na Java e na .NET, quem grava cada coluna:
- **`EDITAVEL`:**
  - `Produtos`: `ProductQnt` e `TotalPriceOnProduct` são `LOJA`;
  - `Clientes`: `SaldoDevedor` é `LOJA`;
  - `Fornecedores`, `Categorias`, `Promocoes`, `PromocaoProdutos`;
  - `Usuarios`: `LastLoginAt`, `FailedLoginAttempts`, `FirstFailedLoginAt` e `LockoutEnd` são `LOCAL`;
  - `Empresas`, só a linha da empresa: os 4 segredos são `CIFRADA`, e `Status`, `ReviewedAt`, `ReviewedBy` e
    `RejectionReason` são `NUVEM`;
  - `EmpresaTema`, `CustomerCreditPolicies`, `EstoqueConfigAnalise`, `LoteConfig`;
  - `RegrasEmissaoNfce`: `ContadorVendas` é `LOJA`;
  - `ProdutoClassificacao`.
- **`LOJA`:**
  - `AutorizacoesPreco`, `CaixaMovimentos`, `CaixaSessoes`, `EstoqueMovimentacoes`, `EstoqueSnapshotDiario`,
    `FiadoMovimentos`;
  - `FiscalSequencias` (modelo 65);
  - `DocumentosFiscais` (modelo 65). As colunas de resultado são `NUVEM`: `Status`, `ChaveAcesso`, `TpEmis`,
    `DhContingencia`, `JustContingencia`, `Protocolo`, `DhAutorizacao`, `CodigoStatus`, `MotivoStatus`,
    `XmlAssinado`, `XmlProtocolado`, `XmlCancelamento`, `Tentativas`, `ProximaTentativaEm`, `UltimoErro`,
    `AtualizadoEm`, `QrCodeUrl`, `Cancelado*` e `Devolvido*`. Ver D8;
  - `InventarioContagens`, `InventarioEventos`, `InventarioItens`, `Inventarios`;
  - `LoteConsumos`, `ProdutoLotes`, `MapeamentoProdutoFornecedor`, `NotasEntradaArquivo`;
  - `OrdemCompra` (+ itens), `Pedidos` (+ itens);
  - `Vendas`, `VendaItens`, `VendaPagamentos`, `VendaCancelamentos`;
  - `ModuloMercadoRegistros`.
- **`ACRESCIMO`:** `AuditLog`.
- **`FORA`:**
  - `Sessoes`, `PasswordResetTokens`, `ProcessedEvents`;
  - `GatewayTokens`, `GatewayCredit*` (6 tabelas), `UserCreditStores`, `LojaGatewayConfig`, `IntegracaoTokens`;
  - `FiscalIa*` (3 tabelas, recurso da nuvem);
  - `TreinamentoSecoes`, `TreinamentoVideos`, `ModulosMercado` (globais);
  - `LoteBaixaPendencias` e as tabelas `Sync*`.

**Guardas do catálogo:** um teste falha se aparecer tabela no esquema sem classificação, coluna classificada que não
existe, ou tabela `EDITAVEL`/`LOJA` sem chave primária. A ordem de aplicação (pais antes de filhos) é calculada de
`sys.foreign_keys` e conferida por teste.

### D3. Change Tracking e versões

- **Loja:**
  - `ALTER DATABASE … SET CHANGE_TRACKING = ON (CHANGE_RETENTION = 14 DAYS, AUTO_CLEANUP = ON)` e
    `ALLOW_SNAPSHOT_ISOLATION ON`;
  - `ENABLE CHANGE_TRACKING` em toda tabela não `FORA`;
  - quem aplica é a migração da loja (`V5`), que só roda no perfil `loja`. Uma propriedade impede rodar no padrão,
    para não ligar o tracking nos bancos de teste do contrato.
- **Nuvem:** o mesmo, retenção 3 dias, em `db/nuvem/V1`. Liga o tracking nas tabelas não `FORA` e com
  `TRACK_COLUMNS_UPDATED = OFF`. A detecção compara com o espelho (D6), não com as colunas.
- **Contexto:** toda escrita do aplicador (nas duas pontas) usa `WITH CHANGE_TRACKING_CONTEXT (0x5351…)` com um valor
  fixo por origem:
  - `sync-loja`: o aplicador da nuvem;
  - `comando-nuvem`: o aplicador de comandos na loja.
  - Assim o detector da nuvem ignora o que o próprio aplicador escreveu.
- **Por quê** Change Tracking e não CDC: o CDC exige o SQL Agent, que não existe no Express. Também não uma fila da
  aplicação (D9 da Fase 4).

### D4. Envio loja → nuvem

- **Rotina:** `EnvioSincronizacao`, a cada 2 s quando ociosa.
  1. Abre uma transação `SNAPSHOT` e lê `CHANGE_TRACKING_CURRENT_VERSION()` como `ate`.
  2. Para cada tabela, na ordem do D2, lê `CHANGETABLE(CHANGES t, @de)` com `LEFT JOIN` na linha atual. A linha é
     upsert se existe, exclusão se não existe.
  3. Monta o lote `{lojaId, loteId, de, ate, partes}`.
- **Retomada fiscal antes do envio:** antes de montar, roda a retomada fiscal da loja (D8). Assim nenhuma venda com
  `EmitirNfce = 1` sobe sem nota.
- **Mínimo de versão:** se `@de < CHANGE_TRACKING_MIN_VALID_VERSION(t)` em alguma tabela, faz a ressincronização
  completa (D5).
- **Formato:** JSON gzip. O valor de cada coluna vai tipado (texto, número decimal como string com a escala, data ISO,
  binário em base64, nulo).
- **Lote grande** (depois de horas offline):
  - o lote é dividido em partes de até 2.000 linhas ou 4 MB, com o mesmo `loteId`;
  - a nuvem guarda as partes em `SyncLotePartes` e aplica tudo numa transação ao receber a última;
  - o `@de` só avança depois da confirmação.
- **Rota:** `POST /api/loja-sync/lotes` (token do servidor, D9).
- **Resposta:** `{aplicadoAte}`. Se `ate <= aplicadoAte` registrado na nuvem, ela confirma sem aplicar
  (idempotente). Se `de` não bate com o `aplicadoAte` registrado, responde 409 com o valor esperado, e a loja recomeça
  dele.
- **Aplicação na nuvem** (`AplicadorLotes`):
  - uma transação, com contexto `sync-loja`, upserts na ordem do D2 e exclusões na ordem inversa;
  - por linha:
    - valida o escopo: `CompanyId` do token, ou a mãe para as filhas;
    - roda a **detecção inline** daquela linha (D6) antes de escrever;
    - escreve só as colunas que a classe permite em `UPDATE`: `LOJA`, mais `EDITAVEL` sem comando pendente na
      coluna;
    - `INSERT` usa todas as colunas, menos as `CIFRADA`, que ficam vazias até a nuvem as preencher;
    - atualiza o espelho (D6) das colunas `EDITAVEL`.
  - `AuditLog`: insere com o `IDENTITY` da nuvem e guarda `SyncAuditoriaMapa(lojaId, idLoja, idNuvem)`. Uma linha já
    mapeada é ignorada.
- **Erros:**
  - rede, timeout, 408, 429 e 5xx: nova tentativa com backoff exponencial de 2 s a 5 min, com jitter, sem limite;
  - 4xx (menos 409 de versão) ou erro de aplicação na nuvem (o lote inteiro faz rollback): a sincronização da loja
    fica **pausada**, com `SyncEstado.pausadaMotivo`, e gera alerta (D11).
  - Retomar: `POST /api/loja-sync/retomar` (administrador local) depois da correção.
  - Não há "pular linha": em replicação de estado, pular quebra a consistência. É a escolha consciente diante da fila
    de mensagens mortas do livro; a quarentena existe só para gravações da nuvem (D7).
- **Limite do corpo:** a rota de lotes aceita até 16 MB. Ela tem um filtro de tamanho próprio; o geral continua com
  2 MB.

### D5. Carga inicial, ressincronização e ativação

- **Registro** (`POST /api/loja-sync/registrar`, sem token):
  - recebe e-mail, senha e nome do servidor e valida como o login da .NET (mesmo hash e bloqueio), exigindo
    `Role = Administrador`;
  - cria `SyncServidores(id, companyId, nome, tokenHash SHA-256, status, criadoEm, ultimoContato)`, com no máximo um
    ativo por empresa;
  - devolve o token (32 bytes aleatórios, base64url) uma vez.
- **Carga** (`GET /api/loja-sync/carga?tabela=&depois=&limite=`):
  - a primeira chamada abre a carga, guarda a versão do retrato `V` e cria `SyncCargas`;
  - cada página é lida em `SNAPSHOT` na versão `V` (a transação de leitura dura uma página; para manter o retrato,
    `V` é a versão de início, e as linhas mudadas depois de `V` entram na fase de delta);
  - devolve as linhas da empresa por chave crescente, com as `CIFRADA` decifradas (TLS no caminho, D9);
  - a loja grava num banco vazio, com tracking ainda desligado e as colunas `CIFRADA` cifradas com a chave dela;
  - a loja registra `(tabela, ultimaChave)` e retoma de onde parou.
- **Delta até a ativação:**
  1. com tudo baixado, a loja pede `GET /api/loja-sync/carga/delta?desde=V`;
  2. a nuvem devolve, por Change Tracking, as linhas da empresa mudadas desde `V`, de qualquer origem; a loja aplica e
     repete até o delta vir vazio;
  3. `POST /api/loja-sync/ativar`: a nuvem marca `SyncServidores.status = ativo` e guarda a versão do detector
     (`SyncEstadoNuvem.detectorDesde`);
  4. a loja liga o tracking e grava `SyncEstado.de` com a versão atual dela.
- **Checklist:** pede que os caixas sejam trocados para o servidor depois da ativação, sem fila offline pendente (D12).
- **Ressincronização completa** (loja passou da retenção):
  - a loja envia o retrato inteiro em partes, com `loteId` e `tipo=completo`;
  - a nuvem aplica cada tabela com "substituir": upsert de tudo, e exclusão das linhas da empresa que não vieram, só em
    tabelas `LOJA`;
  - em `EDITAVEL`, uma linha só da nuvem com comando pendente fica.

### D6. Detecção na nuvem, espelho e comandos

- **Espelho:** `SyncEspelho(tabela, chave, companyId, colunasJson, versaoLoja)` só para tabelas `EDITAVEL`. Guarda o
  último valor das colunas `EDITAVEL` que veio da loja. É a base do comando e a única forma de saber a empresa de uma
  linha excluída na nuvem.
- **Detector** (`DetectorNuvem`, a cada 2 s e inline no aplicador):
  - lê `CHANGETABLE(CHANGES t, @detectorDesde)` das tabelas não `FORA`, ignorando `SYS_CHANGE_CONTEXT = sync-loja`;
  - por linha de empresa em modo loja:
    - **tabela `EDITAVEL`:** compara as colunas `EDITAVEL` com o espelho. Se mudaram, cria comando
      `alterar(tabela, chave, [{coluna, base, novo}])`. Inserção cria `inserir(linha completa)`. Exclusão (empresa pelo
      espelho) cria `excluir`. Colunas `NUVEM` e `CIFRADA` mudadas criam `forcar(colunas)`. Colunas `LOJA` mudadas
      seguem a regra de `LOJA`;
    - **tabela `LOJA`:** grava em `SyncQuarentena` a linha da nuvem e o motivo, gera alerta e, se a linha existe na
      loja, cria `reenviar(tabela, chave)`. A loja põe a linha no próximo lote, e o estado dela volta. Linha que só
      existe na nuvem fica (a spec manda não excluir). Exclusão em tabela `LOJA` de empresa desconhecida é corrigida
      pela conferência (D10);
    - **`AuditLog`:** ignorado;
  - avança `detectorDesde` na mesma transação dos comandos criados.
  - Se `detectorDesde < MIN_VALID_VERSION` (receptor parado mais que a retenção): detecção completa comparando todas
    as linhas `EDITAVEL` da empresa com o espelho.
- **Detecção inline** (resolve "a edição da nuvem não se perde"): antes de o aplicador escrever uma linha, ele roda o
  detector só para ela, com `CHANGETABLE(VERSION t, (chave))`. Se a última mudança dela não é `sync-loja` e é mais
  nova que `detectorDesde`, cria o comando. Depois o aplicador não escreve as colunas com comando pendente. Assim a
  nuvem continua mostrando o preço novo até a loja confirmar.
- **Fila de comandos:** `SyncComandos(seq IDENTITY, companyId, tipo, tabela, chave, payloadJson, status, tentativas,
  resultadoJson, criadoEm, atualizadoEm)`.
- **Entrega:**
  - a loja faz `GET /api/loja-sync/comandos?apos=seq` (long-poll de até 25 s, até 100 comandos) e aplica em ordem;
  - responde `POST /api/loja-sync/comandos/resultados` com `aplicado`, `conflito` (com o valor da loja) ou `falhou`
    (com o erro);
  - a nuvem conta as tentativas. Na 5ª falha, o comando fica `parado` com alerta, e os seguintes continuam.
- **Aplicação na loja** (`AplicadorComandos`):
  - uma transação por comando, com contexto `comando-nuvem`;
  - `alterar`: por coluna, `UPDATE … SET c = novo WHERE chave AND c = base`. O valor é comparado com a mesma
    normalização do lote, e `NULL` é tratado como igual. Uma coluna que não bate é conflito, e as outras do mesmo
    comando continuam (decisão: coluna a coluna, como a spec);
  - `inserir`: se a chave já existe, conflito; senão insere;
  - `excluir`: se a linha mudou em coluna `EDITAVEL` desde a base, conflito; FK impede, `falhou` com a mensagem;
  - `forcar`: escreve sem base, recifrando `CIFRADA`;
  - `reenviar`: marca a chave em `SyncReenvios`, e o envio inclui a linha no próximo lote;
  - toda aplicação grava `AuditLog` na loja (`EventType = SincronizacaoComando`).
  - A mudança aplicada sobe pela sincronização normal e atualiza o espelho, fechando o ciclo.
- **Por quê** conferência de base coluna a coluna, e não "último que grava vence": a loja continua autoridade, as
  edições que não colidem passam e o conflito fica visível. A alternativa de travar a edição na nuvem exigiria mudar a
  .NET.

### D7. Quarentena

`SyncQuarentena(id, companyId, tabela, chave, origem, linhaJson, motivo, criadoEm, resolvidoEm, resolvidoPor)`.

- **Entradas:**
  - gravação da nuvem em tabela ou coluna `LOJA`;
  - linha da nuvem sobrescrita na ressincronização completa.
- **Uso:** consulta pela rota de situação (D11) e marcação de resolvido por um administrador
  (`POST /api/loja-sync/quarentena/{id}/resolver`, na nuvem, com JWT da .NET validado pela Java). Não há tela nesta
  fase.

### D8. Fiscal durante a 4b (autoria dividida até a Fase 7)

- **O problema:** o worker da .NET na nuvem age sobre qualquer venda ou nota pendente. Portanto:
  1. A **venda sobe sempre com a nota**. Isso vem da mesma transação (D4) e da retomada antes do envio. Sem isso, o
     worker criaria a nota com a sequência da nuvem, e o número colidiria com o da loja.
  2. A nuvem **emite** a nota da loja quando ela chega, como faz hoje com os caixas ligados direto. As colunas de
     resultado (`NUVEM`) descem por `forcar`, e a loja mostra autorizada, rejeitada ou "não emitida".
  3. A sequência modelo 65 é da loja e sobe. A nuvem nunca a usa para essas notas, que já trazem número.
  4. Modelo 55 (NF-e do painel) é autoria da nuvem e desce.
- **Fila mínima no perfil `loja`:** só o ramo "retomar vendas sem nota" (spec modificada de `fiscal/fila-nfce`). Os
  ramos de cancelada e sem emitente ficam com a .NET, para não haver dois donos das colunas de resultado.
- **Nota que nunca sai na nuvem:** fica com `Status = 1` na loja. O alerta "NFC-e pendente há mais de 30 minutos"
  cobre esse caso.
- **Limite conhecido:** sem internet não há emissão. A nota sai em emissão normal quando a internet volta, como hoje
  com os caixas offline sem Gateway. A contingência na loja é da Fase 7. A loja só entra em produção depois da Fase 7
  (§ 3.3 do plano); a 4b é validada na loja de teste.

### D9. Transporte e autenticação

- **Iniciativa da loja:** só a loja abre conexão. Nenhuma porta é aberta na loja para a internet.
- **Base:** `quack.sync.nuvem-url`, por exemplo `https://sync-pdv.quacksistemas.com.br`, novo host no Traefik para o
  serviço `pdv-api-sync`.
- **Rotas de máquina:** cabeçalho `Authorization: LojaServidor <token>`. Um filtro próprio resolve o servidor e a
  empresa, guarda `ultimoContato` e recusa servidor revogado (401) e empresa diferente (403).
- **Revogação:** `POST /api/loja-sync/servidores/{id}/revogar`, por administrador com JWT da .NET.
- **Rotas humanas** (situação, quarentena, retomar):
  - na nuvem, JWT da .NET, porque o receptor usa o mesmo segredo;
  - na loja, JWT local, com segredo próprio (D1).
- **Rede local:**
  - HTTP na porta 8080, com firewall restrito à sub-rede (instalador);
  - o SQL Server escuta só em `localhost`.

### D10. Conferência (reconciliação)

- **Rotina:** `ConferenciaSincronizacao` na loja, todo dia às 03:00 e por `POST /api/loja-sync/conferencia`
  (administrador).
- **Conteúdo:** por tabela não `FORA`, `COUNT(*)` e `CHECKSUM_AGG(BINARY_CHECKSUM(colunas comparáveis))` das linhas
  da empresa.
  - Colunas comparáveis: `LOJA` e `EDITAVEL`, sem `LOCAL`, `CIFRADA`, `NUVEM` nem o `Id` do `AuditLog`.
  - O `AuditLog` é conferido pela contagem do mapa.
  - O cálculo é feito com o mesmo SQL nas duas pontas (`POST /api/loja-sync/conferencia/tabela` devolve o da nuvem).
- **Tabela divergente:** compara 64 faixas (`ABS(CHECKSUM(chave)) % 64`). Em cada faixa diferente, a loja reenvia
  todas as linhas e a lista de chaves. A nuvem exclui as que sobram em tabela `LOJA`; em `EDITAVEL` sobra só a linha
  com comando pendente. Repete até fechar, no máximo 3 voltas, e depois alerta.
- **Saldos** (só na loja, sem corrigir):
  - `Produtos.ProductQnt` igual ao `EstoqueNovo` da última `EstoqueMovimentacoes` do produto, quando há movimento;
  - `Clientes.SaldoDevedor` igual ao `SaldoAtual` do último `FiadoMovimentos`.
  - A tarefa confere a semântica na .NET antes.
- **Resultado:** gravado em `SyncConferencias` e mostrado na situação.

### D11. Situação e alertas

- **Rota:** `GET /api/loja-sync/situacao` nas duas pontas, para administrador ou token do servidor. Mostra:
  - versão local e confirmada;
  - última sincronização;
  - atraso em segundos;
  - pausa e motivo;
  - comandos pendentes e parados;
  - quarentena aberta;
  - NFC-e com `Status IN (1,2,8)` criadas há mais de 10 minutos;
  - última conferência;
  - último backup (a loja lê o arquivo de estado do script de backup, D12).
- **Alertas:** `SyncAlertas(motivo, companyId, ultimoEnvio, contagem)`, com os limites da spec.
  - O envio é `ERROR` no log e e-mail aos administradores da empresa pelo `EmailService`.
  - Máximo de um e-mail por motivo por hora. Sem SMTP configurado, fica só no log.
  - Na loja, o e-mail só sai com internet; o alerta fica guardado e é enviado ao voltar.

### D12. Instalador e nuvem

- **`JAVA/api/instalador-loja/`:**
  - `instalar.ps1`:
    - verifica Windows 64 bits e privilégio;
    - instala o SQL Server Express (silencioso, instância `QUACK`, só TCP local) se não houver;
    - cria o banco;
    - instala o JRE 21 embutido (pasta `jre/` do pacote) e a API como serviço por **WinSW**, com início automático e
      reinício em falha;
    - gera `QUACK_AUTH_JWT_SECRET` e `QUACK_SECURITY_ENCRYPTION_KEY` (48 bytes aleatórios) em `config\loja.env`, com
      ACL só para Administradores e o serviço;
    - cria a regra de firewall da sub-rede;
    - agenda o backup;
    - pede e-mail e senha do administrador, registra e roda a carga, mostrando o progresso pela rota de situação.
  - `backup.ps1` (tarefa agendada às 02:00): `BACKUP DATABASE … WITH COMPRESSION, CHECKSUM`, retenção de 7, e escreve
    `backup-estado.json`.
  - `restaurar.ps1`, `desinstalar.ps1`, `CHECKLIST.md` (roteiro da spec).
  - `build-instalador.sh` gera `quack-servidor-loja.zip` com o jar, o JRE (`jlink`), o WinSW e os scripts.
  - O zip não é commitado: é artefato de release. Diferente do Gateway, ninguém baixa pelo painel nesta fase.
- **Nuvem:**
  - serviço `pdv-api-sync` no `docker-compose.yml` da raiz:
    - imagem da API Java;
    - `SPRING_PROFILES_ACTIVE=nuvem`;
    - mesmo banco e mesmos segredos da `pdv-api`;
    - labels do Traefik para `sync-pdv.quacksistemas.com.br`;
  - no GitHub Actions, publicação da imagem Java (`horus-pdv-api-java`);
  - `DEPLOY.md` explica como ligar o Change Tracking, que o `db/nuvem/V1` faz na primeira subida, e como reverter
    (`db/nuvem/desligar.sql` documentado).

### D13. Testes e aceite

- **Unitários:**
  - catálogo (guardas do D2);
  - serialização tipada (decimal com escala, datas, binário);
  - conferência de base (`NULL`, decimais);
  - backoff;
  - montagem de partes.
- **ITs com dois bancos** (`SincronizacaoIT`, `ComandosIT`, `CargaInicialIT`, `ConferenciaIT`):
  - Testcontainers com dois SQL Server, um da loja e um da nuvem, e dois contextos Spring no mesmo teste (perfis
    `loja` e `nuvem`, portas aleatórias);
  - a "edição da .NET" é simulada por SQL direto no banco da nuvem, sem contexto, o que vale porque a detecção é por
    banco;
  - "sem internet" é um interruptor no cliente HTTP da loja.
  - Cobrem os cenários das specs, um a um.
- **Teste com a .NET de verdade** (`ModoLojaDotnetIT`, opcional por propriedade):
  - sobe o `contrato-api-dotnet` apontado para o banco da nuvem do teste;
  - altera preço e cadastra cliente pelas rotas da .NET, e confere a chegada na loja;
  - prova que a detecção pega a gravação real da .NET.
- **Aceite** (tarefa 8): os E2E do Caixa JavaFX (o comando do D11 da Fase 4) contra o servidor em perfil `loja`.
  - Durante a rodada, a "internet" (o receptor) fica desligada.
  - Depois, liga e roda a conferência: zero diferença, e as vendas dos E2E existem no banco da nuvem.
  - O mesmo com o Quack Estoque.
  - O checklist roda numa máquina Windows de teste com 2 caixas. Esse resultado é registrado à mão no `tasks.md`.

## Risks / Trade-offs

- **[Change Tracking e snapshot no banco de produção custam escrita e `tempdb` para todas as empresas]**
  - Mitigação: retenção de 3 dias, tracking só nas tabelas necessárias e medição antes e depois (tarefa 7.3).
  - Rollback: `desligar.sql`.
- **[Mudança futura de chave primária ou recriação de tabela pela .NET falha com o tracking ligado]**
  - Mitigação: nota no `DEPLOY.md` e no plano.
  - O `db/nuvem` tem um verificador na subida que religa o tracking em tabela que o perdeu e alerta.
- **[A detecção depende do espelho; espelho errado gera comando errado]**
  - Mitigação: o espelho é atualizado na mesma transação do aplicador, e a conferência diária compara também o
    espelho com a loja.
- **[O delta da carga inicial pode não terminar se a nuvem muda sem parar]**
  - Mitigação: na ativação, a nuvem aplica o último delta numa transação `SERIALIZABLE` curta. Os caixas ainda na
    nuvem devem ser parados antes (checklist).
- **[Sem internet não há NFC-e (até a Fase 7)]**
  - Mitigação: a loja só entra em produção depois da Fase 7; o alerta de nota parada cobre o resto.
- **[HTTP na rede local expõe senha e token na LAN]**
  - Mitigação: firewall por sub-rede e rede da loja sem Wi-Fi de clientes (checklist). HTTPS fica como melhoria.
- **[Pausa por erro definitivo trava a nuvem desatualizada até alguém agir]**
  - Mitigação: alerta imediato e motivo na situação. A loja não para de vender.
- **[MCP e painel ajustando estoque na nuvem são revertidos]**
  - Mitigação: o alerta explica que o ajuste de estoque é feito na loja (Quack Estoque). Está documentado.

## Migration Plan

1. Publicar a imagem Java e subir `pdv-api-sync` na nuvem. A primeira subida liga o Change Tracking e cria as tabelas
   `Sync*`. Medir o impacto (tarefa 7.3).
2. Loja de teste: rodar o instalador, registrar, fazer a carga e ativar; apontar 2 caixas; rodar o checklist.
3. Loja real: só depois da Fase 7, seguindo o mesmo checklist.
4. **Rollback por loja** (emergência):
   - revogar o servidor;
   - apontar os caixas para a nuvem;
   - a nuvem já tem a réplica até a última sincronização, e o que não subiu é recuperado pelo `restaurar.ps1` mais uma
     ressincronização manual documentada.
5. **Rollback da nuvem:**
   - parar `pdv-api-sync`;
   - `desligar.sql` (desliga o tracking e o snapshot e mantém as tabelas `Sync*`).

## Open Questions

- Horário do backup e da conferência na loja real (padrões 02:00 e 03:00), a combinar com o dono.
- Tamanho exato de partes e intervalos: os valores do D4 e do D6 são iniciais, ajustados pela medição da tarefa 7.3.
