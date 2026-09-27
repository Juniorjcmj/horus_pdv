# Design: Local Gateway — Offline-first por Loja

## Análise do repositório atual (estado antes desta proposta)

Levantamento read-only feito para garantir que o Gateway se encaixe sem quebrar o que existe.

### Frontend (PWA) — sincronização offline

| Peça | Arquivo | Papel |
|------|---------|-------|
| Outbox (fila durável) | `src/infrastructure/database/repositories/OutboxRepository.ts` | Eventos `PENDING/PROCESSING/PROCESSED/FAILED` em IndexedDB (Dexie) |
| Tipos de sync | `src/shared/types/sync.ts` | `OutboxEvent` já tem `id`, `deviceId`, `tenantId`, `storeId`, `eventType`, `aggregateType/Id`, `clientSaleId`, `payloadHash`, `payload`, `sequence`, `status`, `retryCount` |
| Push | `src/infrastructure/synchronization/SyncEngine.ts` | Processa Outbox → API, backoff exponencial (`MAX_RETRIES=10`, base 5s), storage lock em `localStorage`, dedup por `processedEvents` |
| Pull + orquestração | `src/infrastructure/synchronization/SyncCoordinator.ts` | Pull produtos → clientes → caixa; checkpoints e logs; intervalo mínimo 2 min |
| Conectividade | `src/infrastructure/synchronization/ConnectivityService.ts` | Status real (`ONLINE/OFFLINE/API_UNAVAILABLE/SYNCING`) via health-check em `VITE_AUTH_API_URL/me` a cada 30s |
| Hash canônico | `src/utils/cryptoHash.ts` | Cálculo de `PayloadHash` |
| Adapters | `application/sales/SaleOutboxAdapter.ts`, `application/products/ProductSyncAdapter.ts`, `application/customers/CustomerSyncAdapter.ts` | Traduzem entidades ↔ eventos |
| Cliente HTTP | `src/services/api/apiClient.ts` | `fetch(endpointUrl, { credentials: "include" })` para `VITE_*_API_URL` — **ponto único de dependência direta da cloud** |

### Backend (ASP.NET Core) — idempotência, auth, pedidos

| Peça | Arquivo | Papel |
|------|---------|-------|
| Idempotência | `DataBase/Migrations/19_idempotencia_eventos.sql` → tabela `ProcessedEvents (Id, CompanyId, EventId, PayloadHash, ProcessedAt)` | Dedup transacional server-side |
| Hash server | `Services/Shared/HorusPayloadHash.cs`, `Services/Shared/IdempotencyConflictException.cs` | Recalcula/valida hash; conflito → 409 |
| Uso | `Repositories/DatabaseAccess/HistoricoVendasAB.cs`, `CaixaAB.cs`, `Services/Caixa/HorusCaixaService.cs` | Vendas e caixa já idempotentes |
| Contrato de venda | `Models/Requests/VendaRequest.cs` | **Já carrega** `ClientSaleId`, `EventId`, `EventType`, `OfflineReference`, `OccurredAt`, `PayloadHash` |
| Auth | `Middlewares/HorusAuthMiddleware.cs` + `HorusJwtService` | JWT via cookie (`HorusJwtService.AuthCookieName`) ou `Bearer`; valida usuário ativo, `CompanyId` e status da empresa (migração 14) |
| Pedidos | `Controllers/Pedidos/PedidoController.cs`, `Models/Pedidos/PedidoModel.cs` | `POST /api/Pedido`, `GET`, `GET/{n}`, `POST/{n}/finalizar`, `POST/{n}/cancelar`; status `aberto/finalizado/cancelado` |

**Conclusões que orientam o design:**
- O modelo de idempotência (`EventId` + `PayloadHash` + `ProcessedEvents`) já é ponta-a-ponta e **deve ser reutilizado** pelo Gateway, não reinventado.
- A **Outbox já tem os campos** necessários para roteamento (`deviceId`, `storeId`, `tenantId`) — falta apenas a noção de **destino** (`GATEWAY` vs `CLOUD`).
- `apiClient.ts` é o **único lugar** onde o frontend fala com a cloud → é o ponto natural para inserir a decisão de roteamento (Gateway → Cloud → Local).
- **Não existe SignalR/WebSocket** hoje (nem no `.csproj` nem no `package.json`) → tempo-real é infra nova, isolada no Gateway.
- Pedido hoje só tem 3 estados; o fluxo operacional alvo pede uma máquina de estados mais rica (eventos `ORDER_*`).

