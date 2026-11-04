# Tasks

> **Antes de cada grupo:**
> - leia a decisão correspondente em `design.md` e a spec da capacidade em `specs/`;
> - para cada tabela, leia na Java (`JAVA/api`) e na .NET (`API/NETCORE`, só leitura) quem grava cada coluna.
>
> **Onde trabalhar:** branch `spring-api`, em `JAVA/api`.
>
> **Regra do dono do produto: não altere nada em `API/NETCORE/` nem em `GATEWAY/`.**
> - A .NET só é lida e usada como ambiente de teste (`contrato/ambiente-dotnet`).
> - Não versione o PDF de `docs/`. Use `git add` só com caminhos explícitos.
>
> **Antes de rodar `mvn verify`:** feche qualquer API Java iniciada pelo `.jar` de `target/`.
>
> **Cada grupo termina com:**
> - os testes dele verdes;
> - `mvn -B -o verify` verde (o contrato existente não pode quebrar);
> - um commit sem push.

## 1. Base: perfis, catálogo e tabelas

- [x] 1.1 Criar `plataforma/sincronizacao/catalogo/CatalogoSincronizacao` com a classificação do D2.
  - Confira cada tabela e coluna lendo os gravadores na Java e na .NET; registre no próprio catálogo, em comentário
    curto, o motivo de cada coluna `LOJA`, `NUVEM` ou `LOCAL` fora do padrão.
  - Inclua as filhas sem `CompanyId` com a mãe e a ordem pelas chaves estrangeiras.
  - Verificação: teste de guarda que falha com tabela sem classificação, coluna inexistente, ou tabela sincronizada
    sem chave primária (prove com uma tabela fictícia); e teste da ordem contra `sys.foreign_keys` do `V1`.
- [x] 1.2 Criar os perfis `loja` e `nuvem` (D1).
  - Propriedade `quack.modo`, `application-loja.yml`, `application-nuvem.yml`.
  - Filtro de 404 do perfil `nuvem`; fila fiscal e retentativa de lotes desligadas no `nuvem`.
  - Flyway do `nuvem` em `db/nuvem` com tabela `flyway_quack_sync`, sem rodar `db/migration`.
  - Verificação:
    - teste web: `nuvem` responde 404 em `/api/HistoricoVendas` e 200 em `/actuator/health`;
    - IT: subir o `nuvem` num banco com o esquema da .NET deixa `flyway_schema_history` intocado e cria só as tabelas
      `Sync*`.
- [x] 1.3 Criar as migrações das tabelas de sincronização.
  - Na loja (`db/migration/V5`): `SyncEstado`, `SyncReenvios`, `SyncAlertas`, `SyncConferencias`, `SyncCarga` e
    `SyncComandosAplicados` (sequência já aplicada, para idempotência).
  - Na nuvem (`db/nuvem/V1`): `SyncServidores`, `SyncEstadoNuvem`, `SyncLotePartes`, `SyncEspelho`, `SyncComandos`,
    `SyncQuarentena`, `SyncAuditoriaMapa`, `SyncAlertas`, `SyncConferencias` e `SyncCargas`.
  - Ligar o Change Tracking e o snapshot (D3): na loja só no perfil `loja`, por uma migração condicional ou um
    `FlywayConfigurationCustomizer`; na nuvem no `V1`.
  - Verificação:
    - `EsquemaEquivalenteIT` ainda verde, porque as tabelas novas ficam fora da comparação;
    - IT confirmando o tracking ligado nas tabelas não `FORA` e desligado nas `FORA`, nas duas pontas;
    - o banco de teste do perfil padrão continua sem tracking.

## 2. Envio loja → nuvem

- [x] 2.1 Serialização tipada das linhas e dos lotes (D4): decimal com escala, data/hora, binário, nulo; partes e gzip.
  - Verificação: testes unitários de ida e volta com cada tipo do `V1` e um lote dividido em 3 partes.
- [x] 2.2 `EnvioSincronizacao` na loja (D4).
  - Leitura em `SNAPSHOT`, `CHANGETABLE` por tabela na ordem do catálogo, partes, `SyncEstado` e backoff com jitter.
  - Pausa em erro definitivo, retomada por rota, reenvios de `SyncReenvios`.
  - Verificação: unitário do backoff e da classificação dos erros (408/429/5xx/rede contra 4xx/409).
