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

**Ajustes da implementação (grupo 1, tarefas 1.1–1.3):**
- **Classificação (D2) conferida nos gravadores:**
  - `ProdutoClassificacao` e `EstoqueSnapshotDiario` viram `FORA`. O `EstoqueSnapshotWorker` da .NET grava as duas todo
    dia para todas as empresas (`DELETE`+`INSERT` completo e `MERGE`), então sincronizadas gerariam quarentena e alerta
    diários. Ambas se reconstroem de `Vendas`/`EstoqueMovimentacoes`; a loja as calcula na Fase 6.
  - `RegrasEmissaoNfce.UpdatedAt` é `LOJA` (sobe junto com `ContadorVendas`).
  - `Empresas.RequireApprovalForNewCompanies` é `NUVEM` (decisão de plataforma).
  - `Produtos.CustoMedio` e `DataValidade` ficam `EDITAVEL` (só o cadastro grava `CustoMedio`; `DataValidade` também é
    recalculada pelos lotes, e o conflito de base resolve a favor da loja).
  - Limite conhecido: o worker fiscal da .NET também grava `Vendas.Status` (`cancelada`/`estornada`) quando uma nota
    é cancelada ou devolvida na nuvem. `Vendas` é `LOJA`, então isso cai na quarentena; tratar nos grupos 4/5.
- **Change Tracking na loja (D3 × D5):** ligar o tracking na migração `V5` contradiz o D5 (a carga inicial grava num
  banco vazio com o tracking ainda desligado). Escolha: a `V5` só cria as `Sync*`; o `ChangeTrackingAdmin` (bean só no
  perfil `loja`) liga snapshot, banco e tabelas, chamado pela ativação (grupo 3); o `ChangeTrackingLoja`
  (`ApplicationRunner` do perfil `loja`) liga o snapshot na subida e religa o tracking se `SyncEstado.Ativada = 1`.
  O perfil padrão não tem nenhum desses beans.
- **Nuvem:** `db/nuvem/V1__sync_nuvem.sql` (com `V1__sync_nuvem.sql.conf`, `executeInTransaction=false`, e todo o
  script idempotente) cria as `Sync*` e liga snapshot e tracking (retenção 3 dias, `TRACK_COLUMNS_UPDATED = OFF`). A lista
  de tabelas do script é estática e o `ChangeTrackingNuvemIT` confere que é igual ao catálogo. Flyway com
  `baseline-on-migrate` e `baseline-version=0` para o V1 rodar num banco que já tem o esquema da .NET.
- **Ordem (D2):** lista fixa no catálogo, conferida contra `sys.foreign_keys` pelo `CatalogoSchemaIT` (e pela guarda).

**Ajustes da implementação (grupo 2, tarefas 2.1–2.4):**
- **Fio (D4):** a parte é `{loteId, companyId, de, ate, completo, parte, ultima, blocos}`; cada bloco traz colunas tipadas
  (`TipoColuna`), linhas como listas de texto/nulo e chaves de exclusão. O corpo é JSON gzip (`Content-Encoding: gzip`,
  lido por `@RequestBody byte[]`). Como o total de partes só se sabe no fim (a loja envia enquanto lê, no retrato
  SNAPSHOT aberto), a parte final leva `ultima = true`; `SyncLotePartes.TotalPartes` guarda 0 nas demais e o número da
  parte na última. `companyId` no corpo é conferido contra o token (403 se diferente). Colunas `CIFRADA` nem sobem.
- **Idempotência:** `ate < aplicadoAte`, ou `ate == aplicadoAte` com `de < ate`, confirma sem aplicar; `de != aplicadoAte` é
  409 com o esperado. Exceção: lote de reenvio (`de == ate`, de `SyncReenvios`) é aplicado mesmo com `ate == aplicadoAte`.
  Sem linha em `SyncEstadoNuvem` (antes da ativação do grupo 3) qualquer `de` é aceito; a ativação deve criar a linha.
- **Erros da nuvem:** recusa de escopo/tabela/coluna e erro SQL ao aplicar = 422 (a loja pausa); falha transitória do
  banco = 503 (a loja tenta de novo). A loja registra o alerta `sincronizacao-pausada` em `SyncAlertas` (e-mail: 6.2).
- **Ressincronização (D5):** retrato completo substitui; exclui sobras só em tabelas `LOJA` (regra por linha, como
  `Modelo = 55`, preserva a autoria da nuvem). Em `EDITAVEL` a nuvem **não** exclui sobras no grupo 2: sem o espelho (grupo 4)
  não dá para separar "excluída na loja" de "criada na nuvem aguardando comando". Fica para os grupos 4/6.
- **Retrato completo:** a loja lê só as linhas da empresa de `SyncEstado.CompanyId` (filhas pela mãe). A limpeza manual do tracking no teste é desligar e religar (`ChangeTrackingAdmin`); `sp_flush_*` com retenção de 1 minuto não avançou a versão mínima em 4 minutos.
- **Pontos de extensão deixados:** `AntesDoEnvio` (loja, retomada fiscal da 5.1) e `DeteccaoInline` (nuvem, antes/depois de
  cada linha, grupo 4). Contexto do tracking do aplicador: `0x53510001` (`sync-loja`).
- **Teste:** `ClienteNuvemHttp` tem o interruptor `setSemInternet` e o gancho `perderProximasRespostas` (resposta perdida).
  O `SincronizacaoIT` nao repete mais a venda: o deadlock do numerador que exigia isso foi corrigido (ver abaixo).