## Architecture Overview

Três camadas; o Gateway é intermediário **opcional**.

```
                    INTERNET
                       │
              ┌────────▼────────┐
              │   HÓRUS CLOUD   │  ASP.NET Core + SQL Server + Fiscal + APIs
              └────────┬────────┘
                       │  Cloud Sync (CLOUD_SYNC)
══════════════════════╪══════════════════════ REDE LAN
              ┌────────▼────────┐
              │ LOCAL GATEWAY   │  ASP.NET Core + SQLite
              │ Local Store · Event Bus · Sync Engine · Discovery · SignalR │
              └───────┬─────────┘
                      │  LAN (LOCAL_DELIVERY: REST + SignalR)
        ┌─────────────┼─────────────┬─────────────┐
        ▼             ▼             ▼             ▼
   TERMINAL 1    TERMINAL 2    TERMINAL 3    TERMINAL 4 ── CAIXA
   (IndexedDB+Outbox em cada um; Gateway é a 2ª camada de transporte)
```

O PC do caixa hospeda o Gateway por padrão; a arquitetura permite movê-lo para outro PC da loja depois. **Regra de ouro:** o fluxo é sempre `Internet ↓ Gateway ↓ Terminal`, nunca `Terminal ↓ Gateway ↓ Terminal ↓ Gateway ↓ Terminal` (sem dependência circular). Cada terminal mantém IndexedDB, Outbox, `EventId`, `PayloadHash`, `SyncEngine` e estado offline próprios.

## Modos de operação (diagramas de sequência)

### MODO ONLINE
```
Terminal → Gateway → Cloud        (Gateway coordena e encaminha)
Terminal → Cloud                  (direto, quando apropriado por operação)
```

### MODO LAN-OFFLINE (internet fora)
```
Terminal → Gateway → Local Store → (SignalR) → Caixa
```
Nenhum terminal depende da internet para falar com outro. O Gateway retém os eventos localmente (`PENDING_CLOUD`).

### MODO ISOLADO (internet + Gateway fora)
```
Terminal → IndexedDB / Outbox     (comportamento ATUAL do Hórus, intacto)
```
Fundamental: o Gateway é camada adicional, não dependência do offline já existente.

### Fluxo de pedido (feliz, online)
```
Terminal-1 cria Pedido #1001
  → grava IndexedDB + Outbox (EventId=EVT-123, PayloadHash=ABC)
  → POST evento ORDER_CREATED ao Gateway
Gateway: recebe → valida CompanyId → valida TerminalId → valida PayloadHash
         → persiste (commit) → ACK 200
Terminal marca DELIVERED_LOCAL
Gateway → SignalR → Caixa (novo pedido aparece em tempo real)
Gateway → Cloud (CLOUD_SYNC) → ProcessedEvents → SQL Server
```

### Recuperação (Gateway reinicia / queda de energia)
```
Gateway boot → abre SQLite → carrega eventos PENDING → valida integridade → retoma CLOUD_SYNC
Terminais boot → abrem IndexedDB → recuperam Outbox → reconectam ao Gateway → reenviam pendentes
```

## Entidades necessárias (Gateway — SQLite local)

Somente o necessário para operação local; **não** replicar o SQL Server. Cada tabela é criada só se for usada pela feature correspondente.

