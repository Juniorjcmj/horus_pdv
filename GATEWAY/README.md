# HorusGateway — CHANGE GATEWAY 02 + 03 + 04 + 05

Coordenador operacional local da loja (camada LAN **adicional**, nunca obrigatória). Permite que os terminais troquem eventos (ex.: pedidos) entre si — e com o caixa — mesmo sem internet, sincronizando com a cloud depois. Se o Gateway estiver indisponível, cada terminal volta ao comportamento offline-first atual (IndexedDB/Outbox → Cloud).

> Esta é a **infraestrutura inicial** (CHANGE GATEWAY 02). Ainda **não** implementa pedidos completos, NFC-e, Pix, cancelamento, devolução, registro/heartbeat de terminais nem sync Gateway→Cloud — esses vêm nos próximos changes. Objetivo provado aqui: **Terminal A → Gateway → Terminal B** sem internet, com idempotência, isolamento multi-tenant e persistência durável.

## Tecnologia
- ASP.NET Core 8 (mesmo padrão do backend Hórus)
- SQLite (`Microsoft.Data.Sqlite`, ADO.NET) — persistência local durável
- SignalR — distribuição de eventos em tempo real
- Logs estruturados (JSON console)

## Configuração (seção `Gateway`)
| Chave | Descrição |
|-------|-----------|
| `Gateway:GatewayId` | Identidade do Gateway (gerada no boot se vazia) |
| `Gateway:CompanyId` | Empresa vinculada — **eventos de outra empresa são recusados** |
| `Gateway:StoreId` | Loja atendida |
| `Gateway:DatabasePath` | Caminho do arquivo SQLite |
| `Gateway:RegistrationToken` | Segredo compartilhado provisionado nos terminais para o registro (LAN não confiável) |
| `Gateway:RequireTerminalAuth` | Exige credencial de terminal em ingestão/recuperação/heartbeat (padrão `true`) |
| `Gateway:TerminalOnlineWindowSeconds` | Janela sem heartbeat para considerar um terminal OFFLINE (padrão 60s) |

Via variável de ambiente: `Gateway__CompanyId=empresa-1` etc.

## Endpoints
| Método | Rota | Função |
|--------|------|--------|
| GET | `/health/live` | Liveness |
| GET | `/health/ready` | Readiness (storage) |
| GET | `/health` | Status agregado (gateway/storage/cloud/pendingEvents) |
| GET | `/api/gateway/status` | Descoberta/identidade (empresa/loja/hub) |
| POST | `/api/gateway/register` | Registro/autorização de terminal (token compartilhado → emite `apiKey`) |
| POST | `/api/gateway/heartbeat` | Heartbeat do terminal (headers `X-Terminal-Id`/`X-Terminal-Key`) → atualiza `LastSeenAt` |
| GET | `/api/gateway/terminals` | Lista terminais com online/offline (dashboard) |
| GET | `/api/gateway/orders` (+`?status=`) | Lista pedidos (visão do caixa) |
| GET | `/api/gateway/orders/{orderNumber}` | Estado atual de um pedido |
| POST | `/api/gateway/events` | Ingestão de evento (Terminal → Gateway), idempotente |
| GET | `/api/gateway/events?companyId=&after=&limit=` | Recuperação incremental (terminal que ficou offline) |
| WS | `/hubs/events` | SignalR — `Subscribe(companyId)` e callback `eventReceived` |

## Pedidos em tempo real (CHANGE GATEWAY 05)
Pedidos são tratados como eventos operacionais (`ORDER_CREATED/UPDATED/RECEIVED/CONFIRMED/PREPARING/READY/DELIVERED/CANCELLED`). Uma projeção `Orders` (read model) é derivada dos eventos, aplicando a máquina de estados `CREATED → RECEIVED → CONFIRMED → PREPARING → READY → DELIVERED` (avanços podem pular etapas; cancelamento a partir de qualquer estado não terminal). Transições inválidas são rejeitadas com **409** e não gravam evento. Cada mudança gera evento — nunca se altera estado sem registrar a transição. Ao aceitar um evento de pedido, o Gateway atualiza a projeção e transmite `orderUpdated` via SignalR (PDV → Gateway → Caixa). O caixa **consulta** via `GET /orders`; para **aceitar/cancelar/finalizar** ele publica os eventos `ORDER_*` correspondentes. Confirmação de pagamento (Pix via PSP) fica para o CHANGE GATEWAY 06.

## Autenticação de terminal (CHANGE GATEWAY 03)
A LAN é tratada como **não confiável**. Fluxo: o admin provisiona o `RegistrationToken` nos terminais → o terminal chama `POST /register` (valida token + `CompanyId`) e recebe uma `apiKey` única (só o hash PBKDF2 fica no Gateway). Depois, ingestão/recuperação/heartbeat exigem os headers `X-Terminal-Id` + `X-Terminal-Key`. O Gateway autentica o **terminal** ("pertence a esta loja?"), nunca o usuário — a autenticação de usuário continua na cloud. A descoberta do Gateway (mDNS/URL/manual) e o armazenamento seguro da credencial no PWA são integrados no CHANGE GATEWAY 07.

## Idempotência
`EventId` nasce no terminal. O Gateway calcula um `PayloadHash` canônico (SHA-256):
- mesmo `EventId` + mesmo hash → **replay** legítimo (não duplica);
- mesmo `EventId` + hash diferente → **409 Conflict**.

## Isolamento multi-tenant
`CompanyId` faz parte da chave lógica e de toda consulta. O Gateway é vinculado a uma empresa; eventos/consultas de outra empresa recebem **403**.

## Rodar local (Docker)
```bash
cd GATEWAY
GATEWAY_COMPANY_ID=empresa-1 GATEWAY_STORE_ID=store-001 docker compose up --build
# API em http://localhost:5080 — dados persistem no volume horus-gateway-data
```

## Rodar local (dotnet)
```bash
cd GATEWAY/src/HorusGateway
dotnet run   # usa appsettings.Development.json (empresa-1 / store-001)
```

## Testes
```bash
cd GATEWAY
dotnet test
```
Cobrem: start, health, ingestão, persistência, replay, conflito, isolamento multi-tenant, sobrevivência a reinicialização, recuperação por cursor, dois terminais na mesma empresa e entrega em tempo real via SignalR.

## Localização física (flexível)
O Gateway não assume rodar no caixa. Suporta: (A) roteador/servidor LAN, (B) máquina do caixa, (C) servidor local dedicado — é apenas um processo ASP.NET Core acessível na LAN.