**Ajuste do deadlock do numerador (vendas simultaneas, pos-grupo 2):**
- **Causa:** a ordem de travas da venda era produtos (D13 da Fase 4) e `INSERT` de movimentos de estoque -> numerador
  (`MAX(SaleNumber)` com `UPDLOCK, HOLDLOCK` em `Vendas`) -> `UPDATE EstoqueMovimentacoes ... WHERE OrigemId = 'pend-...'`
  (vinculo da origem, sem indice em `OrigemId`, varre as movimentacoes da empresa). Quem segura o numerador varre
  movimentos e espera as linhas ainda nao confirmadas de quem espera o numerador: ciclo. Acontece com varios caixas na
  mesma empresa, com ou sem Change Tracking/snapshot (reproduzido nos dois: o tracking nao influi). As vitimas eram o
  `SELECT MAX` e o `UPDATE` de vinculo (erro 1205). A API .NET (`HistoricoVendasAB`) tem a mesma ordem e a mesma corrida.
- **Correcao:** `NumeradorDocumentos.serializarVendas(companyId)` (`sp_getapplock` exclusivo, dono `Transaction`, espera
  ate 30 s) e a PRIMEIRA instrucao da transacao de venda, de finalizacao de pedido e de correcao (os tres caminhos que
  numeram venda). A ordem global passa a ser trava da empresa -> produtos -> movimentos -> numerador. Mantem o contrato
  (numeros consecutivos, mesmas respostas); as vendas da empresa passam a ser seriadas, o que o numerador
  `UPDLOCK/HOLDLOCK` ja fazia em boa parte. Nao muda indice nem esquema (o `EsquemaEquivalenteIT` segue valendo).
- **Prova:** `VendasSimultaneasIT` e `VendasSimultaneasLojaIT` (8 caixas x 20 vendas, produtos sobrepostos em ordens
  opostas e disjuntos, sem retentativa, 5 repeticoes), antes: 5/5 falham; depois: 5/5 passam.

**Ajustes da implementacao (grupo 3, tarefas 3.1 e 3.2):**
- **Registro (3.1):** `POST /api/loja-sync/registrar` chama `AutenticacaoService.autenticar` (o miolo do login extraido:
  mesmo hash, bloqueio, janela de tentativas, mensagens e situacao da empresa, sem criar sessao). Exige
  `administrador` (403), nome do servidor obrigatorio. A rota entra na lista de rotas sem token do `AutenticacaoFilter`.
  O servidor nasce `pendente` e vira `ativo` na ativacao. Um `pendente` (carga que nao terminou) e substituido por um novo
  registro; um `ativo` recusa com 409 citando nome e id. O indice unico (um nao revogado por empresa) cobre a corrida.
  `ServidorLojaFilter`: `lotes` exige `ativo`; `carga`, `carga/delta` e `ativar` aceitam `pendente` ou `ativo`.
  Revogacao: `POST servidores/{id}/revogar`, administrador so da propria empresa (403/404).
- **Carga (3.2) na nuvem:** `LeitorCargaNuvem.pagina` le por chave primaria crescente (cursor = array JSON da chave da
  ultima linha, `depois`), uma transacao SNAPSHOT por pagina, `CIFRADA` decifrada. `SyncCargas` guarda `V` (versao do
  retrato, aberta na primeira pagina e reaproveitada); o campo `versao` da pagina e sempre esse `V`. Como a pagina le o
  estado atual e nao o de `V`, uma linha alterada depois de `V` aparece na pagina e no delta (upsert idempotente).
  `carga/delta?desde=` usa `CHANGETABLE(CHANGES)` das tabelas nao `FORA`, so da empresa; 410 se `desde` passou da retencao.
  **Limite conhecido:** uma linha excluida nao tem empresa para filtrar, entao o delta devolve a chave de toda exclusao
  (so a chave primaria, sem dados) e a loja ignora a que nao tem; o `ativar` tambem trata qualquer exclusao como
  "mudou" (a loja repete o delta, ate 20 vezes).
- **Ativacao:** `POST ativar {desde, versaoLoja}`: a nuvem relê o delta desde `desde`; se nao esta vazio, 409 e a loja
  repete. Vazio: `SyncEstadoNuvem(aplicadoAte = versaoLoja, detectorDesde = versao da nuvem)`, servidor `ativo`, carga
  `ativada`. Sem a linha de `SyncEstadoNuvem` a nuvem aceitaria qualquer `de` (grupo 2): agora ela existe desde a ativacao.
  Em vez de uma transacao `SERIALIZABLE`, a conferencia e em SNAPSHOT e o que mudar depois cai no detector (grupo 4).
- **Lado da loja:** `CargaInicialLoja` (perfil `loja`) e acionada por LINHA DE COMANDO (`CargaInicialRunner`,
  `--quack.sync.carga.executar=true`, `--quack.sync.carga.nuvem-url/email/nome-servidor`, senha na variavel
  `QUACK_SYNC_CARGA_SENHA`; encerra com codigo 0/1 e pode ser repetida). Nao ha rota HTTP: o banco novo nao tem
  administrador para autenticar. Cada pagina e o progresso `SyncCarga(tabela, ultimaChave)` vao na mesma transacao;
  a gravacao e upsert (UPDATE, senao INSERT; `IDENTITY_INSERT` para `AuditLog`), entao repetir nao duplica. Banco novo
  (sem `SyncCarga`): as tabelas sincronizadas sao esvaziadas antes (os dados de referencia do Flyway V2/V3 saem e
  voltam da nuvem). A chave estrangeira de `Categorias` para ela mesma e suspensa durante a carga e revalidada no fim.
  `CIFRADA` e cifrada com o `SecretProtector` da loja. Depois do delta vazio: liga snapshot, banco e tabelas (tracking),
  le a versao `v`, chama `ativar(desde, v)` (409 -> aplica o delta e repete; o que o delta grava com o tracking ligado nao
  sobe porque `v` e relida e a sincronizacao so comeca com `Ativada = 1`), grava `SyncEstado.Ativada = 1`,
  `VersaoDe = AplicadoAteNuvem = v`. Recusa rodar numa loja ja ativada.
