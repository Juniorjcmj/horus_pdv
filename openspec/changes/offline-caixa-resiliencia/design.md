## Context

Ver proposal.md (Why). Estado atual relevante, confirmado no código:

- **Gateway**: `Services/BackoffPolicy.cs` tem `MaxRetries = 10` e `Exhausted()`.
  `SqliteEventStore.RecordDispatchFailureAsync` marca `FAILED` quando esgota; `GetDueForDispatchAsync`
  filtra `RetryCount < $maxRetries` e ordena por `Seq`. `CloudEndpointSyncClient` já classifica
  Transient (rede, timeout, 408, 429, 5xx, 401, 403) × Permanent (409, demais 4xx);
  `CloudSyncDispatcher.RunOnceAsync` chama `RecordDispatchFailureAsync` (Transient) ou
  `MarkFailedAsync` (Permanent). `DashboardController` calcula `isSafe = PendingCloudEvents == 0`
  (linhas ~89 e ~151), ignorando `FailedEvents`.
- **PDV**: `FRONTEND/src/infrastructure/synchronization/SyncEngine.ts` tem `MAX_RETRIES = 10` e pula
  eventos com `retryCount >= MAX_RETRIES` (linha ~231). `dispatchEvent` envia `CASH_OPEN`/`CASH_CLOSE`
  só com valores (descarta `clientSessionId`/`closedAt` que já existem no payload gerado por
  `CashSessionRepository.closeCashLocal`).
- **API**: `HorusCaixaService.AbrirAsync` gera `sessionId = cx-{agora}` e usa `HorusDateTime.Now`.
  `FecharAsync` (linhas 157-186) pega `ObterSessaoAbertaAsync(companyId, operatorId)` e calcula o
  esperado com `now`. `ComputeExpectedCashAsync(companyId, session, now)` já filtra vendas por
  `session.Id` e período `OpenedAt..now` — basta passar a sessão e o horário certos.
  `caixaAB.ObterSessaoPorIdAsync` já existe.
- **Evidência do incidente**: fechamentos de dias diferentes recusados contra o mesmo esperado
  (R$ 419,01 / R$ 435,37) = o caixa aberto *hoje* de cada operador.

## Goals / Non-Goals

**Goals:** nenhum evento perdido por falha transitória; fechamento offline nunca afeta outra sessão;
falhas sempre visíveis; recuperação sem SQL manual.

**Non-Goals:** resolver automaticamente diferenças de caixa (justificativa é sempre humana); mudar a
contingência fiscal (já corrigida em `b94abbf`); reprocessar os eventos de caixa do incidente de
19/10 (serão fechados pela web pelo gerente); fila multi-terminal com ordenação global.

## Decisions

### D1. Sem limite de tentativas para falha transitória
Remover `MaxRetries`/`Exhausted` do Gateway e `MAX_RETRIES` do PDV no caminho transitório. Backoff
continua `BaseFor(retryCount)` com `ExponentCap = 6` (~5 min) + jitter 30%; o `RetryCount` segue
crescendo só para diagnóstico. `GetDueForDispatchAsync` deixa de filtrar por `RetryCount`.
*Alternativa*: aumentar para 1000 tentativas — rejeitada, só adia o mesmo defeito.

### D2. Migração de eventos já abandonados
No startup do Gateway (onde `GatewayDatabase` aplica schema), executar uma vez por inicialização:
`UPDATE GatewayEvents SET Status='PENDING_CLOUD', NextAttemptAt=NULL WHERE Status='FAILED' AND
(LastError IN ('rede indisponível','timeout') OR LastError LIKE 'HTTP 5%' OR LastError LIKE 'HTTP 408%'
OR LastError LIKE 'HTTP 429%' OR LastError LIKE 'Nuvem recusou o acesso%')`. Idempotente e seguro: a
nuvem deduplica por EventId. No PDV, eventos locais `FAILED` com `retryCount >= 10` e erro sem status
4xx de negócio voltam a `PENDING` na inicialização do `SyncEngine`.
*Alternativa*: botão manual — rejeitado, exige que alguém perceba.

### D3. PDV classifica falhas como o Gateway
Extrair no `SyncEngine` uma função `isBusinessRejection(err)` que retorna true apenas para erro HTTP com
status 4xx exceto 401/403/408/429 (usar o status do erro do `apiClient`; se o erro não tiver status, é
transitório). Só nesse caso o evento vai para `FAILED`. Manter os tratamentos idempotentes existentes
("Já existe um caixa aberto" / "Não existe caixa aberto") **apenas para eventos sem `clientSessionId`**;
com `clientSessionId` a API resolve a idempotência (D5) e essas mensagens não devem ser engolidas.

### D4. Painel considera FAILED
`isSafe = PendingCloudEvents == 0 && FailedEvents == 0` nos dois pontos do `DashboardController`;
mensagem lista "N evento(s) com falha" e o HTML do dashboard ganha um badge de falhas e a lista
(Seq, tipo, motivo) dos últimos 20 `FAILED`. Adicionar `GET /api/gateway/events/failed` se ainda não
houver listagem; ação recomendada `RESOLVE_FAILED`.

### D5. Contrato de caixa com identidade e horário do cliente
`AbrirCaixaRequest` ganha `ClientSessionId?` e `OpenedAt?` (ISO 8601); `FecharCaixaRequest` ganha
`ClientSessionId?` e `ClosedAt?`.
- **Abrir com `ClientSessionId`**: validar formato (`^cx-[A-Za-z0-9-]{1,36}$` (a coluna Id é NVARCHAR(40))). Se a sessão já existir
  na empresa → replay idempotente (retorna status). Senão cria com esse Id e `OpenedAt` informado.
  O bloqueio "já possui caixa aberto" passa a valer só para abertura **sem** `ClientSessionId`. Com
  `ClientSessionId`, nunca recusar por haver outra sessão aberta: cada sessão será fechada pelo seu
  próprio evento.
