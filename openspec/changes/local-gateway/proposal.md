# Proposal: Local Gateway — Offline-first por Loja

## Why

Hoje o Hórus PDV é **offline-first por dispositivo**: cada terminal (PWA) tem seu próprio IndexedDB, Outbox, `SyncEngine`, `SyncCoordinator` e `ConnectivityService`, e fala **direto** com a cloud (`fetch` com `credentials: "include"` para `VITE_*_API_URL`). Quando a internet cai, cada terminal continua operando isolado, mas **os terminais não conversam entre si**: um terminal de pedido não consegue mandar o pedido para o caixa sem a cloud.

O cenário alvo é uma loja com **4 terminais de pedido + 1 caixa + 1 internet + LAN**. A necessidade real é: com a internet fora, os terminais ainda precisam **enviar pedidos ao caixa, atualizar status, vender e manter a operação**, sincronizando com a cloud depois.

Este documento propõe adicionar uma camada intermediária — o **Local Gateway** — que roda como serviço no PC do caixa (ASP.NET Core) e coordena os terminais pela LAN. É uma **camada adicional e temporária**, nunca uma dependência obrigatória: se o Gateway cair, o terminal volta ao comportamento atual (IndexedDB/Outbox → Cloud).

**Esta change é apenas análise arquitetural e proposta.** Não altera código, banco, API nem frontend. O objetivo é aprovar a arquitetura e o plano incremental (Changes GATEWAY 01–08) antes de qualquer implementação, garantindo que os **71 testes de arquitetura já homologados** (Changes 01–07.3) continuem passando.

## What Changes

Esta change **não implementa** o Gateway. Ela entrega os artefatos de decisão:

- **`proposal.md`** — este documento (o quê e por quê).
- **`design.md`** — a arquitetura proposta em detalhe: diagrama de componentes, diagramas de sequência (online / LAN-offline / isolado / recuperação), entidades, endpoints, fluxo de eventos, e as estratégias de autenticação, descoberta, persistência, sincronização e recuperação. Inclui a análise do repositório atual, os riscos, o impacto nos Changes 01–07.3, os arquivos que seriam alterados, as migrações necessárias e o plano incremental.
- **`specs/gateway/local-gateway/spec.md`** — os requisitos (o que o sistema deve fazer) e os 10 critérios de aceitação arquitetural.
- **`tasks.md`** — o plano incremental dividido em Changes GATEWAY 01–08.

### Escopo desta proposta (arquitetura)

O que a arquitetura deve provar antes de qualquer feature:

1. **Três modos de operação** — `ONLINE` (Terminal → Gateway → Cloud), `LAN-OFFLINE` (Terminal → Gateway → Caixa), `ISOLADO` (Terminal → IndexedDB/Outbox, comportamento atual preservado).
2. **Fallback em cascata** configurável por operação: `1. Gateway LAN → 2. Cloud → 3. IndexedDB/Outbox local`.
3. **Reuso do modelo de idempotência existente** — `EventId` nasce no terminal, `PayloadHash` sobre payload canônico, `ProcessedEvents` para dedup. A mesma operação atravessa Terminal → Gateway → Cloud como **uma única operação lógica**.
4. **Duas etapas de sincronização independentes** — `Terminal → Gateway` (LOCAL_DELIVERY) e `Gateway → Cloud` (CLOUD_SYNC), com estados distintos (`PENDING_LOCAL`, `DELIVERED_LOCAL`, `PENDING_CLOUD`, `SYNCED_CLOUD`, `FAILED`).
5. **Autenticação LAN** — a LAN é rede **não confiável**; o terminal se registra/autoriza no Gateway (vínculo `Terminal → Store → Gateway → Company`). O Gateway **não** substitui a autenticação de usuário da cloud.
6. **Persistência durável no Gateway** (SQLite) — nenhum evento pode viver só em RAM; recuperável após reboot/queda de energia.
7. **Generalizar a Outbox atual** para suportar `Destination = GATEWAY | CLOUD` em vez de criar um segundo `SyncEngine`.

## Non-goals

Explicitamente **fora** desta proposta e da primeira versão do Gateway:

- **Não** transformar o Gateway em cópia completa do SQL Server cloud (não é "segunda verdade" de estoque, clientes, vendas ou financeiro).
- **Não** implementar emissão fiscal (NFC-e) no Gateway — segue na cloud; contingência fiscal local é projeto separado.
- **Não** simular/confirmar Pix no Gateway — confirmação oficial vem do PSP via webhook na cloud (`QR exibido ≠ pagamento recebido`).
- **Não** implementar impressão pelo Gateway na v1 (deixar a arquitetura preparada).
- **Não** resolver conflito global de estoque no Gateway (o estoque negativo já é característica conhecida do offline-first).
- **Não** alterar nenhum arquivo, banco, API ou teste nesta change — só produzir a proposta.

## Impact

- **Compatibilidade (regra fundamental):** o Gateway não pode quebrar os mecanismos homologados. Preservar `CompanyId`, `EventId`, `PayloadHash`, `ProcessedEvents`, Outbox, `SyncEngine`, `SyncCoordinator`, Web Locks, Storage Lease, IndexedDB, idempotência, precisão monetária, PWA, autenticação offline e isolamento multi-tenant. Os 71 testes de arquitetura (`FRONTEND/tests/architecture/*`) devem continuar verdes.
- **Novo componente:** um projeto `HorusGateway` (ASP.NET Core + SQLite), executado como Windows Service (alvo inicial) / systemd.
- **Frontend:** camada de transporte adicional que decide Gateway → Cloud → Local; a Outbox atual é generalizada, não duplicada.
- **Detalhes de arquivos afetados, entidades, endpoints e migrações** estão em `design.md`.
