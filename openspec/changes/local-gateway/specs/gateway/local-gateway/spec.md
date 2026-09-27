# Spec: Local Gateway — Coordenação Operacional Local por Loja

## Overview

Camada intermediária **opcional** (Local Gateway, ASP.NET Core + SQLite, rodando como serviço no PC do caixa) que coordena N terminais de uma loja pela LAN, permitindo que pedidos, status e vendas continuem fluindo entre terminal e caixa mesmo sem internet, com sincronização posterior à cloud. O Gateway é uma camada **aditiva**: sua ausência faz o terminal voltar ao comportamento offline-first atual. A cloud (SQL Server) permanece a autoridade empresarial.

## Requirements

### REQ-1: Três modos de operação
- **ONLINE:** `Terminal → Gateway → Cloud` (Gateway coordena; terminal pode falar direto com a cloud quando apropriado à operação).
- **LAN-OFFLINE:** `Terminal → Gateway → Local Store → Caixa`; independe da internet.
- **ISOLADO:** `Terminal → IndexedDB/Outbox` (comportamento atual do Hórus, preservado). O Gateway nunca pode ser dependência do funcionamento offline já existente.

### REQ-2: Regra de ouro e fallback
- Fluxo permitido: `Internet ↓ Gateway ↓ Terminal`. Proibida dependência circular `Terminal ↓ Gateway ↓ Terminal ↓ Gateway`.
- Prioridade de comunicação: `1. Gateway LAN → 2. Cloud → 3. IndexedDB/Outbox local`, **configurável por tipo de operação** (nem toda operação passa pelo Gateway).
- Cada terminal mantém IndexedDB, Outbox, `EventId`, `PayloadHash`, `SyncEngine` e estado offline próprios.

### REQ-3: Identidade e isolamento multi-tenant
- Gateway: `GatewayId`, `CompanyId`, `StoreId`. Terminal: `TerminalId`, `CompanyId`, `StoreId`, `TerminalType (ORDER|CASH)`.
- O Gateway é estritamente vinculado a uma empresa; **nunca** aceitar evento de `CompanyId` diferente. Toda requisição valida `CompanyId` e o vínculo `Terminal → Store → Gateway → Company`.

### REQ-4: Autenticação LAN (rede não confiável)
- A LAN é considerada não confiável; estar na mesma rede não autoriza. O terminal realiza registro/autorização no Gateway e recebe uma credencial local (API Key + Terminal Secret, ou JWT local de curta duração), armazenada com segurança.
- O Gateway autentica o **terminal** ("terminal autorizado pertence a esta loja?"), **não** o usuário. A autenticação de usuário permanece na cloud (`AuthController`), sem duplicação.

### REQ-5: Descoberta do Gateway
- Três níveis: (1) hostname/mDNS; (2) URL previamente configurada; (3) configuração manual pelo admin. Suportar IP fixo, DHCP reservation, hostname e descoberta automática. Sem IP hardcoded no frontend. Configuração persistida localmente no terminal.

### REQ-6: Event Bus local e idempotência reutilizada
- `GatewayEvents` com: `EventId`, `CompanyId`, `StoreId`, `TerminalId`, `EventType`, `OccurredAt`, `Payload`, `PayloadHash`, `Status`, `CreatedAt`, `ProcessedAt`.
- **`EventId` nasce no terminal** e não muda ao atravessar Gateway → Cloud (rastreabilidade da cadeia).
- `PayloadHash` sobre payload canônico. Mesmo `EventId` + mesmo hash → replay aceito; mesmo `EventId` + hash diferente → **409 Conflict** (sem alteração silenciosa).
- Reutilizar o modelo existente (`EventId` + `PayloadHash` + `ProcessedEvents`); a mesma operação atravessa Terminal → Gateway → Cloud como **uma única operação lógica**.

### REQ-7: Comunicação Terminal ↔ Gateway
- REST para: registrar terminal, enviar eventos, consultar estado/pedidos, ACK, heartbeat.
- WebSocket/SignalR para tempo-real: novo pedido, pedido atualizado, pagamento confirmado, cancelamento, alteração de status.

### REQ-8: Sincronização em duas etapas independentes
- `Terminal → Gateway` = `LOCAL_DELIVERY`; `Gateway → Cloud` = `CLOUD_SYNC`.
- Estados de evento: `PENDING_LOCAL`, `DELIVERED_LOCAL`, `PENDING_CLOUD`, `SYNCED_CLOUD`, `FAILED`. "Entregue ao Gateway" ≠ "sincronizado com a cloud".
- **Não** duplicar o `SyncEngine`; generalizar a Outbox atual com `Destination = GATEWAY | CLOUD` (ou abstração equivalente).
- Retry: temporários (`5xx`, timeout, reset) → retry + backoff exponencial (reusar regras do `SyncEngine`); permanentes (`400/401/403/409`) tratados por tipo.

