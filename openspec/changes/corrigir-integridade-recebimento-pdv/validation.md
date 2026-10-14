# Validação

## Resultados confirmados

- `dotnet build API/NETCORE/HORUSPDV-API.csproj --no-restore`: concluído com 0 avisos e 0 erros.
- `dotnet run --project API/tests/SaleIntegrityChecks --no-restore`: 64 verificações passaram. O harness usou uma instância SQL Server Docker isolada (`horus-integrity-tests-20261014`, `127.0.0.1:14339`, banco `HorusIntegrityChecks`) e não carregou `appsettings` nem configurações reais. Cobriu fixtures pelo repositório real, preservação de precisão unitária na resposta e releitura fiscal, fiado agregado, limite disponível (40/100 e 40/40,01 rejeitados; 40 exato aceito), zero, recebimento misto, pedido, retry, concorrência, rollback após falha de auditoria 51000 e migração de NOT NULL para nullable executada duas vezes, preservando dados existentes.
- `dotnet run --project API/tests/FiscalReviewChecks --no-restore -- E:/PROJETOPDV/horus_pdv/API/NETCORE/bin/Debug/net8.0`: 55 verificações passaram, sem emissão fiscal nem acesso ao banco da loja. O CS1705 original foi resolvido substituindo a referência à DLL da API por `ProjectReference` e restaurando as dependências do harness; o runtime da API não foi migrado.
- `dotnet test GATEWAY/tests/HorusGateway.Tests --no-restore --filter FullyQualifiedName~LocalFiscalComplianceTests`: 18 de 18 testes passaram (baseline de 14 mais 4 novos).
- Frontend: `npm run build` passou nas verificações de tipos e no build de produção do Vite. Houve apenas o aviso `PluginTimings`.
- Os resultados isolados de frontend em `test-results-integrity` são ignorados pelo Git. Os artefatos preexistentes em `test-results` foram restaurados somente para o estado do HEAD `4bad2b2`.

## Verificações finais

- `cd FRONTEND; npx --no-install playwright test --config playwright.integrity.config.ts`: 20/20 aprovados. Exercita helpers, Dexie, crédito offline zero/misto, pendências e replay, falhas de snapshot, corrida de refresh, foco/polling com reconferência, clique duplicado, histórico incompleto e HTML real do recibo. Toda requisição de backend é interceptada; somente o Vite local recebe tráfego real.
- `graphify update .`: a segunda tentativa concluiu por AST sem LLM, com 8.586 nós, 15.398 relações e 642 comunidades. `graph.json`, `graph.html` e `GRAPH_REPORT.md` foram atualizados. A primeira gravação falhou por WinError 32 transitório. A visualização usa comunidades agregadas por superar 5.000 nós; 107 nomes de comunidades foram atualizados pelos respectivos hubs, sem extração semântica paga.
- `openspec validate corrigir-integridade-recebimento-pdv --strict`: válido. `git diff --check`: sem erros. UTF-8 conferido nos helpers novos, sem mojibake.

## Limitações conhecidas

- Não há diagnóstico causal confirmado para o cupom 1245 ou a NFC-e 354 sem acesso ao banco, XML e versão da loja.
- Dados históricos de recebimentos e trocos já retropreenchidos por migração anterior não recuperam a semântica que não foi armazenada.
- O modo offline garante o crédito do cache deste aparelho. A consistência global entre vários caixas depende de reconferência atômica no servidor durante a sincronização.

Nenhum dado real foi publicado, implantado, emitido, cancelado ou alterado.


## Reprodução e rollout

A suíte SQL usa apenas o container de teste criado por este apply. Para reproduzir, inicie `horus-integrity-tests-20261014` (porta local 14339) e execute o harness. O script cria/recria exclusivamente `HorusIntegrityChecks` na conexão literal de teste; não execute contra configurações de loja. O container foi parado ao concluir as verificações, preservando a instância para reprodução.

A publicação futura deve coordenar frontend e API por causa do novo endpoint `api/Cliente/credito-snapshot` e aplicar a migração idempotente 49 pelo inicializador normal. Nenhuma migração foi executada em banco real. Campos recebidos/trocos históricos permanecem intactos. Pedidos continuam exigindo conexão para finalização, conforme política anterior.