| Tabela | Campos principais | Origem/Change |
|--------|-------------------|---------------|
| `Terminals` | `TerminalId`, `CompanyId`, `StoreId`, `TerminalType (ORDER\|CASH)`, `CredentialHash`, `LastSeenAt`, `Status` | GATEWAY 03 |
| `GatewayEvents` | `EventId`, `CompanyId`, `StoreId`, `TerminalId`, `EventType`, `OccurredAt`, `Payload`, `PayloadHash`, `Status`, `CreatedAt`, `ProcessedAt` | GATEWAY 04 |
| `ProcessedEvents` | `EventId`, `CompanyId`, `PayloadHash`, `ProcessedAt` (espelha o modelo cloud) | GATEWAY 04 |
| `Orders` | `OrderNumber`, `CompanyId`, `StoreId`, `TerminalId`, `Status`, `TotalAmount`, itens, timestamps | GATEWAY 05 |
| `Payments` | `PaymentId`, `EventId`, `TxId`, `Status`, `Method`, `Amount` | GATEWAY 05/06 |
| `SyncState` | por evento/destino: `PENDING_LOCAL`, `DELIVERED_LOCAL`, `PENDING_CLOUD`, `SYNCED_CLOUD`, `FAILED` | GATEWAY 06 |
| `CatalogCache` | `entity`, `version`, `updatedAt`, `source`, payload (produtos/preços/config) | GATEWAY 05 (opcional) |

Identidade: `GatewayId` (`gw_...`), `CompanyId`, `StoreId`; cada terminal: `TerminalId`, `CompanyId`, `StoreId`, `TerminalType`.

## Endpoints necessários (Gateway REST + SignalR)

REST (`http[s]://<gateway-host>:5080`):
- `POST /api/gateway/register` — registro/autorização de terminal (retorna credencial local).
- `POST /api/gateway/heartbeat` — heartbeat a cada 10–30s; atualiza `LastSeenAt`.
- `POST /api/gateway/events` — recebe evento (EventId + PayloadHash), valida, persiste, ACK.
- `GET  /api/gateway/orders` / `GET /api/gateway/orders/{n}` — consulta de pedidos (caixa).
- `POST /api/gateway/orders/{n}/status` — transição de estado (gera evento).
- `GET  /health`, `/health/live`, `/health/ready` — status do Gateway/storage/cloud/pendências.

SignalR/WebSocket (tempo-real Gateway → clientes): `order.created`, `order.updated`, `payment.confirmed`, `order.cancelled`, `status.changed`.

## Estratégias

### Autenticação (LAN não confiável)
- Terminal → `register` → Gateway valida `CompanyId` → emite credencial local (API Key + Terminal Secret, ou JWT local curto). Armazenada com segurança no terminal.
- O Gateway autentica **"este terminal autorizado pertence a esta loja?"**, nunca **"este usuário tem senha X?"** — autenticação de usuário permanece na cloud (`AuthController`), sem duplicação.
- Toda requisição valida o vínculo `Terminal → Store → Gateway → Company`. Nunca aceitar `CompanyId` diferente.

### Descoberta do Gateway (3 níveis)
1. hostname/mDNS (`horus-gateway.local`); 2. URL previamente configurada; 3. configuração manual pelo admin. Sem IP hardcoded no frontend; suportar IP fixo, DHCP reservation, hostname e descoberta automática. Config salva localmente no terminal.

### Persistência e recuperação
- SQLite no Gateway; nenhum evento só em RAM. ACK só **após** commit (`recebe → valida → persiste → commit → ACK`). Após reboot/energia: recarrega `PENDING`, valida integridade, retoma. Terminais recuperam Outbox do IndexedDB e reconectam.

### Sincronização (reuso, não duplicação)
- **Generalizar a Outbox atual** com `Destination = GATEWAY | CLOUD` (ou abstração equivalente) — **sem** criar `SyncEngine2`. Duas etapas independentes: `LOCAL_DELIVERY` (Terminal → Gateway) e `CLOUD_SYNC` (Gateway → Cloud). "Entregue ao Gateway" ≠ "Sincronizado com a Cloud".
- Retry: falhas temporárias (`5xx`, timeout, reset) → retry + backoff exponencial (reusar regras do `SyncEngine`). Erros permanentes (`400/401/403/409 hash mismatch`) tratados por tipo.
- Catálogo: sincronização por delta (`version`), evitando download completo.

