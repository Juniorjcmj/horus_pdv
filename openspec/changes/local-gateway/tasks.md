# Tasks: Local Gateway — Plano de Implementação Incremental

> **Regra absoluta desta change:** esta é a fase de análise/proposta (CHANGE GATEWAY 01). Nenhuma tarefa abaixo, exceto a 1.x, deve ser executada agora. As tarefas 2.x–9.x são o plano das changes futuras e serão implementadas em changes próprias, cada uma rodando `FRONTEND/tests/architecture/*` para garantir que os 71 testes homologados continuem verdes.

## CHANGE GATEWAY 01 — Discovery arquitetural (esta change)

- [x] Mapear Outbox, `SyncEngine`, `SyncCoordinator`, `ConnectivityService` (frontend)
- [x] Mapear idempotência backend (`ProcessedEvents`, `HorusPayloadHash`, `VendaRequest`)
- [x] Mapear autenticação (`HorusAuthMiddleware`, JWT, `CompanyId`, status da empresa)
- [x] Mapear entidades e endpoints de pedidos/vendas/caixa
- [x] Mapear ponto único de dependência da cloud (`apiClient.ts`) e detecção online/offline
- [x] Confirmar ausência de SignalR/WebSocket hoje
- [x] Produzir proposta, design, spec e este plano — **sem alterar código, banco, API ou testes**
- [ ] Aprovação do usuário para iniciar CHANGE GATEWAY 02

## CHANGE GATEWAY 02 — Projeto e infraestrutura básica (concluído)

- [x] Criar projeto `HorusGateway` (ASP.NET Core 8) em `GATEWAY/src/HorusGateway`
- [x] Configuração por ambiente e identificação: `GatewayId`, `CompanyId`, `StoreId` (seção `Gateway`)
- [x] Armazenamento local (SQLite via `Microsoft.Data.Sqlite`) e schema base idempotente
- [x] Health check (`/health`, `/health/live`, `/health/ready`) + `/api/gateway/status` (descoberta)
- [x] Event Bus local: ingestão `POST /api/gateway/events` (EventId + PayloadHash canônico, idempotência: replay/409) e recuperação `GET /api/gateway/events?after=` (cursor)
- [x] Estrutura de tempo real (SignalR Hub `/hubs/events`) — PDV → Gateway → outros terminais/caixa
- [x] Persistência durável e recuperação após reinicialização (SQLite; log de eventos recuperados no boot)
- [x] Isolamento multi-tenant: `CompanyId` na chave lógica e em toda consulta (403 para outra empresa)
- [x] Logs estruturados (JSON), Dockerfile, docker-compose e README
- [x] Testes do Gateway (9/9 passando) cobrindo os 10 cenários exigidos + entrega em tempo real
- [x] Regressão zero: nenhum arquivo existente alterado (SyncEngine/Outbox/PDV/caixa/NFC-e intactos)
- [ ] Execução como Windows Service / systemd — **adiado** (estrutura pronta; empacotamento como serviço fica para a etapa de implantação, junto de GATEWAY 03/08)

## CHANGE GATEWAY 03 — Registro de terminais, heartbeat, descoberta, auth (concluído)

- [x] Endpoint de registro/autorização de terminal `POST /api/gateway/register` (valida `CompanyId` + token compartilhado, emite `apiKey`; só o hash PBKDF2 é persistido)
- [x] Heartbeat `POST /api/gateway/heartbeat` (headers `X-Terminal-Id`/`X-Terminal-Key`) atualizando `LastSeenAt`; listagem `GET /api/gateway/terminals` com online/offline
- [x] Autenticação de terminal aplicada a ingestão/recuperação de eventos (`RequireTerminalAuth`, seguro por padrão); credencial ≠ terminalId do evento → 403 (anti-spoofing)
- [x] Validação do vínculo `Terminal → Store → Gateway → Company` em toda requisição protegida
- [x] `/api/gateway/status` expõe `registrationRequired`/`terminalAuthRequired` para descoberta de capacidade
- [x] Testes (18/18 no total: 9 do GATEWAY 02 + 9 do 03) e regressão zero (71/71 arch)
- [ ] Descoberta client-side em 3 níveis (mDNS/hostname → URL → manual) e **armazenamento seguro da credencial no PWA** — **adiado para o CHANGE GATEWAY 07** (integração com o terminal/frontend); o lado servidor (registro + `/status`) está pronto

## CHANGE GATEWAY 04 — Event Bus local (concluído)

- [x] Tabela `GatewayEvents` e ledger dedicado `ProcessedEvents` (idempotência espelhando a cloud)
- [x] `POST /api/gateway/events`: recebe → valida `CompanyId`/`TerminalId`/`PayloadHash` → persiste (evento + ledger, 1 transação) → commit → ACK
- [x] `EventId` preservado (nasce no terminal); dedup por `EventId`+`PayloadHash` via `ProcessedEvents`
- [x] `409 Conflict` para mesmo `EventId` com `PayloadHash` diferente
- [x] Retry + backoff exponencial reutilizando as regras do `SyncEngine` (`BackoffPolicy`: base 5s, ×2, teto ~5min, jitter 30%, máx. 10)
- [x] Ciclo de vida de reprocessamento: `RetryCount`/`LastAttemptAt`/`LastError`/`NextAttemptAt`, seleção de eventos devidos (`GetDueForDispatchAsync`), falha reagenda (`RecordDispatchFailureAsync`), esgotamento → `FAILED`, `MarkSyncedAsync` — base do dispatcher do GATEWAY 06
- [x] Relógio injetável (`IClock`) para agendamento de retry determinístico nos testes
- [x] Testes (31/31 no total) incluindo unitários de backoff e ciclo de vida do bus; regressão zero (71/71 arch)

