# Validacao — autorizar-fiado-compartilhado-gateway

Data: 2026-10-21

## 7.1 Concorrencia real SQLite (Gateway)

**Arquivo**: `GATEWAY/tests/HorusGateway.Tests/CreditValidationTests.cs` — classe `CreditConcurrencyTests`

| Teste | Resultado |
|-------|-----------|
| TwoReserves70In100OnlyOneSucceeds | PASS |
| TwoTerminalsConcurrentReserveAndCommitOneGrant | PASS |
| ConcurrentCommitAndReserveFromDifferentTerminals | PASS |
| FourConcurrentReservesOnSmallBudgetNeverOverspend | PASS |

Invariante: `SUM(AmountCents) WHERE State IN ('RESERVED','COMMITTED') <= grant.AmountCents` verificada apos cada interleaving.

## 7.1 Concorrencia real SQL Server (API)

**Arquivo**: `API/tests/GatewayCreditChecks/Program.cs`

| Teste | Resultado |
|-------|-----------|
| two stores race for one customer credit | PASS |
| real concurrent grant60 versus cloud60 cannot spend over limit100 | PASS |

Invariante: `SaldoDevedor + delegado <= LimiteCredito` verificada.

## 7.2 Perda de resposta, reinicio, duplicatas e hash

**Arquivo**: `CreditValidationTests.cs` — classe `CreditIdempotencyTests`

| Teste | Resultado |
|-------|-----------|
| ReserveReplayReturnsSameResultAfterLostResponse | PASS |
| ReserveReplayWithDifferentAmountThrowsConflict | PASS |
| CommitReplayAfterRestartReturnsSameDecision | PASS |
| ConcurrentCommitRetryProducesExactlyOneEventAndOneDecision | PASS |
| CommitWithDifferentSaleHashThrowsConflict | PASS |
| CommittedAuthorizationCannotBeReReservedOrDoubleSpent | PASS |
| CancelAfterCommitPreservesCommittedState | PASS |
| PartialCommitFailureRollsBackEverything | PASS |
| QueryBySaleEventIdReturnsCorrectAuthorization | PASS |

Exigencia satisfeita: uma venda, um debito, um consumo apos qualquer numero de retries.

**API (SQL Server)**:

| Teste | Resultado |
|-------|-----------|
| lost grant response retries same allocation | PASS |
| operation hash conflict | PASS |
| one atomic sale debit consumption across retries | PASS |
| identity conflicts on changed payload | PASS |

## 7.3 Expiracao, cancel, politica, relogio

**Arquivo**: `CreditValidationTests.cs` — classe `CreditExpirationPolicyTests`

| Teste | Resultado |
|-------|-----------|
| ExpiredReservationCannotBeCommitted | PASS |
| ExpiredReservationFreesCredit | PASS |
| ExpiredGrantRejectsNewReservation | PASS |
| CancelConcurrentWithCommitProducesConsistentState | PASS |
| CancelBeforeCommitReleasesCredit | PASS |
| BlockedCustomerRejectsNewReservationButHonorsExistingCommit | PASS |
| DrainingCancelsReservationsAndClosesGrants | PASS |
| ClockRegressionBlocksAllCreditOperations | PASS |
| GrantReservationExpiryClampedToGrantExpiry | PASS |

## 7.4 Seguranca: spoofing, bypass, versoes

**Arquivo**: `CreditValidationTests.cs` — classe `CreditSecurityTests`