### REQ-9: ACK e persistência durável
- Nunca responder `200 OK` antes de persistir. Fluxo: `recebe → valida → persiste → commit → ACK`.
- Gateway usa armazenamento persistente (SQLite); nenhum evento vive só em RAM. Recuperável após reboot e queda de energia (recarrega `PENDING`, valida integridade, retoma sync).

### REQ-10: Pedidos como eventos operacionais
- Eventos: `ORDER_CREATED`, `ORDER_UPDATED`, `ORDER_CANCELLED`, `ORDER_CONFIRMED`, `ORDER_READY`, `ORDER_DELIVERED`.
- Máquina de estados: `CREATED → RECEIVED → CONFIRMED → PREPARING → READY → DELIVERED`. Cada transição gera evento; nunca atualizar estado sem registrar a transição.
- Caixa recebe pedidos via Gateway e pode visualizar, aceitar, cancelar, finalizar e registrar pagamento.

### REQ-11: Pagamentos
- Dinheiro: pode operar 100% offline (registro local, sync posterior).
- Pix: **depende do PSP**. O Gateway não simula/confirma Pix. `QR exibido ≠ pagamento recebido`; só `PSP confirmado = pagamento recebido` (webhook → cloud → Gateway → caixa). Pagamento tem `PaymentId`, `EventId`, `TxId`, `Status` próprios.

### REQ-12: Fronteiras (não é segunda verdade)
- O Gateway não cria versão própria de estoque, clientes, vendas ou financeiro; mantém só o necessário para operação local. Autoridade final = cloud SQL Server.
- Não resolve conflito global de estoque; estoque negativo em offline concorrente é característica conhecida e aceita.
- Cache local (`produtos`, `preços`, `config`, `terminais`, `pedidos`, `eventos`) deve ter `version`, `updatedAt`, `source`; catálogo sincroniza por delta quando possível.

### REQ-13: Fiscal e impressão (fora da v1)
- Emissão fiscal (NFC-e) permanece na cloud; contingência fiscal local é projeto separado. Impressão pelo Gateway não entra na v1, mas a arquitetura deve ficar preparada.

### REQ-14: Monitoramento e saúde
- Dashboard local do Gateway: Internet, Gateway, Cloud, terminais (online/offline via heartbeat), eventos pendentes, eventos com erro, última sincronização, último heartbeat.
- Heartbeat por terminal a cada 10–30s atualiza `LastSeenAt`. Endpoints `/health`, `/health/live`, `/health/ready` (gateway/storage/cloud/pendingEvents).

### REQ-15: Preservação dos Changes 01–07.3
- Nenhuma feature do Gateway pode quebrar: `CompanyId`, `EventId`, `PayloadHash`, `ProcessedEvents`, Outbox, `SyncEngine`, `SyncCoordinator`, Web Locks, Storage Lease, IndexedDB, idempotência, precisão monetária, PWA, autenticação offline, isolamento multi-tenant. Os 71 testes de arquitetura (`FRONTEND/tests/architecture/*`) devem continuar verdes.

## Acceptance Criteria (arquitetura)

A arquitetura deve provar, antes de features adicionais:

1. **Internet funcionando** → `Terminal → Gateway → Cloud`.
2. **Internet desligada** → `Terminal → Gateway → Caixa`.
3. **Internet + Gateway desligados** → `Terminal → IndexedDB/Outbox` (offline atual).
4. **Gateway reinicia** → eventos persistidos são recuperados.
5. **Terminal reinicia** → Outbox continua intacta.
6. **Dois terminais enviam o mesmo evento** → 1 operação, 1 `EventId`, 1 processamento.
7. **Mesmo `EventId` com `PayloadHash` diferente** → `409 Conflict`.
8. **Internet retorna** → Gateway sincroniza automaticamente.
9. **Gateway fica indisponível** → terminal continua funcionando offline.
10. **Dois terminais criam pedidos simultaneamente** → ambos persistidos sem colisão.

## Regra absoluta desta fase

Esta é a fase de análise (CHANGE GATEWAY 01). **Não** alterar código, criar arquivos de implementação, modificar banco, implementar Gateway/Pix, nem criar novos testes. Apenas a análise arquitetural e a proposta para aprovação.
