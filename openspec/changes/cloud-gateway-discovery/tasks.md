# Tasks: Descoberta do Local Gateway via Cloud (endereço por loja)

> Cada etapa mantém os **71 testes de arquitetura** e os testes do Gateway verdes antes de prosseguir.
> As mudanças na API/Cloud são **aditivas** e só começam com aprovação explícita do usuário.

## CLOUD-DISC 01 — Persistência do endereço na Cloud (SQL + repositório)
- [ ] Script `API/NETCORE/scripts/NN_loja_gateway_config.sql` (idempotente) criando `LojaGatewayConfig`
      (`CompanyId`, `StoreId`, `GatewayUrl`, `Enabled`, `UpdatedAt`, `UpdatedBy`) sem alterar tabelas existentes
- [ ] Registrar o script no `HorusDatabaseInitializer` (mesmo padrão dos scripts 20/21)
- [ ] Novo repositório `LojaGatewayConfigAB.cs` (ADO.NET): `GetByStore`, `Upsert`, `Disable` — isolado
- [ ] Validação de URL (esquema http/https, host, porta) no upsert
- [ ] Isolamento multi-tenant em toda consulta/escrita (`CompanyId`+`StoreId`)

## CLOUD-DISC 02 — Endpoints (Cloud, aditivos)
- [ ] `GET /api/gateway-config` — terminal autenticado recebe o endereço da **sua** loja (nunca por parâmetro livre)
- [ ] `PUT /api/admin/gateway-config` — admin define/edita (perfil admin + validação de URL)
- [ ] Desabilitar via `Enabled=false` (admin) → terminais param de usar o Gateway
- [ ] Nenhum endpoint existente alterado; contratos novos documentados
- [ ] Testes de API: leitura por loja, escrita admin, cross-tenant negado, URL inválida rejeitada

## CLOUD-DISC 03 — Painel admin (frontend Cloud)
- [ ] Tela/campo (área Admin) para cadastrar/editar/desabilitar o endereço do Gateway por loja
- [ ] Aceita **host + porta + esquema https** (viabiliza caminho sem mixed content), além de IP
- [ ] Feedback de validação e estado (habilitado/desabilitado, última atualização)
- [ ] Não altera telas homologadas de venda/caixa

## CLOUD-DISC 04 — Aprendizado automático no terminal (aditivo, zero config)
- [ ] `src/infrastructure/gateway/cloudGatewayProvisioning.ts` (novo): `learnGatewayFromCloud()` chama
      `GET /api/gateway-config` via `apiClient` e faz `saveGatewayConfig({...})` em sucesso; silencioso em falha
- [ ] Gancho mínimo no bootstrap/reconexão (ao ficar online) — sem tocar o fluxo de vendas/caixa
- [ ] `gatewayConfig`: campo `updatedAt`/TTL aditivo (merge com `EMPTY`, sem quebrar shape) + revalidação
- [ ] Precedência: endereço da Cloud > URL manual; URL manual como fallback
- [ ] `Enabled=false` na Cloud → terminal limpa/desliga o uso do Gateway
- [ ] Degradação: sem Cloud e sem cache → modo ISOLADO atual, sem erro visível

## CLOUD-DISC 05 — Transporte HTTPS na LAN (mixed content) + documentação
- [ ] Gateway: suportar escutar HTTPS (ex.: `:5443`) com certificado para um host (`quack-gateway.local`/host interno)
- [ ] README do Gateway: procedimento de host + certificado (mDNS/DNS/DHCP reservation) e alternativas (IP/exceção)
- [ ] Validar ponta-a-ponta: terminal HTTPS → Gateway HTTPS por host, sem bloqueio de mixed content

## Validação transversal (todas as etapas)
- [ ] Rodar `FRONTEND/tests/architecture/*` — 71/71 verdes
- [ ] Rodar testes do Gateway — verdes
- [ ] Provar AC-1..AC-8 do `spec.md`
- [ ] Confirmar preservação: em condição normal, terminal fala direto com a Cloud; vendas/caixa não roteiam pelo Gateway
