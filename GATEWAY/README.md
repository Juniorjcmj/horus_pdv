# HorusGateway — CHANGE GATEWAY 02

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

Via variável de ambiente: `Gateway__CompanyId=empresa-1` etc.

## Endpoints
| Método | Rota | Função |
|--------|------|--------|
| GET | `/health/live` | Liveness |
| GET | `/health/ready` | Readiness (storage) |
| GET | `/health` | Status agregado (gateway/storage/cloud/pendingEvents) |
| GET | `/api/gateway/status` | Descoberta/identidade (empresa/loja/hub) |
| POST | `/api/gateway/events` | Ingestão de evento (Terminal → Gateway), idempotente |
| GET | `/api/gateway/events?companyId=&after=&limit=` | Recuperação incremental (terminal que ficou offline) |
| WS | `/hubs/events` | SignalR — `Subscribe(companyId)` e callback `eventReceived` |

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