- **Referencia global:** `ModuloMercadoRegistros` (`LOJA`) tem chave estrangeira para `ModulosMercado` (`FORA`, global). A carga
  copia antes das demais as tabelas de `ReferenciasGlobais` (hoje so `ModulosMercado`, sem escopo de empresa, sem tracking).
  Uma consulta das chaves estrangeiras do `V1` confirma que e a unica `FORA` referenciada por tabela sincronizada.
- Reuso: `LeitorLinhas` (protocolo) e `ConversorValor.bind` saíram do `LeitorMudancas`/`AplicadorLotes`.

**Ajustes da implementacao (grupo 4, tarefas 4.1 a 4.4):**
- **Espelho (`SyncEspelho`):** guarda, por linha, o valor das colunas `EDITAVEL`, `LOJA`, `NUVEM` e `CIFRADA` (esta com o texto
  cifrado da nuvem; `LOCAL` e chave ficam fora). Existe para tabelas `EDITAVEL` e para as que tem coluna ou linha de autoria da
  nuvem (`DocumentosFiscais`, `FiscalSequencias`): nestas ultimas guarda so a chave (`{}`), que diz "esta linha veio da loja" (sem
  isso, uma nota inserida pelo lote e alterada pelo worker na mesma janela do detector pareceria criada na nuvem). Quem mantem:
  o aplicador (`depois`, relendo a linha da nuvem; coluna `EDITAVEL` bloqueada por comando pendente guarda o valor da loja), a
  ativacao (`AtivacaoNuvem.ativar` recria o espelho no MESMO retrato SNAPSHOT em que confere o delta e le a versao do detector, sem
  janela perdida) e o resultado `aplicado` de `alterar`/`forcar` (o espelho passa a refletir a loja). A pagina da carga registra so
  as chaves, para o delta saber de que empresa e uma exclusao.
- **Detector:** `DetectorNuvem` (2 s, `quack.sync.detector-ativo`) por empresa em modo loja, transacao SNAPSHOT, trava de aplicacao
  `quack:sync-nuvem:<empresa>` (sessao no detector, transacao no `ReceptorLotes.aplicar`) para nao correr com o aplicador.
  `MotorDeteccao` e o nucleo (comparacao com o espelho) e e o mesmo na deteccao inline (`DeteccaoInline` agora devolve as colunas
  bloqueadas por comando pendente; o aplicador nao as escreve). Dedupe: comando pendente identico nao se repete; quarentena aberta
  identica tambem nao. Deteccao completa (alem da retencao) compara so as tabelas `EDITAVEL` com o espelho.
- **Regra por tipo de linha mudada na nuvem:** `EDITAVEL` ou linha de autoria da nuvem (modelo 55): insercao -> `inserir`; exclusao
  (empresa pelo espelho) -> `excluir`; coluna `EDITAVEL` -> `alterar` com base do espelho; `NUVEM`/`CIFRADA` -> `forcar`; coluna `LOJA`
  -> quarentena + `reenviar`. Tabela `LOJA`: linha criada na nuvem -> quarentena (nao exclui, sem `reenviar`); alterada -> `forcar`
  das colunas `NUVEM` se a tabela as tem (resultado fiscal, D8) ou das colunas de um aceite (abaixo) ou, senao, quarentena +
  `reenviar`. Exclusao em tabela `LOJA` nao tem empresa conhecida: nenhum comando, fica para a conferencia (grupo 6).
- **`Vendas.Status` vindo da nuvem (regra escolhida):** o catalogo ganhou `AceiteDaNuvem` por tabela: `Vendas` aceita da nuvem
  `Status` quando a linha ja esta `cancelada` ou `estornada` (mais `CanceladoEm` e `Cancelado*`, que o worker .NET grava junto).
  A mudanca desce por `forcar` (sem quarentena e sem alerta) e a coluna fica bloqueada para o aplicador ate o comando terminar, entao
  um lote antigo da loja nao a desfaz. Mais simples que coluna `NUVEM` condicional (que impediria o cancelamento feito na loja de subir).
  Limite: os efeitos de estoque/fiado que a .NET grava na nuvem ao cancelar a nota (`EstoqueMovimentacoes`, `Produtos.ProductQnt`)
  sao tabelas/colunas `LOJA` e vao para a quarentena e sao revertidos; o estorno de estoque na loja e da Fase 7.
- **Comandos:** `GET /api/loja-sync/comandos?apos=` (long-poll, `quack.sync.comandos-espera-ms`, 25 s) entrega os pendentes com
  `seq > apos` mais os que ja falharam (reentrega); `CIFRADA` sai decifrada e a loja recifra. `POST .../resultados`:
  `aplicado`, `conflito` (alerta `comando-conflito`) e `falhou` (5a falha -> `parado` + alerta `comando-parado`; os seguintes seguem).
  Na loja `ExecutorComandos` (thread propria, perfil `loja`) chama `AplicadorComandos.ciclo()`: cursor `ComandosApos` so avanca depois
  de os resultados chegarem; `SyncComandosAplicados` torna a reentrega idempotente (resultado `falhou` e reaplicado). Conflito de
  `alterar` e de `excluir` poe a linha em `SyncReenvios`, para a nuvem voltar ao valor da loja no proximo lote. `forcar` e `inserir`
  em linha inexistente/ja existente terminam `conflito` (nao `falhou`).
