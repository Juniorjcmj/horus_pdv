# Proposal: Cloud entrega o endereço do Local Gateway aos terminais (Descoberta via Cloud)

## Why

Hoje o terminal (PWA) só encontra o Local Gateway (Quack Gateway) na LAN por uma **URL previamente
configurada** (`gatewayConfig.url` em `localStorage`) — ver `FRONTEND/src/infrastructure/gateway/gatewayDiscovery.ts`.
Isso contraria o objetivo de **zero configuração no terminal**: alguém teria que digitar o IP do Gateway
em cada PWA, e refazer isso sempre que o IP mudasse.

Restrição técnica incontornável: **um PWA não abre socket UDP nem faz descoberta mDNS/broadcast a partir
do JavaScript** — só consegue fazer requisições HTTP para um endereço/hostname. Logo, "achar o Gateway
sozinho na rede" no estilo Bonjour **não é possível puro no navegador**; o endereço precisa chegar ao
terminal por uma via legítima **antes** de a internet cair.

A via que respeita o **contrato do usuário** — *"o ponto de comunicação sempre será com a Cloud; o terminal
só procura o Gateway quando não houver internet"* — é a **própria Cloud entregar o endereço**. O administrador
cadastra, no painel da Cloud, o endereço LAN do Gateway **por loja**. Enquanto o terminal está **online**, ele
já aprende esse endereço e o guarda em cache local. Quando a internet cai, o terminal **já sabe** onde o Gateway
está — sem ninguém tocar no terminal, e com um único ponto de administração (a Cloud).

## What Changes

Esta change **é apenas análise arquitetural e proposta** — não altera código, banco, API nem frontend.
Entrega os artefatos de decisão para aprovar a arquitetura da **descoberta via Cloud (opção C)** antes de
qualquer implementação, preservando os **71 testes de arquitetura homologados**.

Comportamento-alvo (a ser implementado após aprovação):

1. **Fonte da verdade na Cloud** — o endereço LAN do Gateway (URL/host + porta) é um dado **por loja**,
   cadastrado pelo admin no painel da Cloud e servido a cada terminal daquela loja.
2. **Aprendizado automático (online)** — enquanto há internet, o terminal busca sua configuração de loja na
   Cloud (incluindo o endereço do Gateway) e faz **cache local** (`gatewayConfig`), sem intervenção humana.
3. **Uso automático (offline)** — quando a internet cai, o terminal lê o endereço em cache e cai no fluxo já
   existente (`probeGateway` → `identify` por IP/token → credencial local). **Zero config no terminal.**
4. **HTTPS → HTTP (mixed content)** — tratar o bloqueio do navegador ao acessar o Gateway HTTP a partir de uma
   página HTTPS (decisão de rede/cert descrita em `design.md`), pois afeta qualquer forma de descoberta.

### Escopo desta proposta (arquitetura)

- Modelo de dado do endereço do Gateway **por loja** na Cloud (autoridade única).
- Contrato de leitura pelo terminal (endpoint da Cloud) e de escrita pelo admin (painel da Cloud).
- Fluxo de **cache/expiração** no terminal e precedência sobre a URL manual atual (compatibilidade).
- Estratégia para o **mixed content** HTTPS→HTTP na LAN (nome + certificado, ou exceção de rede privada).
- Preservação estrita do fluxo homologado: em condição normal o terminal continua falando **direto com a
  Cloud**; a camada de descoberta é **aditiva** e só influencia o *fallback* offline já previsto.

## Non-goals

- **Não** implementar descoberta ativa por mDNS/UDP/scan a partir do navegador (impossível puro no PWA).
- **Não** transformar o Gateway em segunda verdade — ele continua coordenando LAN e sincronizando com a Cloud.
- **Não** rotear vendas/caixa pelo Gateway em condição normal (contrato: com internet, tudo vai à Cloud).
- **Não** alterar o mecanismo de autenticação de usuário (segue na Cloud) nem a idempotência já homologada.
- **Não** implementar emissão fiscal/Pix no Gateway (fora do escopo, como nas changes anteriores).

## Impact

- **Afeta a API/Cloud principal (código homologado):** requer um pequeno acréscimo de leitura/escrita do
  endereço do Gateway por loja (novo endpoint + persistência em SQL Server via ADO.NET). **Esta é a parte
  sensível** — será isolada, aditiva e coberta por testes; nada de fluxos existentes é alterado. Requer
  aprovação explícita antes de tocar no homologado.
- **Frontend admin:** nova tela/campo (área Admin) para o endereço do Gateway por loja.
- **Frontend terminal:** um passo de *bootstrap* que, quando online, lê o endereço na Cloud e alimenta o
  `gatewayConfig` — aditivo, sem alterar o fluxo de vendas/caixa homologado.
- **71 testes de arquitetura:** devem permanecer verdes; a change não muda IndexedDB/Outbox/SyncEngine/
  SyncCoordinator/Web Locks/idempotência/precisão monetária/PWA/auth offline/isolamento multi-tenant.
