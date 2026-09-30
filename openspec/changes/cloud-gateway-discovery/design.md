# Design: Descoberta do Local Gateway via Cloud (endereço por loja)

## Análise do estado atual (antes desta proposta)

### Terminal (PWA) — descoberta atual
- `FRONTEND/src/infrastructure/gateway/gatewayConfig.ts`: guarda em `localStorage` (`horus-gateway-config`)
  `{ enabled, url, companyId, storeId, terminalId, terminalType, apiKey }`. Todos os acessos protegidos.
- `gatewayDiscovery.ts`: `resolveGatewayUrl()` devolve `getGatewayConfig().url`; `probeGateway()` bate em
  `/api/gateway/status` e só aceita se `bound && companyId == config.companyId`; cache de 10s.
- `gatewayClient.ts` / `orderGatewayTransport.ts`: registro/identify/heartbeat/publish e escolha de destino
  (com internet → Cloud; sem internet + Gateway → Gateway; senão → Outbox local).
- **Lacuna:** a `url` precisa ter sido preenchida manualmente. Não há via automática de o terminal saber o
  endereço.

### Cloud (ASP.NET Core, ADO.NET, SQL Server)
- Backend sem EF; repositórios em `API/NETCORE/Repositories/DatabaseAccess/*`; schema versionado por scripts
  em `HorusDatabaseInitializer.cs` (`NN_*.sql`). Autenticação/isolamento por `CompanyId` já existentes.
- **Lacuna:** não há onde guardar nem por onde servir o endereço do Gateway por loja.

### Restrição do navegador
- PWA **não** faz UDP/mDNS/scan de sub-rede a partir do JS. Só HTTP para host/IP conhecido. Portanto a
  descoberta "ativa" na rede é inviável puro no navegador — o endereço tem de **chegar** ao terminal.

## Architecture Overview

```
                 (ONLINE — condição normal)
Admin ──cadastra endereço da loja──▶ Cloud (SQL Server)
                                        │  GET /api/gateway-config (terminal autenticado)
Terminal ──────────────────────────────┘  aprende { url, enabled } → cache em gatewayConfig
   │
   ▼ (INTERNET CAI)
Terminal lê cache → probeGateway(url) → /identify (IP/token) → credencial local → opera na LAN
   │
   ▼ (SEM cache e SEM Cloud)
Terminal → IndexedDB/Outbox (modo ISOLADO atual, inalterado)
```

O endereço viaja **da Cloud para o terminal enquanto há internet**; o uso acontece **na LAN quando não há**.
Isso respeita o contrato: o ponto de comunicação é sempre a Cloud; o Gateway só entra no fallback offline.

## Fluxos (diagramas de sequência)

### Aprendizado (online)
```
Terminal (online) → Cloud: GET /api/gateway-config  (auth do terminal/usuário)
Cloud → Terminal: { enabled:true, url:"https://quack-gateway.local:5443", updatedAt }
Terminal: saveGatewayConfig({ enabled, url, companyId, storeId })  // cache local + TTL
```

### Uso (internet cai)
```
ConnectivityService: offline
Terminal: url = getGatewayConfig().url  (aprendida antes)
Terminal → Gateway: GET /api/gateway/status  → bound && companyId ok
Terminal → Gateway: POST /identify (IP de origem/token) → apiKey
Terminal ↔ Gateway: heartbeat/publish/recover (fluxo GATEWAY 03–07)
```

### Degradação (Cloud fora e sem cache)
```
Terminal: getGatewayConfig().url == ""  → probeGateway()=false
Terminal → IndexedDB/Outbox (ISOLADO)   // nenhum erro visível; vendas seguem
```

### Atualização/desligamento do endereço
```
Admin muda url (ou Enabled=false) na Cloud
Terminal (próxima janela online): GET /api/gateway-config → aplica novo url / limpa se disabled
```

## Modelo de dado (Cloud, aditivo)

Tabela nova (nome sugerido `LojaGatewayConfig`), uma linha por loja:

| Coluna | Tipo | Observação |
|--------|------|-----------|
| `CompanyId` | identificador | parte da chave lógica; isolamento |
| `StoreId` | identificador | parte da chave lógica |
| `GatewayUrl` | string | esquema+host+porta (`https://host:5443` ou `http://ip:5080`) |
| `Enabled` | bool | desliga o uso do Gateway sem apagar o endereço |
| `UpdatedAt` | datetime | carimbo para TTL/cache no terminal |
| `UpdatedBy` | identificador | auditoria (opcional) |

- Migração via novo script `NN_loja_gateway_config.sql` registrado no `HorusDatabaseInitializer` (mesmo padrão
  dos scripts 20/21). **Não** altera tabelas existentes.

## Endpoints (Cloud, aditivos)

| Método | Rota | Função |
|--------|------|--------|
| GET | `/api/gateway-config` | Terminal autenticado obtém o endereço do Gateway da **sua** loja |
| PUT | `/api/admin/gateway-config` | Admin define/edita o endereço da loja (valida URL + perfil) |
| DELETE/PUT | `/api/admin/gateway-config` (`Enabled=false`) | Admin desabilita o uso |

