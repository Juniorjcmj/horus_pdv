## 1. API — contrato de caixa com sessão e horário do cliente (design D5)

- [x] 1.1 Adicionar `ClientSessionId?` e `OpenedAt?` em `AbrirCaixaRequest`, e `ClientSessionId?` e `ClosedAt?` em `FecharCaixaRequest` (`API/NETCORE/Models/Requests/CaixaRequest.cs`)
- [x] 1.2 Criar helper de validação: formato `^cx-[A-Za-z0-9-]{1,36}$` (a coluna Id é NVARCHAR(40)) e janela de horário (agora−30 dias … agora+5 min; fora da janela usa `HorusDateTime.Now` e loga aviso)
- [x] 1.3 `AbrirAsync`: com `ClientSessionId`, replay idempotente se a sessão já existir; senão criar com o id e `OpenedAt` informados, sem recusar por outra sessão aberta. Sem o campo, comportamento atual
- [x] 1.4 Ajustar o repositório de caixa (`caixaAB.AbrirIdempotenteAsync` ou sobrecarga) para aceitar id e horário de abertura; conferir o tipo da coluna Id (migration só se necessário)
- [x] 1.5 `FecharAsync`: com `ClientSessionId`, usar `ObterSessaoPorIdAsync`; inexistente → erro "Sessão de caixa {id} não encontrada na nuvem."; já fechada → replay por EventId ou erro "Sessão já fechada"; `ClosedAt < OpenedAt` → erro; esperado via `ComputeExpectedCashAsync(company, session, closedAt)`; gravar `ClosedAt` informado. Sem o campo, comportamento atual
- [x] 1.6 `BuildStatusAsync`: escolher a sessão aberta mais recente do operador (`OrderByDescending(OpenedAt)`)
- [x] 1.7 Testes da API: fechamento antigo com outra sessão aberta hoje fecha só a indicada; sessão inexistente não fecha nada; replay idempotente; abertura no passado com sessão aberta existente; cliente sem campos mantém comportamento

## 2. Gateway — retentativa sem abandono e cadeia de caixa (D1, D2, D6, D7)

- [x] 2.1 `BackoffPolicy`: remover `MaxRetries`/`Exhausted`; manter `BaseFor`/`NextDelay` com teto de ~5 min
- [x] 2.2 `SqliteEventStore.RecordDispatchFailureAsync`: nunca marcar `FAILED`; sempre reagendar
- [x] 2.3 `SqliteEventStore.GetDueForDispatchAsync`: remover filtro `RetryCount < $maxRetries`; excluir eventos `CASH_%` com evento `CASH_%` `FAILED` de `Seq` menor no mesmo `TerminalId`
- [x] 2.4 Migração no startup: reenfileirar `FAILED` com motivo transitório (lista em D2)
- [x] 2.5 `CloudEndpointSyncClient`: incluir `clientSessionId`, `openedAt`/`closedAt` no corpo de `CASH_OPEN`/`CASH_CLOSE`
- [x] 2.6 Atualizar/criar testes xUnit em `GATEWAY/tests/HorusGateway.Tests`: 50 falhas de rede seguidas mantêm `PENDING_CLOUD`; 400 vira `FAILED`; migração reenfileira só transitórios; `CASH_OPEN` retido atrás de `CASH_CLOSE` `FAILED` do mesmo terminal e venda não retida; corpo do fechamento contém `clientSessionId`

## 3. Gateway — painel e resolução de recusas (D4, D8)

- [x] 3.1 `DashboardController`: `isSafe` exige `FailedEvents == 0` nos dois pontos; mensagem e ação `RESOLVE_FAILED`
- [x] 3.2 Endpoint de listagem dos `FAILED` (Seq, EventType, OccurredAt, LastError) e badge/lista no HTML do dashboard
- [x] 3.3 Endpoint `POST /api/gateway/events/{seq}/resolve` com `{ differenceReason }` para `CASH_CLOSE` `FAILED`: atualiza payload e volta a `PENDING_CLOUD` (autenticado pela chave do terminal)
- [x] 3.4 Testes: painel não seguro com só `FAILED`; resolve atualiza payload e reenfileira; resolve recusa evento que não é `CASH_CLOSE` `FAILED`

## 4. PDV — sincronização (D1, D2, D3, D6, D7)

- [x] 4.1 `SyncEngine.ts`: remover pulo por `MAX_RETRIES`; manter backoff com teto; criar `isBusinessRejection(err)` (4xx exceto 401/403/408/429) e só marcar `FAILED` nesse caso
- [x] 4.2 Recuperação na inicialização do `SyncEngine`: eventos `FAILED` com erro transitório voltam a `PENDING`
- [x] 4.3 Cadeia: pular `CASH_*` quando houver `CASH_*` `FAILED` de `sequence` menor no mesmo `deviceId`
- [x] 4.4 Payload de `CASH_OPEN` local com `clientSessionId` e `openedAt`; `dispatchEvent` e `cashRegisterService.open/close` enviam `clientSessionId`, `openedAt`/`closedAt`; manter os tratamentos "Já existe caixa aberto"/"Não existe caixa aberto" só para eventos sem `clientSessionId`
- [x] 4.5 Testes Playwright (architecture): falha de rede repetida não abandona; 400 marca `FAILED`; `CASH_OPEN` retido atrás de `CASH_CLOSE` falho e venda enviada

## 5. PDV — visibilidade para operador e gerente (D8, D9, D10)

- [x] 5.1 `OutboxStatusModal`: seção "Fechamentos recusados" (locais e do Gateway), com motivo; para gerente/admin, campo de justificativa que atualiza o payload (recalculando hash se aplicável) e reenfileira — local direto, Gateway via endpoint 3.3
- [x] 5.2 Banner fixo de modo offline nas telas de venda e caixa: "Sem internet — pode continuar vendendo. As vendas estão guardadas neste caixa." (tokens do design system, prefixo `rg-` se novo componente)
- [x] 5.3 Confirmação ao fechar caixa com pendências, informando a quantidade
- [x] 5.4 `SalesHistoryPage.tsx`: mesclar vendas locais não sincronizadas (`NOT_IN_CLOUD_STATUSES`) do período, sem duplicar por `clientSaleId`, com badge "pendente de envio"
- [x] 5.5 Testes: histórico mescla sem duplicar; banner aparece offline

## 6. Entrega

- [x] 6.1 `npx tsc -b` no FRONTEND, `dotnet test` no Gateway e testes da API passando
- [x] 6.2 Regenerar `FRONTEND/public/gateway/quack-gateway-completo.zip` com `GATEWAY/build-installer.sh` e incluir no commit
- [x] 6.3 Rodar `graphify update .`