## CHANGE GATEWAY 05 — Pedidos em tempo real (concluído)

- [x] Eventos `ORDER_CREATED/UPDATED/RECEIVED/CONFIRMED/PREPARING/READY/DELIVERED/CANCELLED`
- [x] Máquina de estados `CREATED → RECEIVED → CONFIRMED → PREPARING → READY → DELIVERED` (avanço pode pular etapas; cancelamento de qualquer estado não terminal); transição inválida → 409 sem gravar
- [x] Projeção `Orders` (read model) derivada dos eventos, versionada; nunca altera estado sem evento
- [x] Canal tempo-real (SignalR): `Terminal → Gateway → Caixa` via `orderUpdated` (além do `eventReceived`)
- [x] Caixa: visualizar (`GET /api/gateway/orders` e `/{orderNumber}`); aceitar/cancelar/finalizar via eventos `ORDER_*`
- [x] Idempotência preservada: `ORDER_CREATED` repetido (mesmo EventId) continua replay, não conflito de transição
- [x] Testes (44/44 no total) incl. máquina de estados unitária e fluxo end-to-end + tempo real; regressão zero (71/71 arch)
- [ ] Registrar pagamento (Pix/PSP) e cache de catálogo (delta) — **adiados** para GATEWAY 06 (pagamento depende do PSP)

## CHANGE GATEWAY 06 — Sincronização Gateway → Cloud (concluído)

- [x] Dispatcher (`CloudSyncDispatcher`) + serviço em background que consome os eventos devidos e envia à cloud em ordem de `Seq`
- [x] Estados `PENDING_CLOUD` → `SYNCED_CLOUD` (sucesso/conflito idempotente) / `FAILED` (permanente)
- [x] Retry com backoff em falha transitória; recuperação automática após reboot/energia (pendentes vivem no SQLite)
- [x] Idempotência ponta-a-ponta: `EventId` + `PayloadHash` preservados; 409 da cloud = replay = sincronizado
- [x] Transporte HTTP desacoplado (`ICloudSyncClient`/`HttpCloudSyncClient`); `CloudSyncUrl` vazio ⇒ LAN-only (eventos acumulam)
- [x] `/health` reflete `cloud` (`disabled`/`online`/`offline`) e `pendingEvents`; `CloudSyncState` observável
- [x] Reusa a idempotência da cloud (`ProcessedEvents`); Gateway não cria segunda verdade
- [x] Testes (50/50 no total) incl. dispatcher (sucesso, conflito, transitório→reagenda, permanente→FAILED, desabilitado, ordem); regressão zero (71/71 arch)
- [ ] Endpoint de ingestão em lote na cloud (API central) — **fora do escopo GATEWAY** (evita alterar a API/SQL principal); o Gateway já envia preservando idempotência quando a URL for configurada

## CHANGE GATEWAY 07 — Integração com o terminal (fallback offline) (concluído)

> Contrato definido pelo usuário: **o ponto de comunicação é sempre a Cloud**; o terminal só
> procura o Gateway **quando não há internet**, para os terminais falarem com o caixa. Fora disso,
> nada muda. Portanto o fluxo homologado de vendas/caixa → cloud (Outbox/SyncEngine/apiClient)
> permanece **intacto**; a camada do Gateway é aditiva.

- [x] Decisão no terminal (`chooseOrderDestination`): COM internet → Cloud; SEM internet + Gateway → Gateway; senão → Offline Local (Outbox atual)
- [x] Descoberta do Gateway na LAN (`gatewayDiscovery`): URL configurada/manual + verificação `/status` com validação de `CompanyId` (mDNS/hostname a cargo da config de rede)
- [x] Armazenamento seguro da credencial no PWA (`gatewayConfig` em localStorage, protegido) + registro/heartbeat/publicação/recuperação (`gatewayClient`)
- [x] Transporte de pedido offline (`publishOrderEventOffline`) preservando `EventId`+`PayloadHash`; se o Gateway cair, mantém na Outbox local (nada é perdido)
- [x] Camada 100% aditiva: nenhum arquivo homologado alterado; 71/71 testes de arquitetura verdes; typecheck OK
- [ ] Roteamento de vendas/caixa via Gateway e generalização da Outbox com `Destination` — **NÃO implementado por contrato** (vendas/caixa sempre falam com a Cloud; o Gateway não é rota dessas operações)

## CHANGE GATEWAY 08 — Interface de monitoramento (concluído)

- [x] Dashboard local: Internet, Gateway, Cloud, terminais, eventos pendentes/erro, última sync, último heartbeat (servido em `/` e `/dashboard`)
- [x] Indicadores em tempo real via heartbeat e health check (`/api/gateway/dashboard` consolidado e componente `GatewayMonitorCard`)
- [x] Mecanismo de atualização controlada do Gateway (`/api/gateway/system/update-check`: valida `pendingEvents == 0` antes de autorizar atualização/manutenção)

## Validação transversal (em toda change 02–08)

- [ ] Rodar `FRONTEND/tests/architecture/*` — os 71 testes homologados continuam verdes
- [ ] Verificar preservação de: `CompanyId`, `EventId`, `PayloadHash`, `ProcessedEvents`, Outbox, `SyncEngine`, `SyncCoordinator`, Web Locks, Storage Lease, IndexedDB, idempotência, precisão monetária, PWA, auth offline, isolamento multi-tenant
- [ ] Provar os 10 critérios de aceitação arquitetural (ver `spec.md`)