| Teste | Resultado |
|-------|-----------|
| ImportRejectsGrantWithWrongCompany | PASS |
| ImportRejectsGrantWithWrongStore | PASS |
| ImportRejectsGrantWithWrongGateway | PASS |
| ImportRejectsGrantWithWrongEpoch | PASS |
| ImportRejectsGrantWithZeroAmount | PASS |
| ImportRejectsGrantWithWrongVersion | PASS |
| DuplicateGrantImportWithDifferentContentThrowsConflict | PASS |
| DuplicateGrantImportWithSameContentIsIdempotent | PASS |
| ReserveWithForgedPermitIsRejected | PASS |
| ReserveWithExpiredPermitIsRejected | PASS |
| QueryFromWrongTerminalReturnsNull | PASS |
| CommitWithWrongCompanyInSaleRejected | PASS |
| CommitWithWrongStoreInSaleRejected | PASS |
| CommitWithWrongTerminalInSaleRejected | PASS |
| CommitNonSaleEventTypeRejected | PASS |
| CommitWithMismatchedCustomerCpfRejected | PASS |
| CommitWithMismatchedOperatorRejected | PASS |
| CommitWithMismatchedFiadoAmountRejected | PASS |
| GenericEventIngestCannotBypassCreditAuthorization | PASS |
| UnauthenticatedCreditEndpointsReturnUnauthorized | PASS |
| CreditDisabledByConfigRejectsAllOperations | PASS |
| OpenRegistrationModeBlocksCreditOperations | PASS |
| AuditRecordsAllStateTransitions | PASS |
| ReserveInvalidVersionIsRejected | PASS |
| ReserveWithZeroAmountIsRejected | PASS |
| ReserveWithNegativeAmountIsRejected | PASS |

Nenhum consumo ou vazamento de saldo em nenhum caso de spoofing.

## 7.5 Smoke Windows com dois terminais

**Status**: PENDENTE — requer dois computadores reais na LAN da loja.

Cobertura parcial automatizada:
- Bloqueio de instancia unica: `CreditInstallationGuardTests.WindowsDpapiWitnessRejectsRestoredLedgerAndSecondInstance` (PASS)
- Restart normal vs backup: mesmo teste valida restart OK + regressao de revision (PASS)
- Continuidade de outras formas de pagamento: `CreditExpirationPolicyTests` e `CreditSecurityTests` verificam que credit disabled/draining nao impede operacao legada

Cenarios que ainda exigem ambiente fisico:
- Queda seletiva de internet entre caixa e Gateway
- Fechamento da UI principal com crédito pendente
- Recuperacao de backup antigo em maquina diferente (DPAPI com LocalMachine scope)

## 7.6 Suites existentes — resultados consolidados

| Suite | Comando | Resultado |
|-------|---------|-----------|
| Gateway (xUnit) | `dotnet test GATEWAY/tests/HorusGateway.Tests/` | **175 passed**, 0 failed |
| API SaleIntegrityChecks | `dotnet run --project API/tests/SaleIntegrityChecks/` | **64 checks passed** |
| API GatewayCreditChecks | `dotnet run --project API/tests/GatewayCreditChecks/` | **45 checks passed** |
| Frontend TypeScript | `npx tsc --noEmit` | **0 errors** |
| Frontend Integrity (playwright) | `npx playwright test --config=playwright.integrity.config.ts` | **23 passed** |
| Frontend Credit Regression (playwright) | `npx playwright test --config=playwright.credit-regression.config.ts` | **179 passed** (16 did not run) |
| Frontend Architecture (playwright) | `npx playwright test --config=playwright.arch.config.ts` | **176 passed** (16 did not run) |

Total: **662 verificacoes automatizadas**, 0 falhas.

Os 16 "did not run" em credit-regression e arch sao testes filtrados por tag/condição, nao falhas.

## 7.7 graphify update e openspec validate

```
$ graphify update .
[graphify watch] Rebuilt: 7402 nodes, 14153 edges, 650 communities
graph.json and GRAPH_REPORT.md updated

$ openspec validate autorizar-fiado-compartilhado-gateway --strict
Change 'autorizar-fiado-compartilhado-gateway' is valid
```

## Limitacoes operacionais remanescentes

1. **Smoke com dois terminais fisicos (7.5)** — nao executado; requer LAN real da loja.
2. **DPAPI LocalMachine** — o witness so funciona em Windows; em Linux/Mac o guard bloqueia preventivamente.
3. **Recebimentos nao recarregam verba local** — por design; reconciliacao exige checkpoint manual.
4. **Feature desabilitada por padrao** — nenhuma loja operara com credito compartilhado ate ativacao explicita via /api/gateway-credit/store com inventario e orçamento.