- **Pendencias do grupo 3 resolvidas:** (a) o delta so devolve exclusao cuja chave esta no espelho da empresa (tabelas sem espelho
  ficam para a conferencia); (b) `ativar` usa o mesmo filtro, entao exclusao desconhecida nao gera 409; (c) na ressincronizacao
  completa, sobra em tabela `EDITAVEL` so e excluida na nuvem se tem espelho (a loja a excluiu); sem espelho e linha criada na nuvem
  aguardando comando e fica.
- **Fora desta entrega (anotado):** a entrada "linha da nuvem sobrescrita na ressincronizacao completa" da quarentena (D7) nao foi
  implementada; a rota de situacao e o e-mail dos alertas sao do grupo 6.

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

### Notas do grupo 5 (implementacao da fila fiscal no modo loja)

- `FilaFiscalMinima` le `quack.modo`: `nuvem` nunca roda (independe de `ativa`); `loja` roda so `retomarVendasSemNota()`
  (tanto no agendamento quanto em `processarLote()`); `padrao` mantem o ciclo completo. `ativa=false` desliga tambem a
  retomada na loja.
- `RetomadaFiscalAntesDoEnvio` (`@Profile("loja")`, via `ObjectProvider`) implementa `AntesDoEnvio` e chama
  `retomarAntesDoEnvio()`. Desvio: se alguma venda nao puder ganhar a nota, a retomada lanca excecao e o ciclo de envio
  e abortado e repetido no proximo intervalo (em vez de subir a venda sem nota e deixar o worker da nuvem reservar um
  numero da sequencia dela). Custo: uma venda permanentemente quebrada trava o envio ate ser resolvida; o erro vai ao log.
- A retomada repete em lotes de 20 ate nao sobrar venda sem nota (antes era um lote por ciclo).
- Limite conhecido: a venda gravada entre a retomada e a leitura SNAPSHOT do lote ainda pode subir sem nota, se nao
  nasceu com a pendencia na mesma transacao; o proximo ciclo a retoma, e a nuvem so cria nota de venda sem nota depois
  de seu proprio atraso do worker.

### Notas do grupo 6 (conferência, situação e alertas)

- **Conferência (D10).** `conferencia/SqlConferencia` é o SQL único das duas pontas: por tabela não `FORA` e não
  `ACRESCIMO`, `COUNT_BIG`, `CHECKSUM_AGG(BINARY_CHECKSUM(...))` e, além do pedido, a soma dos checksums em `BIGINT`
  (o XOR sozinho anula duas linhas iguais que mudam juntas). Colunas: chave + `LOJA`/`EDITAVEL`. Linhas de autoria da
  nuvem (`Modelo = 55`) ficam fora. Faixa = `CHECKSUM(chave) & 63` (o `ABS(CHECKSUM) % 64` do design estoura em
  `-2^31`). Fluxo da loja: `resumo` (1 chamada) -> `faixas` -> `linhas` (chave + checksum por linha) -> reenvio via
  `SyncReenvios` (`Motivo = conferencia`) + `EnvioSincronizacao.ciclo()`; sobras na nuvem saem por
  `conferencia/excluir`. Até 3 voltas e uma comparação final; sobrando diferença: resultado `divergente` e alerta.
  Antes de começar a loja roda um ciclo de envio, para não confundir atraso com divergência.
- **Linhas que a conferência não toca** (contam em `pendencias`, não em divergência): comando `pendente`, quarentena
  aberta (a spec manda manter a linha criada na nuvem até um administrador resolver; resolvida, a conferência seguinte a
  exclui) e, em tabela `EDITAVEL`, linha que a nuvem nunca viu vir da loja (criada na nuvem, aguardando comando). A
  nuvem repete essas regras ao excluir.
- **Pendências do grupo 4 fechadas.** Exclusão na nuvem em tabela `LOJA` (sem empresa conhecida) e divergência de
  coluna `LOJA` sem espelho/sem rastro são achadas pelo resumo e corrigidas por reenvio (`ConferenciaIT`). A entrada de
  quarentena "linha da nuvem sobrescrita na ressincronização completa" (D7) existe: no lote `completo`, a deteccão inline
  grava `Origem = ressincronizacao` e motivo "Linha da nuvem sobrescrita na ressincronizacao completa. ...". Limite: só
  pega a edição ainda não vista pelo detector (versão > `DetectorDesde`); uma já vista tem a entrada `edicao-nuvem` e o
  `reenviar` do detector.
- **`AuditLog` fora da conferência** (desvio do D10 "pela contagem do mapa"): as duas pontas inserem nele, a carga copia as
  linhas antigas sem passar pelo mapa e os Ids são próprios, então não há contagem comparável sem uma linha de base.
- **Saldos (só loja, sem corrigir).** Semântica conferida na .NET e na Java: `EstoqueNovo` é o saldo do produto depois do
  movimento e `SaldoAtual` é o saldo do cliente depois do movimento. `ProductQnt` deve ser igual ao `EstoqueNovo` de
  algum dos movimentos com o `CriadoEm` máximo (empate de hora aceito); `SaldoDevedor` idem com `FiadoMovimentos`. Produto
  sem movimento não é conferido. Divergência vira `resultado = divergente`, lista (até 50) em `detalhe.saldos` e alerta.