- [x] 2.3 Rota `POST /api/loja-sync/lotes` e `AplicadorLotes` na nuvem (D4), com o filtro do token do servidor (D9).
  - Escopo por `CompanyId` e pela mãe, contexto `sync-loja`, classes de coluna e `SyncAuditoriaMapa`.
  - Idempotência por `aplicadoAte`, 409 de versão, partes, limite de 16 MB só nessa rota.
  - Verificação: `SincronizacaoIT` (dois bancos, dois contextos) com os cenários da spec
    `plataforma/sincronizacao-loja-nuvem`:
    - venda completa chega em menos de 1 minuto;
    - exclusão;
    - venda e nota na mesma transação;
    - resposta perdida e reenvio sem efeito;
    - item de outra empresa recusa o lote e pausa;
    - `AuditLog` duas vezes vira uma linha;
    - uma hora de vendas offline em 7 "caixas" (threads), que convergem ao religar.
- [x] 2.4 Ressincronização completa (D5) quando a versão passou da retenção.
  - Verificação: IT que força `MIN_VALID_VERSION` (retenção mínima e limpeza manual do tracking) e termina com a
    conferência do grupo 6 sem diferença. Se o grupo 6 ainda não existir, comparar contagens e somas por SQL no teste.

## 3. Registro, carga inicial e ativação

- [x] 3.1 `POST /api/loja-sync/registrar` (D5 e D9).
  - Validação de login igual à da .NET, só administrador, um servidor ativo por empresa, token com hash.
  - Revogação por administrador.
  - Verificação: testes web e IT dos cenários da spec `plataforma/modo-loja` (gerente recusado, segundo servidor,
    token de outra empresa 403, revogado 401).
- [x] 3.2 Carga e delta (`GET /api/loja-sync/carga`, `/carga/delta`, `POST /ativar`) e o lado da loja.
  - Banco vazio, páginas retomáveis, segredos recifrados, tracking ligado só na ativação.
  - Verificação: `CargaInicialIT`:
    - carga completa com contagens iguais;
    - queda no meio e retomada sem duplicar;
    - produto alterado na nuvem durante a carga chega antes da ativação;
    - senha de certificado decifrável com a chave da loja e diferente da da nuvem;
    - token de sessão da nuvem recusado na loja (401);
    - login do usuário na loja com a senha da nuvem.

## 4. Comandos nuvem → loja

- [ ] 4.1 `SyncEspelho` mantido pelo aplicador e `DetectorNuvem` (D6), periódico e inline.
  - Ignora o contexto `sync-loja`.
  - Cria `alterar`, `inserir`, `excluir` e `forcar`; em tabela `LOJA` grava a quarentena e cria `reenviar`.
  - Detecção completa quando o detector passa da retenção.
  - Verificação: unitário da comparação com o espelho, e IT em que um `UPDATE` por SQL sem contexto no banco da nuvem
    gera o comando certo, enquanto um lote aplicado não gera nenhum.
- [ ] 4.2 Entrega (`GET /comandos` com long-poll, `POST /comandos/resultados`), tentativas e parada na 5ª falha.
  - Lado da loja: `AplicadorComandos` com conferência de base coluna a coluna, contexto `comando-nuvem`, idempotência
    por `SyncComandosAplicados` e `AuditLog`.
  - Verificação: `ComandosIT` com os cenários da spec `plataforma/comandos-nuvem-loja`:
    - preço novo vale no próximo caixa;
    - cliente novo;
    - edição simultânea na loja e na nuvem vira conflito e a loja vence;
    - colunas diferentes valem as duas;
    - a venda logo depois da edição de preço não apaga o preço;
    - comandos guardados sem internet são aplicados em ordem;
    - comando que falha 5 vezes para sem travar os seguintes.
- [ ] 4.3 Quarentena e reversão (D7).
  - Venda criada na nuvem vai para a quarentena e não é excluída.
  - Saldo alterado na nuvem volta ao da loja, com alerta.
  - Exclusão em tabela `LOJA` é corrigida pela conferência.
  - Rota de resolver.
  - Verificação: IT de cada caso.
- [ ] 4.4 Resultado fiscal (D8): colunas `NUVEM` de `DocumentosFiscais` descem por `forcar`; modelo 55 é autoria da
  nuvem.
  - Verificação: IT em que um `UPDATE` "da .NET" na nota (status 100, protocolo, XML) chega à loja mantendo número e
    chave, e uma NF-e modelo 55 criada na nuvem desce para a loja.

## 5. Fila fiscal no modo loja

- [ ] 5.1 No perfil `loja`, a `FilaFiscalMinima` roda só a retomada das vendas sem nota. O envio chama a retomada
  antes de montar o lote (D4 e D8). No `nuvem`, nunca roda.
  - Verificação:
    - IT: venda cancelada com nota pendente no perfil `loja` não muda a nota;
    - IT: venda sem nota ganha a pendência com o número da sequência da loja antes de subir;
    - `FilaFiscalMinimaIT` existente ainda verde no perfil padrão.