- **Fechar com `ClientSessionId`**: `ObterSessaoPorIdAsync(companyId, clientSessionId)`; inexistente →
  `InvalidOperationException("Sessão de caixa {id} não encontrada na nuvem.")` (HTTP 400 → FAILED,
  visível). Já fechada → se `EventId` processado retorna o resultado registrado; senão recusa
  "Sessão já fechada". Esperado = `ComputeExpectedCashAsync(company, session, closedAt)`. Grava
  `ClosedAt = closedAt`. `EnsureResponsavelPeloCaixa(session, user)` continua valendo.
- **Horários**: aceitar `OpenedAt/ClosedAt` entre `agora - 30 dias` e `agora + 5 min`; fora disso usar
  `HorusDateTime.Now` e registrar aviso no log. `ClosedAt < session.OpenedAt` → recusa.
- **Sem os campos**: comportamento atual inalterado (clientes antigos/online).
- Repositório: adicionar parâmetro de Id/OpenedAt em `AbrirIdempotenteAsync` (ou nova sobrecarga) e
  `ClosedAt` no fechamento. Verificar a coluna Id da tabela de sessões (string) — sem migração de schema
  esperada; se precisar, criar migration no padrão do repositório.

### D6. PDV e Gateway repassam os novos campos
- PDV: `openCashLocal` já gera o id local; garantir que o payload de `CASH_OPEN` inclua
  `clientSessionId` e `openedAt`, e o de `CASH_CLOSE` inclua `clientSessionId` e `closedAt` (já
  inclui). `SyncEngine.dispatchEvent` e `cashRegisterService.open/close` passam esses campos.
  Abertura **online** deve continuar usando o id devolvido pela nuvem como `actualSessionId`.
- Vendas offline já carregam o `caixaSessaoId` local — com D5 elas passam a casar com a sessão.
- Gateway: `CloudEndpointSyncClient` monta o corpo de `CASH_OPEN`/`CASH_CLOSE` a partir do payload;
  incluir `clientSessionId`, `openedAt`/`closedAt` no corpo enviado.

### D7. Cadeia de caixa por terminal
- Gateway: em `GetDueForDispatchAsync`, excluir eventos `CASH_%` cujo `TerminalId` tenha um evento
  `CASH_%` `FAILED` com `Seq` menor (subquery `NOT EXISTS`). Vendas não são afetadas.
- PDV: no laço do `SyncEngine`, antes de enviar um `CASH_*`, pular se existir evento `CASH_*` local
  `FAILED` com `sequence` menor no mesmo `deviceId`.
*Alternativa*: bloquear tudo atrás de um evento falho — rejeitada, travaria vendas por uma diferença de
caixa.

### D8. Resolver recusa no PDV
`OutboxStatusModal` ganha seção "Fechamentos recusados" (eventos `CASH_CLOSE` `FAILED` do PDV e, se o
Gateway estiver configurado, os do Gateway via endpoint de D4). Para gerente/admin (`HorusRoles`
equivalente no front), campo "Justificativa" → atualiza `differenceReason` no payload, recalcula
`payloadHash` se o hash do evento de caixa incluir esse campo (conferir `computeCashMovementPayloadHash`
/ hash de fechamento), volta o evento a `PENDING` com `retryCount = 0`. Para eventos que estão no
Gateway: novo endpoint `POST /api/gateway/events/{seq}/resolve` com `{ differenceReason }` que atualiza
o payload e volta a `PENDING_CLOUD` (autenticado com a chave do terminal).

### D9. Aviso de modo offline
Reaproveitar o estado de conectividade já usado pelo PDV (hook de status online/outbox). Banner fixo na
tela de vendas e de caixa enquanto offline. No fechamento de caixa, se `countPending() > 0`, diálogo de
confirmação com a quantidade.

### D10. Histórico mescla vendas locais
`SalesHistoryPage.tsx` (~linha 160): após carregar da nuvem, buscar eventos `SALE_CREATED` locais com
status em `NOT_IN_CLOUD_STATUSES` no período filtrado, mapear para o formato da linha e incluir os que
não estiverem na lista da nuvem (comparar por `clientSaleId`), com badge "pendente de envio".

## Risks / Trade-offs

- [Evento com defeito real reenviado para sempre] → só transitórios repetem; backoff com teto de 5 min
  limita custo; painel mostra a fila.
- [Sessão aberta no passado com outra aberta hoje gera duas sessões abertas do mesmo operador] →
  `BuildStatusAsync` escolhe a sessão aberta do operador; garantir que escolha a **mais recente**
  (`OrderByDescending(OpenedAt)`), e a antiga é fechada pelo seu próprio evento.
- [Horário do cliente com relógio errado] → janela de aceitação (D5) e log.
- [Migração D2 reenvia evento já aceito] → nuvem é idempotente por EventId (comprovado no incidente).
- [Mudança no Gateway exige reinstalação nos caixas] → regenerar instalador; PDV e API funcionam com
  Gateway antigo (campos novos são opcionais).

## Migration Plan

1. Publicar a API (campos opcionais; compatível com clientes antigos).
2. Publicar o front (passa a enviar os campos; mesclagem e avisos).
3. Regenerar `quack-gateway-completo.zip` e atualizar o Gateway dos caixas; na primeira inicialização a
   migração D2 reenfileira eventos presos.
Rollback: reverter front/Gateway; a API ignora a ausência dos campos.

## Open Questions

- Os 6 eventos de caixa do incidente de 19/10 continuam `FAILED` e foram gerados sem `clientSessionId`
  no corpo enviado; serão fechados pelo gerente na web, fora desta mudança.