- **Resultado:** `SyncConferencias` na loja e, por `conferencia/resultado`, na nuvem (tabela já tinha `CompanyId`).
  Agendada às 03:00 (`quack.sync.conferencia-cron`, `quack.sync.conferencia-ativa`) em thread própria (o agendador da
  API é de uma thread só); pedido: `POST /api/loja-sync/conferencia` (administrador local).
- **Situação (D11).** `GET /api/loja-sync/situacao` nas duas pontas (`situacao/SituacaoSync`). Nuvem: administrador (JWT
  da .NET) ou token do servidor; caixa 403, sem credencial 401. Loja: só administrador (JWT local) — desvio: a loja é
  quem *tem* o token do servidor, não há o que aceitar; a loja lê da nuvem, com o token, comandos pendentes/parados e
  quarentena (`nuvemAcessivel = false` sem internet). Atraso = segundos desde a última sincronização se a versão local
  passou da confirmada, senão 0. NFC-e = modelo 65, `Status IN (1,2,8)`, `CriadoEm` há mais de 10 min.
- **Alertas (D11).** `alertas/AlertasSync.registrar` (log `ERROR` + `SyncAlertas`) substitui os dois `MERGE` soltos.
  `EnviadorAlertas` (a cada minuto, nas duas pontas) manda e-mail aos usuários `administrador` ativos da empresa pelo
  `EmailService` **já existente** (SMTP da empresa ou `quack.email.*`; ganhou `enviarAlerta`), nada de interface nova.
  Regra: sai se nunca saiu, ou se houve ocorrência depois do último envio e o último envio tem mais de 1 h. Sem SMTP
  ou sem internet o alerta fica guardado e é tentado no ciclo seguinte. `VerificadorAlertasLoja` (minuto) re-registra
  enquanto a condição durar: pausa, atraso > 15 min (só sem falhas de rede seguidas), NFC-e > 30 min e falha de backup.
  Alertas de evento (quarentena, comando parado/conflito, conferência) são registrados onde acontecem.
- **Backup:** `quack.sync.backup-estado` aponta o `backup-estado.json` do script (grupo 7), formato
  `{"ultimoBackupEm": "<ISO>", "sucesso": true|false, "erro": "<texto>|null"}`. Vazio = sem backup monitorado.
- **Testes:** `ConferenciaIT`, `SituacaoAlertasIT` (SMTP GreenMail; "relógio fixo" = o `agora` passado às rotinas) e
  `ResincronizacaoQuarentenaIT`; `AmbienteSync.iniciarComExtras` e `tokenLoja`.

### Notas do grupo 7 (instalador e nuvem)

- **Pacote (`JAVA/api/instalador-loja/`).** `instalar.ps1`, `backup.ps1`, `restaurar.ps1`, `desinstalar.ps1`, `lib/comum.ps1`
  (funções puras, testáveis), `winsw/QuackLojaApi.xml.modelo`, `CHECKLIST.md`, `build-instalador.sh` e `testes/`. O zip
  (`target/quack-servidor-loja.zip`, ~112 MB) não é versionado, como manda o D12. JRE por `jlink` (`java.se` +
  `jdk.unsupported`, `jdk.crypto.ec`, `jdk.localedata`, `jdk.charsets`, `jdk.zipfs`, `jdk.management`, `jdk.naming.dns`,
  `jdk.httpserver`; 61 MB). O `jlink` gera o JRE do sistema do JDK usado: o pacote de Windows sai de um Windows ou de
  `JDK_WIN_HOME`. WinSW v2.12.0 baixado uma vez, com SHA-256 fixado no script.
- **Segredos e configuração.** O WinSW v2 não lê arquivo de variáveis; por isso o serviço roda com
  `--spring.config.import=file:./config/loja.env[.properties]` (diretório de trabalho = pasta da instalação; sem
  `optional:`, então a API não sobe sem o arquivo). O `loja.env` é `.properties`: tem as chaves de placeholder do
  `application.yml` (`SPRING_DATASOURCE_*`, `QUACK_AUTH_JWT_SECRET`, `QUACK_SECURITY_ENCRYPTION_KEY`) e as propriedades
  `quack.sync.nuvem-url` e `quack.sync.backup-estado`. Segredos de 48 bytes (base64url, 64 caracteres), ACL só
  Administradores e SYSTEM. Reexecutar o instalador **reaproveita** os segredos existentes (trocar a chave de cifra
  invalidaria os dados cifrados). Gravado em UTF-8 **sem BOM** (o Jackson do `EstadoBackup` e o Spring não toleram BOM; o
  `Set-Content -Encoding UTF8` do PowerShell 5.1 grava BOM).
- **Banco.** A API não usa o `sa`: o instalador cria o login `quack_app` (senha aleatória, só no `loja.env`) como
  `db_owner` do banco `QuackLoja` (o Flyway cria o esquema). A senha do `sa` do SQL Express é aleatória e não é guardada
  (o acesso de manutenção é pelo Windows: Administradores e SYSTEM são `sysadmin`). Recuperação `SIMPLE`: há backup
  completo diário e nenhum de log, então o log não cresce sem limite; perda máxima de até 24 h num disco perdido (a
  nuvem guarda o que já subiu). O acesso do instalador/backup ao SQL é por `System.Data.SqlClient` (já no Windows),
  sem depender do `sqlcmd`.