### Idempotência ponta-a-ponta
- `EventId` **nasce no terminal** e não muda ao atravessar Gateway → Cloud. `PayloadHash` sobre payload canônico: mesmo `EventId` + mesmo hash → replay aceito; mesmo `EventId` + hash diferente → **409 Conflict** (sem alteração silenciosa). `ProcessedEvents` no Gateway e na cloud.

### Concorrência e multi-tenant
- N terminais + caixa simultâneos; locks por recurso/evento, **nunca** lock global da loja. Gateway estritamente vinculado a uma empresa; toda requisição valida `CompanyId`.

### Segurança de transporte
- Preferir HTTPS mesmo na LAN. Se a v1 usar HTTP em rede privada, é decisão explícita de implantação e credenciais **não** trafegam em texto puro.

## Risks and Mitigations

| Risco | Mitigação |
|-------|-----------|
| Gateway virar dependência do offline | Modo ISOLADO obrigatório; fallback Gateway→Cloud→Local; testes de aceitação cobrindo Gateway fora |
| Duplicar `SyncEngine`/idempotência | Generalizar Outbox (`Destination`); reusar `EventId`/`PayloadHash`/`ProcessedEvents` |
| Quebrar os 71 testes homologados | Não tocar nos mecanismos preservados; rodar `tests/architecture/*` a cada Change GATEWAY |
| "Segunda verdade" de estoque/vendas | Gateway só coordena; cloud é autoridade; estoque negativo aceito como no modelo atual |
| Confundir Pix exibido com pago | Confirmação só via PSP/webhook na cloud |
| Perda de evento por RAM-only | SQLite + ACK pós-commit + recuperação no boot |
| LAN tratada como confiável | Registro/credencial por terminal + validação de `CompanyId` em toda request |

## Impacto nos Changes 01–07.3 (71 testes homologados)

Preservar sem regressão: `CompanyId`, `EventId`, `PayloadHash`, `ProcessedEvents`, Outbox, `SyncEngine`, `SyncCoordinator`, Web Locks, Storage Lease, IndexedDB, idempotência, precisão monetária, PWA, autenticação offline, isolamento multi-tenant. Suíte a manter verde: `FRONTEND/tests/architecture/` (`bloco1-outbox`, `bloco2-3-idempotency`, `bloco4-cash-atomicity`, `bloco5-6-sale-stock`, `bloco8-crypto-hash`, `bloco10-pwa`, `bloco12-security-production`). O Gateway entra como camada aditiva; nenhuma feature GATEWAY deve alterar contratos existentes de venda/caixa.

## Arquivos que seriam alterados (nas implementações futuras, NÃO nesta change)

- **Novo projeto** `HorusGateway/` (ASP.NET Core + SQLite + SignalR; Windows Service).
- **Frontend:** `services/api/apiClient.ts` (roteamento Gateway→Cloud→Local), `shared/types/sync.ts` (campo `destination`), `OutboxRepository.ts`/`SyncEngine.ts`/`SyncCoordinator.ts` (generalização por destino), `ConnectivityService.ts` (status do Gateway), novo `GatewayDiscoveryService` + config de terminal, hooks de heartbeat.
- **Cloud (opcional):** endpoint de recepção em lote do Gateway (reusa idempotência existente); nada removido.

## Migrações necessárias

- **Cloud SQL Server:** possivelmente **nenhuma** — `ProcessedEvents` e `VendaRequest` (EventId/PayloadHash) já existem. Um endpoint de ingestão em lote reusa o que há.
- **Gateway SQLite:** schema local próprio (`Terminals`, `GatewayEvents`, `ProcessedEvents`, `Orders`, `Payments`, `SyncState`, `CatalogCache`), criado incrementalmente por Change. Não é migração do banco cloud.

## Plano de implementação incremental

Detalhado em `tasks.md` (Changes GATEWAY 01–08). Ordem: 01 discovery → 02 projeto/infra → 03 registro+heartbeat+descoberta+auth → 04 event bus local → 05 pedidos tempo-real → 06 sync Gateway→Cloud → 07 integração com SyncEngine (fallback) → 08 dashboard de monitoramento.