## 6. Conferência, situação e alertas

- [ ] 6.1 `ConferenciaSincronizacao` (D10): contagem e soma por tabela, faixas, reenvio e exclusão do que sobra; saldos
  de estoque e de fiado (confira antes a semântica de `EstoqueNovo` e `SaldoAtual` na .NET); agendamento às 03:00 e
  rota de pedido.
  - Verificação: `ConferenciaIT`:
    - linha mudada no banco da nuvem com o tracking desligado temporariamente é acusada, reenviada e fecha;
    - saldo de produto divergente é listado sem correção;
    - resultado gravado.
- [ ] 6.2 `GET /api/loja-sync/situacao` nas duas pontas (D11) e alertas com limite de um por motivo por hora, por log
  e e-mail.
  - Verificação:
    - teste web: administrador 200, caixa 403, token do servidor 200;
    - IT: sincronização pausada gera um e-mail (`EmailService` com servidor SMTP falso) e não gera outro na mesma hora;
    - nota pendente há mais de 30 minutos (`Clock` fixo) gera alerta.

## 7. Instalador e nuvem

- [ ] 7.1 `JAVA/api/instalador-loja/` (D12): `instalar.ps1`, `backup.ps1`, `restaurar.ps1`, `desinstalar.ps1`, a
  configuração do WinSW, `CHECKLIST.md` e `build-instalador.sh` (zip com jar, JRE de `jlink`, WinSW e scripts).
  - Verificação:
    - `build-instalador.sh` gera o zip;
    - `instalar.ps1 -SomenteValidar` roda sem erro numa máquina Windows;
    - o backup e a restauração são testados contra um SQL Server local (Docker serve);
    - registre a saída no `tasks.md`.
- [ ] 7.2 Serviço `pdv-api-sync` no `docker-compose.yml` da raiz (perfil `nuvem`, Traefik `sync-pdv`), publicação da
  imagem Java no workflow do GitHub Actions e seção no `DEPLOY.md` (subida, `desligar.sql`, alerta sobre chave
  primária).
  - Verificação: `docker compose config` válido e a imagem buildando no Actions (ou `docker build` local); a API .NET
    e o frontend sem nenhuma linha alterada no compose.
- [ ] 7.3 Medir o custo do Change Tracking e do snapshot.
  - Rodar a suíte de contrato da .NET (`contrato/ambiente-dotnet`) com e sem o `db/nuvem/V1` aplicado e comparar o
    tempo.
  - Verificação: os números ficam registrados no `DEPLOY.md`, e a suíte da .NET passa igual com o tracking ligado.

## 8. Aceite e integração

- [ ] 8.1 `ModoLojaDotnetIT` (D13): a .NET real (`contrato-api-dotnet`) apontada para o banco da nuvem do teste muda
  um preço e cadastra um cliente pelas rotas dela, e os dois chegam à loja.
  - Verificação: o IT verde, rodado com a propriedade que o liga.
- [ ] 8.2 Aceite com os clientes JavaFX (D13).
  - Rodar os E2E do Caixa (comando do D11 da Fase 4) e do Quack Estoque contra a API em perfil `loja`, com o receptor
    desligado.
  - Depois, ligar o receptor e rodar a conferência.
  - Verificação: os relatórios do Surefire verdes, a conferência sem diferença e as vendas dos E2E no banco da nuvem.
- [ ] 8.3 `mvn -B -o verify` completo; atualizar documentos e o grafo; commit `docs(api-java): fecha a Fase 4b
  (modo loja)`.
  - Documentos:
    - `JAVA/api/README.md`: perfis, rotas `loja-sync`, propriedades, instalação, alertas;
    - plano `docs/migracao-api-springboot.md`: linha da 4b e § 3.3 com as decisões de 04/11/2026 (receptor Java na
      nuvem; cadastros, preços e configurações por comando; fiscal com autoria dividida até a Fase 7).
  - Rodar `graphify update .`.
  - Verificação: o relatório do verify e os comandos documentados conferidos.
- [ ] 8.4 Rodar o `CHECKLIST.md` numa máquina Windows de teste com 2 caixas: queda de internet, queda do servidor e
  restauração do backup.
  - Verificação: cada item com resultado registrado aqui. Precisa do dono do produto ou de uma máquina disponível;
    se não houver, deixe a tarefa aberta e diga isso no relatório.

## Workflow follow-up

- Arquivar a change com `/opsx:archive` depois do aceite.
- Fase 7 (emissão fiscal na loja): retomar o D8 deste design. A nota deve subir já com o resultado, para o worker da
  nuvem não emitir de novo.