- **Rede.** SQL escuta só em `127.0.0.1:1433` (registro: `ListenOnAllIPs=0`, só o IP de loopback ativo) e há ainda uma regra
  de firewall de **bloqueio** da porta; a API tem regra de permissão só para a sub-rede (detectada pela rota padrão com
  IPv4 de prefixo até /30, ou `-Subrede`). VPN e adaptadores virtuais costumam ter /32: a primeira versão pegava esse e
  falhava, a validação na máquina de desenvolvimento achou.
- **Backup.** `BACKUP DATABASE ... WITH COMPRESSION, CHECKSUM, INIT`, depois `RESTORE VERIFYONLY ... WITH CHECKSUM`, e
  só então a retenção apaga os mais antigos (7 mantidos). Falha: nada é apagado, `backup-estado.json` fica com
  `sucesso:false`, `erro` com a causa-raiz do SQL e `ultimoBackupEm` do último sucesso preservado, e o script sai com 1
  (a tarefa agendada mostra a falha). A tarefa roda como SYSTEM com `StartWhenAvailable` (máquina desligada às 02:00
  faz o backup ao ligar). Horário e retenção são parâmetros do instalador (`-HoraBackup`, `-RetencaoBackups`); o
  horário real fica para combinar com o dono (Open Question do D12).
- **Restauração e conferência (achado importante).** O backup é mais antigo que a nuvem. A conferência (D10) trata a nuvem
  como reflexo da loja nas tabelas `LOJA` e exclui o que sobra lá: depois de uma restauração ela **apagaria da nuvem** as
  vendas posteriores ao backup. Por isso o `restaurar.ps1` desliga `quack.sync.conferencia-ativa` no `loja.env` (religa
  com `-ReativarConferencia`) e o `CHECKLIST.md` descreve os dois caminhos (restaurar um backup recente, ou reinstalar e
  fazer a carga inicial a partir da nuvem quando o que importa é não perder o que já subiu). Decisão de produto para o
  grupo 8 / dono: uma restauração deveria forçar uma ressincronização completa *da nuvem para a loja*, hoje inexistente.
- **Nuvem (7.2).** `pdv-api-sync` acrescentado ao `docker-compose.yml` (só linhas novas; a saída de `docker compose
  config` sem perfil é idêntica à anterior), com `profiles: ["nuvem"]`, imagem `horus-pdv-api-java`, mesmo banco e segredos
  da `pdv-api` e Traefik `sync-pdv`. Desvio: a publicação da imagem foi para um **workflow próprio**
  (`api-java-publish.yml`, push em `main` com `JAVA/api/**`), e não para o `docker-publish.yml`, para um push da Java não
  reconstruir a .NET e o frontend nem disparar o webhook de redeploy deles. `profiles` não é aceito por
  `docker stack deploy` (Portainer em Swarm): está no `DEPLOY.md`. `.dockerignore` da Java ganhou `instalador-loja/` e
  `db/`. O `docker build` local da imagem passou.
- **`desligar.sql` não existia.** O D12 e o `DEPLOY.md` o citam; foi criado em `JAVA/api/db/nuvem/desligar.sql`, **fora**
  do classpath (em `resources/db/nuvem` o Flyway o tomaria por migração). Provado em container: 38 tabelas, banco e snapshot
  desligados; repetir não falha; reaplicar o `V1__sync_nuvem.sql` (idempotente) religa tudo. Pendência: o **verificador de
  subida** que "religa o tracking em tabela que o perdeu e alerta" (Risks do design) **não existe** (o `ChangeTrackingAdmin`
  é só do perfil `loja`); o `DEPLOY.md` manda conferir `sys.change_tracking_tables` à mão. Fica para o grupo 8.
- **7.3, custo medido** (`contrato/medicao-tracking/`; números no `DEPLOY.md`). SQL Server 2022 (RTM-CU27) em container, dois
  bancos com o esquema `db/migration` V1..V4, 2 000 produtos. `medir.sh`: 10 000 vendas por rodada, 3 rodadas alternadas,
  cada venda uma transação de 11 linhas (`vendas.sql`). Resultado: mediana **107,8 s sem** e **112,2 s com** tracking
  + snapshot (**+4,0%**; as rodadas com tracking foram 110,4 / 126,9 / 112,2 s, e sem 107,0 / 107,8 / 112,3 s: ruído de
  uma máquina de desenvolvimento). Espaço após 30 000 vendas: 121,7 MB sem e 169,0 MB com (+47,3 MB; as tabelas internas do
  tracking respondem por 21,8 MB, ~0,73 KB por venda; o resto é o acréscimo de 14 bytes por versão de linha e as tabelas
  `Sync*`). Version store do `tempdb`: 14,7 MB logo após a carga sem leitor, voltando a 0; com um leitor `SNAPSHOT` aberto
  durante 10 000 vendas, pico amostrado de 23 MB e 0 MB depois que o leitor fechou (`tempdb` 80 MB). Conclusão: custo de
  escrita de poucos por cento e `tempdb` quase sem efeito, porque as leituras do receptor são curtas. Limites: um só
  cliente, sem contenção; valores absolutos de laptop.
