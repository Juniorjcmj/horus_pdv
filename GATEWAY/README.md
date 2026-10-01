# Quack Gateway — CHANGE GATEWAY 02 + 03 + 04 + 05 + 06 + 07 + 08

> **Nome do produto:** Quack Gateway. O nome técnico do processo/serviço/assembly permanece
> `HorusGateway` (arquivos `HorusGateway.dll`/`HorusGateway.exe` e o serviço `sc` `HorusGateway`),
> por ser identificador interno — a marca exibida (painel, banners e Serviço do Windows) é **Quack Gateway**.

Coordenador operacional local da loja (camada LAN **adicional**, nunca obrigatória). Permite que os terminais troquem eventos (ex.: pedidos) entre si — e com o caixa — mesmo sem internet, sincronizando com a cloud depois. Se o Gateway estiver indisponível, cada terminal volta ao comportamento offline-first atual (IndexedDB/Outbox → Cloud).

> Esta é a **infraestrutura do Gateway Local** (Changes GATEWAY 02 a 08 concluídas). Prova: **Terminal A → Gateway → Terminal B** sem internet, com idempotência, isolamento multi-tenant, persistência durável no SQLite, sincronização com a Cloud, dashboard local de monitoramento e verificação de atualização controlada.

## Tecnologia
- ASP.NET Core 8 (mesmo padrão do backend Hórus)
- SQLite (`Microsoft.Data.Sqlite`, ADO.NET) — persistência local durável
- SignalR — distribuição de eventos em tempo real
- Logs estruturados (JSON console)
- Dashboard embutido (HTML5/CSS3/Vanilla JS responsivo)

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
| `Gateway:CloudSyncUrl` | Endpoint de ingestão em lote da cloud. Vazio = sync desligado (LAN-only, eventos acumulam PENDING_CLOUD) |
| `Gateway:CloudSyncIntervalSeconds` | Intervalo entre ciclos do dispatcher Gateway → Cloud (padrão 15s) |
| `Gateway:CloudSyncBatchSize` | Máximo de eventos por ciclo (padrão 50) |
| `Gateway:CloudSyncToken` | Token Bearer opcional enviado à cloud |

Via variável de ambiente: `Gateway__CompanyId=empresa-1` etc.

## Endpoints
| Método | Rota | Função |
|--------|------|--------|
| GET | `/` ou `/dashboard` | Dashboard Web de monitoramento local embutido |
| GET | `/api/gateway/dashboard` | Resumo consolidado para monitoramento (KPIs, saúde, eventos, terminais) |
| GET | `/api/gateway/system/update-check` | Verificação pré-atualização segura (bloqueia se pendingEvents > 0) |
| GET | `/health/live` | Liveness |
| GET | `/health/ready` | Readiness (storage) |
| GET | `/health` | Status agregado (gateway/storage/cloud/pendingEvents) |
| GET | `/health/update-readiness` | Prontidão para atualização controlada (seguro só sem eventos pendentes) |
| GET | `/` | Painel local de monitoramento/administração (dashboard estático) |
| GET | `/api/gateway/status` | Descoberta/identidade (empresa/loja/hub) |
| POST | `/api/gateway/terminals/provision` | Admin pré-autoriza um terminal por IP e/ou token (allowlist) |
| POST | `/api/gateway/identify` | Terminal se auto-identifica pelo IP de origem (ou token) e recebe credencial |
| POST | `/api/gateway/register` | Registro do terminal (token compartilhado, ou aberto) → emite `apiKey` |
| POST | `/api/gateway/heartbeat` | Heartbeat do terminal (headers `X-Terminal-Id`/`X-Terminal-Key`) → atualiza `LastSeenAt` |
| GET | `/api/gateway/terminals` | Lista terminais com online/offline (dashboard) |
| GET | `/api/gateway/orders` (+`?status=`) | Lista pedidos (visão do caixa) |
| GET | `/api/gateway/orders/{orderNumber}` | Estado atual de um pedido |
| POST | `/api/gateway/events` | Ingestão de evento (Terminal → Gateway), idempotente |
| GET | `/api/gateway/events?companyId=&after=&limit=` | Recuperação incremental (terminal que ficou offline) |
| WS | `/hubs/events` | SignalR — `Subscribe(companyId)` e callback `eventReceived` |

## Dashboard Local e Atualização Controlada (CHANGE GATEWAY 08)
O Gateway expõe em `/` e `/dashboard` uma interface web responsiva que exibe o status de saúde do processo, armazenamento SQLite, conectividade com a Cloud, lista de terminais online/offline via heartbeat e métricas de eventos da fila. Além disso, o endpoint `/api/gateway/system/update-check` permite que scripts de implantação, rotinas de manutenção ou o próprio dashboard validem se o Gateway pode ser reiniciado ou atualizado com segurança (`pendingEvents == 0`), evitando que eventos da LAN sejam retidos sem sincronização com a Cloud.

