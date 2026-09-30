# Tasks: Descoberta do Local Gateway via Cloud (endereço por loja)

> Cada etapa mantém os **71 testes de arquitetura** e os testes do Gateway verdes antes de prosseguir.
> As mudanças na API/Cloud são **aditivas** e só começam com aprovação explícita do usuário.

## CLOUD-DISC 01 — Persistência do endereço na Cloud (SQL + repositório) ✅
- [x] Script `DataBase/Migrations/25_loja_gateway_config.sql` (idempotente) criando `LojaGatewayConfig`
      (`CompanyId` PK, `StoreId`, `GatewayUrl`, `Enabled`, `UpdatedAt`, `UpdatedBy`) sem alterar tabelas existentes
- [x] Registrar o script no `HorusDatabaseInitializer` (mesmo padrão dos scripts 20/21)
- [x] Novo repositório `LojaGatewayConfigAB.cs` (ADO.NET): `GetByCompanyAsync`, `UpsertAsync` — isolado
- [x] Validação de URL (esquema http/https, host, porta) no controller (upsert)
- [x] Isolamento multi-tenant em toda consulta/escrita (`CompanyId` — unidade de tenant da nuvem)

## CLOUD-DISC 02 — Endpoints (Cloud, aditivos) ✅
- [x] `GET /api/gateway-config` — terminal autenticado recebe o endereço da **sua** empresa (CompanyId do token)
- [x] `PUT /api/gateway-config` — admin/gerente define/edita (perfil restrito no método + validação de URL)
- [x] Desabilitar via `Enabled=false` (admin) → terminais param de usar o Gateway
- [x] Nenhum endpoint existente alterado; controller/rota novos (`api/gateway-config`)
- [x] Build da API verde (0 erros). Testes de isolamento cross-tenant: validados no stack local (etapa transversal)

## CLOUD-DISC 03 — Painel admin (frontend Cloud) ✅
- [x] Card `GatewaySettingsCard` (Configurações) para cadastrar/editar/desabilitar o endereço do Gateway
- [x] Aceita **host + porta + esquema https** (validação `new URL`) além de IP
- [x] Feedback de validação e estado (ativo/inativo, última atualização) + Toast
- [x] Visível só para admin/gerente; não altera telas homologadas de venda/caixa

## CLOUD-DISC 04 — Aprendizado automático no terminal (aditivo, zero config) ✅
- [x] `src/infrastructure/gateway/cloudGatewayProvisioning.ts`: `learnGatewayFromCloud()` chama
      `GET /api/gateway-config` (via `gatewayConfigService`/`apiClient`) e `saveGatewayConfig` em sucesso; silencioso em falha
- [x] Gancho mínimo em `App.tsx` (`startCloudGatewayProvisioning`) junto do sync, só autenticado — sem tocar vendas/caixa
- [x] `gatewayConfig`: campo `updatedAt` aditivo (merge com `EMPTY`) + revalidação ao ficar online
- [x] Precedência: endereço da Cloud > URL manual; URL manual como fallback (não sobrescreve quando Cloud é nula)
- [x] `enabled=false` na Cloud → terminal desliga o uso do Gateway (`isGatewayConfigured` checa `enabled`)
- [x] Degradação: sem Cloud e sem cache → modo ISOLADO atual, sem erro visível

## CLOUD-DISC 05 — Transporte HTTPS na LAN (mixed content) + documentação ✅
- [x] Scripts `run-gateway.sh`/`.ps1` opt-in a HTTPS via `GATEWAY_HTTPS_CERT` (Kestrel) — HTTP como padrão
- [x] README do Gateway: seção "Descoberta via Cloud + HTTPS na LAN" (host + certificado, mDNS/DNS/DHCP) e alternativa IP
- [x] Modelo/cliente aceitam host+porta+https ponta a ponta (validação no controller e no card)

## Validação transversal (todas as etapas)
- [ ] Rodar `FRONTEND/tests/architecture/*` — 71/71 verdes
- [ ] Rodar testes do Gateway — verdes
- [ ] Provar AC-1..AC-8 do `spec.md`
- [ ] Confirmar preservação: em condição normal, terminal fala direto com a Cloud; vendas/caixa não roteiam pelo Gateway
