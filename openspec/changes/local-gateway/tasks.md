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

## CHANGE GATEWAY 02 — Projeto e infraestrutura básica

- [ ] Criar projeto `HorusGateway` (ASP.NET Core)
- [ ] Configuração e identificação: `GatewayId`, `CompanyId`, `StoreId`
- [ ] Armazenamento local (SQLite) e schema base
- [ ] Health check (`/health`, `/health/live`, `/health/ready`)
- [ ] Execução como Windows Service (alvo inicial) / systemd
- [ ] Sem alterar fluxo de vendas

## CHANGE GATEWAY 03 — Registro de terminais, heartbeat, descoberta, auth

- [ ] Endpoint de registro/autorização de terminal (valida `CompanyId`, emite credencial local)
- [ ] Armazenamento seguro da credencial no terminal
- [ ] Heartbeat (`POST /api/gateway/heartbeat`, 10–30s) atualizando `LastSeenAt`
- [ ] Descoberta do Gateway em 3 níveis (mDNS/hostname → URL configurada → manual)
- [ ] Validação do vínculo `Terminal → Store → Gateway → Company` em toda requisição

## CHANGE GATEWAY 04 — Event Bus local

- [ ] Tabela `GatewayEvents` e `ProcessedEvents` local
- [ ] `POST /api/gateway/events`: recebe → valida `CompanyId`/`TerminalId`/`PayloadHash` → persiste → commit → ACK
- [ ] `EventId` preservado (nasce no terminal); dedup por `EventId`+`PayloadHash`
- [ ] `409 Conflict` para mesmo `EventId` com `PayloadHash` diferente
- [ ] Retry + backoff exponencial (reusar regras do `SyncEngine`)

## CHANGE GATEWAY 05 — Pedidos em tempo real

- [ ] Eventos `ORDER_CREATED`, `ORDER_UPDATED`, `ORDER_CANCELLED` (e demais estados)
- [ ] Máquina de estados `CREATED → RECEIVED → CONFIRMED → PREPARING → READY → DELIVERED`
- [ ] Canal tempo-real (SignalR): `Terminal → Gateway → Caixa`
- [ ] Caixa: visualizar/aceitar/cancelar/finalizar/registrar pagamento
- [ ] Cache de catálogo local com `version`/`updatedAt`/`source` (delta), se necessário

## CHANGE GATEWAY 06 — Sincronização Gateway → Cloud

- [ ] Outbox do Gateway e `CLOUD_SYNC` na ordem correta
- [ ] Estados `PENDING_CLOUD` / `SYNCED_CLOUD` / `FAILED`
- [ ] Retry, recuperação após reboot/energia, idempotência ponta-a-ponta
- [ ] Reusar idempotência da cloud (`ProcessedEvents`); nada de "segunda verdade"

## CHANGE GATEWAY 07 — Integração com o SyncEngine existente (fallback)

- [ ] Generalizar Outbox atual com `Destination = GATEWAY | CLOUD` (sem `SyncEngine2`)
- [ ] Decisão no terminal: Gateway disponível? → Gateway; senão Cloud; senão Offline Local
- [ ] Fallback configurável por tipo de operação; Outbox atual intacta
- [ ] Roteamento inserido em `apiClient.ts`; `ConnectivityService` passa a conhecer o Gateway

## CHANGE GATEWAY 08 — Interface de monitoramento

- [ ] Dashboard local: Internet, Gateway, Cloud, terminais, eventos pendentes/erro, última sync, último heartbeat
- [ ] Indicadores em tempo real via heartbeat e health check
- [ ] Mecanismo de atualização controlada do Gateway (checar eventos pendentes antes de atualizar)

## Validação transversal (em toda change 02–08)

- [ ] Rodar `FRONTEND/tests/architecture/*` — os 71 testes homologados continuam verdes
- [ ] Verificar preservação de: `CompanyId`, `EventId`, `PayloadHash`, `ProcessedEvents`, Outbox, `SyncEngine`, `SyncCoordinator`, Web Locks, Storage Lease, IndexedDB, idempotência, precisão monetária, PWA, auth offline, isolamento multi-tenant
- [ ] Provar os 10 critérios de aceitação arquitetural (ver `spec.md`)