## Sincronização Gateway → Cloud (CHANGE GATEWAY 06)
Um dispatcher em background consome os eventos devidos (`GetDueForDispatchAsync`, com backoff do CHANGE 04) e os envia à `CloudSyncUrl`, preservando `EventId` + `PayloadHash` (idempotência ponta-a-ponta). Desfechos: **2xx** → `SYNCED_CLOUD`; **409** (a cloud já processou) → idempotente, também `SYNCED_CLOUD`; **5xx/timeout/rede** → reagenda com backoff; **4xx** → `FAILED`. Sem `CloudSyncUrl`, o sync fica desligado e o Gateway opera LAN-only (eventos acumulam `PENDING_CLOUD`). A recuperação após reinicialização é automática — os pendentes vivem no SQLite. O `/health` reflete `cloud` (`disabled`/`online`/`offline`) e `pendingEvents`. A cloud continua a autoridade; o Gateway não cria segunda verdade.

## Pedidos em tempo real (CHANGE GATEWAY 05)
Pedidos são tratados como eventos operacionais (`ORDER_CREATED/UPDATED/RECEIVED/CONFIRMED/PREPARING/READY/DELIVERED/CANCELLED`). Uma projeção `Orders` (read model) é derivada dos eventos, aplicando a máquina de estados `CREATED → RECEIVED → CONFIRMED → PREPARING → READY → DELIVERED` (avanços podem pular etapas; cancelamento a partir de qualquer estado não terminal). Transições inválidas são rejeitadas com **409** e não gravam evento. Cada mudança gera evento — nunca se altera estado sem registrar a transição. Ao aceitar um evento de pedido, o Gateway atualiza a projeção e transmite `orderUpdated` via SignalR (PDV → Gateway → Caixa). O caixa **consulta** via `GET /orders`; para **aceitar/cancelar/finalizar** ele publica os eventos `ORDER_*` correspondentes. Confirmação de pagamento (Pix via PSP) fica para o CHANGE GATEWAY 06.

## Pré-autorização e auto-identificação de terminais
Sem limite de terminais — quantos quiser, na mesma empresa. Modelo recomendado para conexão automática:
o **administrador pré-autoriza** cada terminal no Gateway (`POST /terminals/provision`) informando o
**IP** na LAN e/ou um **token**. Quando a internet cai, o terminal chama `POST /identify`: o Gateway o
**reconhece pelo IP de origem** (ou pelo token apresentado) e devolve a credencial na hora — **zero
configuração no terminal**. Cada terminal recebe credencial própria (auditável/revogável) e o
isolamento por empresa é sempre validado. Alternativas: registro por token compartilhado, ou registro
aberto (`Gateway:OpenRegistration=true`, padrão) em que qualquer terminal da empresa se registra sozinho.

## Painel local de monitoramento/administração (CHANGE GATEWAY 08)
O próprio Gateway serve um **dashboard** em `http://<ip-do-gateway>:5080/` — página única, **sem build/npm**,
que se atualiza a cada 5s consumindo os endpoints existentes. Mostra: estado do **Gateway/Storage/Cloud
(Internet)**, **eventos pendentes**, **última sincronização**, e a lista de **terminais** com online/offline
(por heartbeat), IP autorizado e situação (pré-autorizado/registrado/revogado). Traz o formulário de
**pré-autorização** de terminais (por **IP** e/ou **token**) e o painel de **atualização controlada**
(verde/amarelo): antes de parar o Gateway para atualizar, ele confirma que não há eventos presos apenas
no disco local. É a UI de quem opera a máquina do Gateway — o frontend homologado (que fala com a Cloud)
não muda em nada.

## Descoberta via Cloud + HTTPS na LAN (zero config no terminal)
Como um PWA não faz mDNS/UDP/scan a partir do navegador, o endereço do Gateway **chega ao terminal pela
Cloud**: o admin cadastra o endereço da loja no painel da Cloud (Configurações → *Local Gateway*), cada
terminal o aprende automaticamente enquanto online e o guarda em cache; quando a internet cai, o terminal
já sabe onde o Gateway está e cai no fluxo `identify` (por IP/token) — sem configurar cada terminal.

**Mixed content (HTTPS → HTTP):** se o terminal roda em HTTPS, o navegador bloqueia `fetch` para
`http://<ip>:5080`. Recomendado subir o Gateway em **HTTPS com um host**:
1. Dê um nome ao Gateway na LAN — `quack-gateway.local` (mDNS do SO) ou um host interno via DNS/DHCP
   reservation com IP fixo.
2. Gere um certificado `.pfx` para esse host e suba o Gateway em HTTPS:
   - **Linux/macOS:** `GATEWAY_HTTPS_CERT=/caminho/cert.pfx GATEWAY_HTTPS_CERT_PASSWORD=senha GATEWAY_PORT=5443 ./run-gateway.sh`
   - **Windows:** `$env:GATEWAY_HTTPS_CERT="C:\cert.pfx"; $env:GATEWAY_PORT=5443; ./run-gateway.ps1`
   (os scripts detectam `GATEWAY_HTTPS_CERT` e usam o Kestrel em HTTPS; sem ela, sobem em HTTP como antes.)