- Novo repositório `LojaGatewayConfigAB.cs` (ADO.NET), isolado dos existentes.
- Contratos novos; **nenhum** endpoint existente muda de forma.

## Terminal (aditivo)

- Novo módulo `cloudGatewayProvisioning.ts` em `src/infrastructure/gateway/`:
  - `learnGatewayFromCloud()`: quando online, chama `GET /api/gateway-config` via o `apiClient` existente e,
    em sucesso, `saveGatewayConfig({...})`; em falha, não faz nada (silencioso).
  - Chamado num ponto de bootstrap/reconexão já existente (ex.: quando `ConnectivityService` vira online),
    com o **mínimo** de acoplamento — sem alterar o fluxo de vendas/caixa homologado.
- `gatewayConfig` ganha `updatedAt`/TTL (campo aditivo, default vazio) para revalidação; sem quebrar o shape
  atual (merge com `EMPTY`).
- Precedência: endereço da Cloud > URL manual anterior; URL manual permanece como *fallback*.

## Estratégias

### Mixed content (HTTPS → HTTP) — a decisão crítica de infra
Como o app roda em HTTPS, `fetch("http://<ip>:5080")` é bloqueado. Opções (a arquitetura suporta todas via
"host+porta+esquema"):
1. **Gateway em HTTPS com host** (recomendado): o Gateway escuta HTTPS (ex.: `:5443`) com certificado para um
   **nome** resolvível na LAN (`quack-gateway.local` via mDNS do SO, ou host interno via DNS/hosts/DHCP). O
   endereço cadastrado na Cloud é `https://quack-gateway.local:5443`. Sem mixed content.
2. **Exceção de rede privada**: políticas de dispositivo/navegador permitindo HTTP para IP privado (varia por
   versão/gestão de frota). Cadastra-se `http://<ip>:5080`. Menos robusto.
3. **IP fixo por DHCP reservation** combinado com (1) para estabilidade do host.

A change não força uma opção; garante que **URL com host e https** é um valor válido de ponta a ponta e
documenta o procedimento de certificado/host no README do Gateway.

### Cache e TTL no terminal
- Revalida a cada janela online; usa `UpdatedAt` da Cloud. `Enabled=false` limpa o uso. Nunca bloqueia a
  operação por causa da descoberta.

### Segurança/isolamento
- `GET /api/gateway-config` deriva `CompanyId/StoreId` do contexto autenticado; nunca aceita loja por parâmetro
  livre. Escrita restrita a perfil admin. Testes cobrem cross-tenant negado.

## Riscos e mitigações

| Risco | Mitigação |
|-------|-----------|
| Tocar API/SQL homologada | Mudanças **aditivas** (nova tabela, novos endpoints, novo repositório); nenhum contrato existente alterado; suíte de testes antes/depois |
| Mixed content quebrar o fallback | Suportar host+https no dado e no cliente; documentar cert/host; opção IP como degradação consciente |
| Endereço desatualizado em cache | TTL + revalidação online + `Enabled` para desligar remotamente |
| Cloud fora no primeiro boot (sem cache) | Degrada para ISOLADO atual, sem erro; aprende quando a internet voltar |
| Regressão nos 71 testes | Camada aditiva; rodar arch tests a cada etapa; terminal não altera vendas/caixa |

## Impacto nos Changes 01–08 (71 testes homologados)
- Nenhuma alteração em IndexedDB/Outbox/SyncEngine/SyncCoordinator/Web Locks/idempotência/precisão monetária/
  PWA/auth offline/isolamento. A descoberta é aditiva e só influencia o *fallback* offline já previsto.

## Arquivos que seriam alterados (nas implementações futuras, NÃO nesta change)
- **Cloud:** `API/NETCORE/Repositories/DatabaseAccess/LojaGatewayConfigAB.cs` (novo), um `Controllers/*` novo
  (ou método aditivo em controller existente), `Repositories/HorusDatabaseInitializer.cs` (+1 script),
  `API/NETCORE/scripts/NN_loja_gateway_config.sql` (novo).
- **Frontend admin:** nova tela/campo em `FRONTEND/src/components/Admin/*` para o endereço por loja.
- **Frontend terminal:** `src/infrastructure/gateway/cloudGatewayProvisioning.ts` (novo) + um gancho mínimo no
  bootstrap/reconexão; `gatewayConfig.ts` (campo `updatedAt` aditivo).
- **Gateway:** README (procedimento HTTPS/host/cert). Nenhuma mudança de lógica obrigatória.

## Migrações necessárias
- `NN_loja_gateway_config.sql`: cria `LojaGatewayConfig` (idempotente, `IF NOT EXISTS`), registrado no
  initializer no mesmo padrão dos scripts existentes. Sem alterar tabelas atuais.

## Plano de implementação incremental
Ver `tasks.md` (CLOUD-DISC 01–05). Cada etapa roda os 71 testes de arquitetura + testes do Gateway e mantém
tudo verde antes de prosseguir. A etapa que toca a API/SQL homologada só inicia com aprovação explícita.
