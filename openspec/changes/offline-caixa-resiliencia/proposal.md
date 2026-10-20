## Why

No incidente de 19/10/2026 a internet do caixa caiu por ~15h e, quando voltou, nada se recuperou
sozinho: 67 eventos (58 vendas) ficaram abandonados como `FAILED` por "rede indisponível", 91 vendas
ficaram presas como `FISCAL_PENDING`, 5 fechamentos e 1 abertura de caixa foram recusados pela nuvem
— e o painel do Gateway dizia "tudo sincronizado". A recuperação exigiu SQL manual no caixa e na VPS.
O operador, sem saber que podia continuar vendendo offline, fechou o caixa às 19:03 e as vendas até
as 22h ficaram fora do sistema. (O endereço errado do Gateway na contingência fiscal já foi corrigido
no commit `b94abbf`.)

## What Changes

- **Gateway e PDV nunca abandonam um evento por falha transitória** (rede, timeout, 408/429/5xx,
  401/403): continuam tentando com backoff exponencial com teto, sem limite de tentativas. Só recusa
  de negócio (demais 4xx, 409) leva o evento a `FAILED`.
- **Painel do Gateway e checagem de atualização consideram eventos `FAILED`**: não exibem "seguro /
  tudo sincronizado" enquanto houver falha, e mostram a contagem e o motivo.
- **Fechamento offline fecha a sessão certa, com o esperado da época**: a API passa a receber o id da
  sessão do cliente e o horário do fato (abertura/fechamento), calcula o dinheiro esperado até o
  horário do fechamento e **nunca** fecha uma sessão diferente da indicada no evento.
  **BREAKING** (contrato interno PDV/Gateway → API): novos campos opcionais `ClientSessionId`,
  `OpenedAt`/`ClosedAt` em abrir/fechar caixa; sem eles, o comportamento atual (online) é mantido.
- **Abertura offline usa o id de sessão do cliente**, para que as vendas offline (que carregam esse id)
  entrem no cálculo do caixa certo.
- **Ordem da cadeia do caixa**: enquanto um evento de caixa de um terminal estiver recusado, os eventos
  de caixa seguintes do mesmo terminal ficam retidos (as vendas continuam subindo).
- **Recusa de negócio fica visível e tratável**: o PDV mostra fechamentos recusados com o motivo e
  permite ao gerente informar a justificativa e reenviar o mesmo evento.
- **Operador sabe que está offline**: aviso permanente "Sem internet — pode continuar vendendo, as vendas
  estão guardadas" e alerta ao tentar fechar o caixa com pendências.
- **Histórico mostra vendas locais ainda não sincronizadas** junto com as da nuvem, marcadas como
  pendentes.

## Capabilities

### New Capabilities
- `pdv/sincronizacao-offline`: entrega garantida de eventos offline (PDV e Gateway) à nuvem —
  classificação de falhas, retentativa sem abandono, visibilidade de falhas e pendências.
- `pdv/caixa-offline`: abertura, movimentação e fechamento de caixa feitos offline e reconciliados
  depois na sessão correta, com ordem da cadeia e tratamento de recusas.

### Modified Capabilities
<!-- nenhuma: a contingência fiscal (fiscal/contingencia-offline-nfce) não muda de requisito -->

## Impact

- **API** (`API/NETCORE`): `Models/Requests/CaixaRequest.cs`, `Services/Caixa/HorusCaixaService.cs`
  (`AbrirAsync`, `FecharAsync`, `ComputeExpectedCashAsync`), repositório de caixa (`caixaAB`):
  buscar sessão por id, criar sessão com id/horário informados.
- **Gateway** (`GATEWAY/src/HorusGateway`): `BackoffPolicy`, `SqliteEventStore`
  (`GetDueForDispatchAsync`, `RecordDispatchFailureAsync`), `CloudSyncDispatcher`,
  `CloudEndpointSyncClient` (repassar novos campos), `DashboardController`. Exige regenerar
  `FRONTEND/public/gateway/quack-gateway-completo.zip` via `GATEWAY/build-installer.sh`.
- **PDV** (`FRONTEND/src`): `SyncEngine.ts`, `CashSessionRepository.ts` (payloads),
  `cashRegisterService.ts`, `SalesHistoryPage.tsx`, `OutboxStatusModal.tsx`, página de caixa e banner
  de conectividade.
- **Dados existentes**: eventos já `FAILED` por erro de rede passam a ser reenfileirados
  automaticamente por migração no Gateway.