3. Cadastre na Cloud o endereço `https://quack-gateway.local:5443`.

Alternativa: manter HTTP com IP (`http://192.168.0.10:5080`) em redes/dispositivos que permitem conteúdo
misto para IP privado — menos robusto.

**Atalho no Windows:** `setup-https.ps1` gera o certificado pronto (com o **IP como SAN**, dispensando DNS):
```powershell
pwsh -File .\setup-https.ps1 -IpAddress 192.168.0.10 -PfxPassword "umaSenhaForte"
```
Gera `certs\quack-gateway.pfx` (para o Gateway) e `certs\quack-gateway.cer` (instalar em cada terminal nas
**Autoridades de Certificação Raiz Confiáveis**). Depois instale o serviço com `GATEWAY_HTTPS_CERT` apontando
para o `.pfx` e cadastre na Cloud `https://192.168.0.10:5443`.

## Instalação nativa (sem Docker)
O Gateway é ASP.NET Core 8 com **SQLite embutido** (nenhum banco a instalar — é um arquivo).
- **Self-contained (não instala nada na máquina):** `dotnet publish -c Release -r win-x64 --self-contained true -o publish-win`, copie a pasta e rode `HorusGateway.exe`.
- **Framework-dependent:** instale só o **ASP.NET Core Runtime 8** e rode `dotnet HorusGateway.dll`.
- Nada de SQL Server, Node ou Docker. Libere a porta LAN (padrão 5080) no firewall.

### Windows Service (inicia sozinho no boot — CHANGE GATEWAY 08)
O host usa `UseWindowsService()` (no-op fora do Windows) e, como serviço, resolve o caminho do SQLite e os
arquivos a partir da pasta do `.exe` (não do `System32`). O `install-service.ps1` faz tudo em um passo,
como **Administrador**:
```powershell
# GATEWAY\install-service.ps1  (clique direito > Executar com o PowerShell, como Admin)
pwsh -File .\install-service.ps1 -CompanyId minha-empresa -StoreId loja-01 -Port 5080
# HTTPS opcional:  $env:GATEWAY_HTTPS_CERT="C:\cert.pfx"; $env:GATEWAY_PORT=5443; .\install-service.ps1
```
O instalador: publica **self-contained win-x64**, cria o serviço com **início automático**, configura
**recuperação automática** (reinicia no crash em 5s/10s/30s via `sc failure`), cria a **fonte do Event Log**,
grava as variáveis de ambiente (`Gateway__*`), libera a **porta no firewall** e sobe o serviço.

**Logs** (como serviço não há console): arquivo em `publish-service\logs\gateway-YYYY-MM-DD.log` (rotação diária,
retenção 14 dias) **e** no **Visualizador de Eventos** do Windows (origem `HorusGateway`).

Gerencie com `sc.exe start HorusGateway` / `sc.exe stop HorusGateway`. Para remover, use
`.\uninstall-service.ps1` (para, exclui e preserva os dados; `-RemoveFirewall`/`-RemoveEventSource` opcionais).
**Atualização controlada:** antes de parar/atualizar, confira no painel que "Eventos pendentes" esteja **zero**
(o uninstall também avisa via `GET /health/update-readiness`).

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

## Executar com um clique (scripts `run-gateway`)
Precisa apenas do **.NET SDK 8** instalado. Escutam em `0.0.0.0:5080` (toda a LAN).
- **Windows:** duplo-clique em `GATEWAY\run-gateway.bat` (ou `run-gateway.ps1` — este ainda libera a porta 5080 no firewall se rodar como Administrador).
- **Linux/macOS:** `./GATEWAY/run-gateway.sh`

Personalize a empresa/loja/porta por variáveis de ambiente antes de executar:
`GATEWAY_COMPANY_ID`, `GATEWAY_STORE_ID`, `GATEWAY_PORT` (ex.: `GATEWAY_COMPANY_ID=minha-empresa ./GATEWAY/run-gateway.sh`).
Os dados ficam em `GATEWAY/gateway-data/horus-gateway.db` (persistem entre execuções).

## Testes
```bash
cd GATEWAY
dotnet test
```
Cobrem: start, health, ingestão, persistência, replay, conflito, isolamento multi-tenant, sobrevivência a reinicialização, recuperação por cursor, dois terminais na mesma empresa, entrega em tempo real via SignalR, provisionamento/auto-identificação de terminais (IP e token) e prontidão para atualização controlada (61 testes no total).

## Localização física (flexível)
O Gateway não assume rodar no caixa. Suporta: (A) roteador/servidor LAN, (B) máquina do caixa, (C) servidor local dedicado — é apenas um processo ASP.NET Core acessível na LAN.