- **7.3, suíte de contrato da .NET** (`contrato/medicao-tracking/suite-dotnet.sh`): a suíte inteira (`GravadorDoContrato`, todos os grupos, 1 152 gravações) rodou contra a API .NET real (imagem `quack-api-dotnet:contrato`, SQL próprio e descartável) **sem** o `db/nuvem/V1`: **1 896 s**, e **com** ele aplicado (38 tabelas com tracking + snapshot; a .NET reiniciada sobre o banco já rastreado, sem erro de inicialização): **1 784 s** (diferença dentro do ruído, a execução com tracking até foi mais curta). As 1 152 respostas têm o mesmo status HTTP nas duas rodadas e os mesmos arquivos; o corpo só difere em campos de relógio e de id gerado (`serverNow`, `openedAt`, `sessionId`, `elapsedMinutes`...), que mudam entre quaisquer duas execuções. Ou seja: a suíte da .NET passa igual com o tracking ligado. `comparar-gravacoes.py` faz a comparação; as gravações do repositório não foram alteradas (a suíte grava numa cópia de `JAVA/api`).
- **O que não foi validado sem máquina Windows dedicada** (vai para o checklist 8.4): a instalação real do SQL Server
  Express (download, `SQLEXPR` silencioso, serviço `MSSQL$QUACK`), o ajuste de TCP no registro, o `WinSW install/start` e o
  início automático depois de reiniciar a máquina, a regra de firewall e a recusa vinda de outra sub-rede, a tarefa
  agendada às 02:00 rodando como SYSTEM, a ACL do `loja.env`, o registro e a carga inicial contra a nuvem real com
  acompanhamento pela situação (login local por cookie/Bearer), a desinstalação, e a troca do endereço nos caixas. Foram
  provados à parte: sintaxe e lógica dos `.ps1` (parser e `testes/Testar-Logica.ps1`), `instalar.ps1 -SomenteValidar`
  (código 0) e `-WhatIf`, backup/retenção/estado/restauração contra SQL Server em container, e o jar com o JRE do `jlink` e um
  `loja.env` gerado pelo instalador subindo em `UP` no perfil `loja` (`testes/Testar-ApiPacote.ps1`).

### Notas da recuperação (tarefa 7.4: servidor restaurado de backup)

**Problema.** O banco restaurado é mais antigo que a nuvem. (a) As vendas, notas, movimentos e cadastros feitos depois do
backup só existem na nuvem, e a conferência (D10), que trata a nuvem como reflexo da loja nas tabelas `LOJA`, os apagaria
de lá. (b) O Change Tracking voltou atrás: `SyncEstado.VersaoDe` e as versões novas da loja reusam números que a nuvem já
aplicou. Com `aplicadoAte` da nuvem à frente, um lote com `ate < aplicadoAte` é respondido "já aplicado" e a loja avança a
versão **pulando as mudanças** (sem erro nenhum); com `ate >= aplicadoAte` vem 409 e a loja adota a versão da nuvem, também
pulando. (c) Os contadores de numeração voltam atrás. Decisão do dono: puxar tudo da nuvem antes de voltar ao normal.

**Desenho** (`plataforma/sincronizacao/recuperacao`, estado em `SyncRecuperacao`, migração `V6`):
- **Disparo.** `restaurar.ps1` deixa `config\recuperar.pendente` (propriedade `quack.sync.recuperacao-marcador`, padrão
  `config/recuperar.pendente` relativo à pasta da instalação, que é o diretório de trabalho do serviço). A API o transforma
  em estado `pendente` no banco e apaga o arquivo (nessa ordem: se cair no meio, o marcador continua). O banco restaurado
  não sabe que foi restaurado, por isso o marcador é o gatilho. Também por linha de comando
  (`--quack.sync.recuperacao.executar=true`, como a carga inicial; saída 0 = concluída) e por `POST /api/loja-sync/recuperar`
  (administrador). Rede de segurança para restauração feita sem o script: a cada `recuperacao-verificacao-ms` (5 min) a
  loja lê a situação da nuvem e, se `versaoConfirmada` > versão local do tracking (impossível sem regressão), pede a
  recuperação. `-SemRecuperacao` no script é a exceção (nuvem sem nada além do backup).
- **Vendas durante a recuperação: bloqueadas.** `RecuperacaoFilter` responde 503 com `Retry-After` a tudo, menos
  `/actuator/health`, `/api/auth/login|logout|me` e `/api/loja-sync/**` (o administrador acompanha). O Caixa trata como
  servidor fora do ar e segue na fila offline (spec "Ligação dos caixas"). Opção descartada: aceitar vendas durante a
  recuperação. Seria preciso numerar vendas e notas contra um banco em que ainda faltam as vendas da nuvem (repetiria
  número), o estoque e o fiado seriam recalculados sobre saldos defasados e as vendas novas subiriam com versões que a
  reativação (abaixo) redefine. O marcador conta como "em andamento" desde a primeira requisição, antes mesmo de virar
  estado no banco. Envio, conferência (manual e agendada), executor de comandos da nuvem, fila fiscal e retentativa de lotes
  também param (`EstadoRecuperacao.emAndamento()`).
- **Fases** (cada uma gravada; qualquer queda ou falta de internet retoma de onde parou, sem duplicar):
  1. `preparando`: guarda `VersaoAntiga` (= `SyncEstado.VersaoDe` restaurado) e enfileira em `SyncReenvios` as linhas
     mudadas na loja desde essa versão (`CHANGETABLE(CHANGES, VersaoAntiga)`), isto é, o que o backup tinha e a nuvem talvez
     não tenha. Se o tracking do banco restaurado for inutilizável (ex.: restaurado em outro servidor sem `KEEP_CT`), marca
     `RetratoCompleto` e, no fim, `VersaoDe = -1` faz o envio mandar o retrato completo (D5).
  2. `puxando`: usa as rotas da carga inicial (`GET /carga`, com `renovar=true` na primeira página para não reusar uma
     carga aberta antiga). Tabela a tabela, na ordem do catálogo, upsert das linhas da empresa. **A nuvem vence** nas linhas
     que existem nos dois lados: é o valor mais recente que a loja enviou. Diferenças do upsert da carga inicial
     (`AplicadorCargaLoja`, modo `recuperacao`): colunas `LOCAL` não são tocadas no `UPDATE`; `FiscalSequencias.ProximoNumero`
     e `RegrasEmissaoNfce.ContadorVendas` só crescem (`CASE WHEN`); `AuditLog` entra com Id novo da loja e só se não houver
     linha igual (a nuvem numera a auditoria por conta própria). Nenhuma linha é excluída.
  3. `alinhando`: delta da nuvem desde o retrato até vir vazio e `POST /ativar` com `recuperacao=true`, o mesmo da carga
     inicial: religa o tracking, lê a versão `v` da loja e a nuvem passa a ter `aplicadoAte = v`, refaz o espelho e o
     `detectorDesde` no mesmo retrato SNAPSHOT em que confere o delta vazio. A loja grava `VersaoDe = v`: as escritas da
     própria puxada (que o tracking viu) não sobem de volta. Com `recuperacao=true` a nuvem também dá como aplicados os
     comandos `alterar`, `inserir` e `forcar` pendentes (o retrato já os cobre; sem isso viram conflito). `excluir` e
     `reenviar` ficam: a linha excluída na nuvem pode continuar viva no backup.
  4. `sequencias`: `FiscalSequencias` (modelo 65) passa de `MAX(NumeroNf) + 1` das notas da loja e ganha linha se faltar
     (o numerador cria com 1 se a linha não existir, o que repetiria número). Numeração de venda, pedido, ordem de
     compra e inventário é `MAX + 1` das linhas, então já acompanha o que foi puxado. Roda depois da reativação: o que muda
     aqui sobe no primeiro lote, junto com as linhas de `SyncReenvios` da fase 1.
- **Change Tracking e `aplicadoAte`** (resumo): o realinhamento é a reativação, com o espelho refeito no mesmo retrato. A
  nuvem nunca vê a loja "voltar no tempo": depois de `ativar`, o primeiro lote sai de `v` e a nuvem espera `v`.
- **Sequências.** Contador guardado (fiscal, `ContadorVendas`): nunca diminui na puxada e é elevado ao maior número real. Contador
  derivado (`MAX + 1`): vem das linhas.
- **Conflitos.** Linha alterada no backup e depois dele: vale a da nuvem, que é o valor mais recente que a loja enviou. Exceção
  exata: a loja sabe até que versão a nuvem aplicou (`versaoConfirmada`, lida no início). Linha mudada no banco restaurado com versão
  de tracking acima disso a nuvem nunca viu (ex.: venda feita sem internet antes do backup): é "protegida", entra em
  `SyncReenvios` (motivo `recuperacao`) e, na puxada, mantém as colunas `LOJA` do backup (o estoque do produto continua batendo com a
  venda que só existe ali) e recebe da nuvem as colunas `EDITAVEL`/`NUVEM`. Linha só no backup e não enviada: fica e sobe. Linha que a
  nuvem tem e o backup não: entra.
- **Situação.** `GET /api/loja-sync/situacao` ganha `recuperando` e `recuperacao` (estado, fase, motivo, tentativas, linhas
  puxadas, tabela atual, último erro). Alerta `recuperacao-pendente` se passar de 10 minutos (e-mail, no máximo um por hora).
- **Sem internet.** `ClienteCarga` falha com status 0: estado volta a `pendente` com o erro; a rotina repete a cada 15 s
  (`recuperacao-intervalo-ms`); vendas, envio e conferência seguem parados.
- **Limites aceitos** (todos no `CHECKLIST.md`): (1) exclusão feita depois do backup (na loja ou na nuvem, de cadastro ou
  não) não é refeita: a linha volta à loja e a conferência a devolve à nuvem; só a exclusão feita na nuvem e ainda não vista
  pelo detector (comando `excluir` pendente) é preservada; (2) uma edição da loja não enviada à época do backup, em linha que a
  nuvem também mudou depois, fica com o valor da nuvem; (3) `ProcessedEvents` não é sincronizado (sincronizá-lo dobraria o volume, cada venda
  tem um evento com JSON de resposta, e a .NET também o grava). Em vez disso a deduplicação usa o próprio dado que voltou da
  nuvem: a **venda** com o mesmo `ClientSaleId` (índice único já existente) responde como repetição, com a resposta refeita das
  linhas da venda, e o registro do evento é recriado; o **movimento de caixa** ganhou `Id` derivado do `EventId` (SHA-256 de
  empresa e evento), então a linha que voltou é reconhecida. Restam sem prova no dado: *abrir* caixa online sem
  `clientSessionId` e *fechar* caixa (o reenvio é recusado com 400 "já foi fechado", sem duplicar nada), e o reenvio de
  movimento cujo turno já fechou ou cuja sangria já não cabe no saldo (também 400, sem duplicar). Testes: `RecuperacaoLojaIT`
  (venda) e `CaixaIT` (movimento); (4) o `UPDATE` de linhas idênticas gera entradas no Change
  Tracking da loja, que a reativação descarta (custo de espaço, não de correção); (5) a restauração fora do script só é
  percebida na próxima verificação de versão.
