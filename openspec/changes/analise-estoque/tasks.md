# Tasks

Referência obrigatória: `design.md` (D1–D9) e `specs/estoque/*`. API: `cd API/NETCORE && dotnet build -c Release`.
App: `mvn -o -f ESTOQUE_JAVAFX/pom.xml test`. Ambiente Docker conforme `design.md` (Context). **Ao fim de cada etapa
(grupos 1–6): rodar os testes da etapa, fazer commit na `main` com mensagem descritiva e resumir o que mudou e como
acessar a tela.**

## 1. Etapa 1 — Dados, movimentação em todo saldo, fotografia diária e dados de demonstração

- [x] 1.1 Migração `57_analise_estoque.sql` (idempotente) com colunas novas de `EstoqueMovimentacoes`, `EstoqueSnapshotDiario`, `EstoqueConfigAnalise`, `ProdutoClassificacao`, `Produtos.LeadTimeDias`, `Fornecedores.LeadTimePrometidoDias` e índices (D2–D4), registrada em `HorusDatabaseInitializer`. Verificar: API sobe no Docker e `sqlcmd -C -I` lista tabelas/colunas.
- [x] 1.2 `EstoqueMovimentacaoAB.AplicarMovimentoAsync`/`RegistrarAsync` (D2) com `OUTPUT` para anterior/novo e custo; atualizar `AjustarEstoqueAsync` para usar o helper. Verificar: build e check unitário do helper (saldo e linha gravados na mesma transação; rollback desfaz os dois).
- [x] 1.3 Passar pelo helper: venda (`HistoricoVendasAB.BaixarEstoqueAsync`, `BaixarEstoqueComPrecoFixoAsync`), cancelamento de venda, correção de venda, cancelamento NFC-e/NF-e (`MarcarCanceladoComAuditoriaAsync`), devolução NF-e (`GravarDevolucaoNfeComAuditoriaAsync`), entrada (`EntradaEstoqueAsync` — NF-e, manual, pedido de compra), estorno de nota (`EstornarEstoqueAsync`), baixa avulsa (`ProdutoAB.BaixarEstoqueAsync`), inventário (`InventarioAB`) e cadastro/edição (`SalvarAsync`) com tipos/origens de D2. Verificar: `grep -rn "ProductQnt = \|ProductQnt=" API/NETCORE --include=*.cs` só mostra o helper e leituras.
- [x] 1.4 `API/tests/EstoqueIndicadoresChecks` (console, padrão dos `*Checks`, conexão por `--connection`/env, padrão o `pdv-teste-sql`) com o check de conciliação: executa cada fluxo de 1.3 e confere `Σ movimentações = saldo final − inicial` por produto e os tipos/origens. Verificar: `dotnet run --project API/tests/EstoqueIndicadoresChecks` passa.
- [x] 1.5 `EstoqueSnapshotWorker` + `POST api/Estoque/analise/snapshot` (gerente/admin) com MERGE idempotente e reconstrução (D3), registrado no `Program.cs`. Verificar: check grava duas vezes o mesmo dia sem duplicar e reconstrói dia passado conferindo com saldo esperado.
- [x] 1.6 Seed `API/tests/EstoqueAnaliseSeed` (D8) e documentação de uso em `ESTOQUE_JAVAFX/README.md` (seção Análise). Verificar: roda contra o Docker, cria a empresa demo com ~200 SKUs, 12 meses e snapshots; recusa conexão que não seja localhost.
- [x] 1.7 Rodar os testes existentes do caixa (`mvn -o -f CAIXA_JAVAFX/pom.xml test` com E2E `-Dquack.e2e.url`) e do estoque para garantir que venda/cancelamento/inventário continuam iguais; commit da etapa 1.

## 2. Etapa 2 — Serviço de cálculos e testes

- [x] 2.1 `EstoqueCalculos` (fórmulas puras de D5) com os exemplos numéricos do spec e bordas em `EstoqueIndicadoresChecks` (consumo zero → "sem giro", sem histórico, saldo negativo, lead time ausente → fallback, desvio com dias zero, arredondamento por unidade). Verificar: checks passam.
- [x] 2.2 `EstoqueIndicadoresAB`: CTEs de agregação por produto (demanda diária com calendário, média/desvio, CMV, estoque médio, dias de ruptura, última saída, em trânsito, contagens) com filtros período/categoria (com filhas)/fornecedor/depósito e comparação com período anterior. Verificar: checks de integração com dados controlados (demanda conhecida → média e desvio esperados).
- [x] 2.3 `EstoqueConfigAnaliseAB` (get com padrões, put) e `ProdutoClassificacao` (ABC por faturamento/CMV com limites, FSN por semanas com saída) recalculados no worker. Verificar: checks (sem configuração → padrões; matriz 3×3 com contagens esperadas).
- [x] 2.4 Lead time real por fornecedor (média, desvio, nº pedidos, % no prazo) e fallback do lead time do produto. Verificar: check com pedidos de datas conhecidas.
- [x] 2.5 Commit da etapa 2 com resumo dos números conferidos.

## 3. Etapa 3 — Dashboard geral

- [ ] 3.1 `EstoqueAnaliseController` com `kpis`, `serie-estoque`, `serie-ruptura`, `cobertura-categorias`, `top-giro`, `top-parados`, `abc-valor`, cache de 5 min e remoção de R$ para estoquista (D6). Verificar: `dotnet build` e E2E/curl contra a empresa demo (estoquista sem campos de valor; perfil caixa → 403).
- [ ] 3.2 App: `AnaliseApi`, menu "Análise", `FiltrosAnalise` (atalhos, comparação, categoria, fornecedor, memória no terminal) e componentes de gráfico (linha com referências, barras, rosca, tooltips pt-BR). Verificar: JUnit de formatação de tooltip e de persistência dos filtros.
- [ ] 3.3 Tela Dashboard: cards com variação e semáforo, 6 gráficos, carregamento por bloco e estado vazio. Verificar: E2E contra a demo confere os números dos cards com a API; prints em 1366×690 (zoom 0,85) e 1024×680 revisados.
- [ ] 3.4 Commit da etapa 3; resumo com como acessar (menu Análise → Dashboard).

## 4. Etapa 4 — Curva ABC e Reposição

- [ ] 4.1 API `abc`, `abc-fsn`, `reposicao`, `POST reposicao/pedidos`. Verificar: E2E — pedidos gerados aparecem em `GET /api/OrdemCompra` por fornecedor.
- [ ] 4.2 Tela Curva ABC: Pareto (barras + linha acumulada, marcas 80/95%, top 100 + "Outros"), tabela e matriz ABC×FSN clicável. Verificar: E2E clica na célula e a tabela filtra; prints.
- [ ] 4.3 Tela Reposição: cards, tabela por urgência com ruptura destacada, seleção → rascunhos por fornecedor (quantidades editáveis), CSV e Excel (`XlsxWriter`). Verificar: `XlsxWriterTest` (lê o zip de volta e confere células) e E2E gerando pedidos; prints.
- [ ] 4.4 Commit da etapa 4.

## 5. Etapa 5 — Produto, Acuracidade, Perdas e parados, Fornecedores

- [ ] 5.1 API `produto/{id}`, `produto/{id}/extrato`, `contagem-ciclica`, `acuracidade`, `divergencias`, `perdas`, `parados`, `fornecedores`. Verificar: checks/E2E com a demo.
- [ ] 5.2 Tela Detalhe do produto (KPIs, saldo diário com mínimo/ponto de pedido/máximo, entradas × saídas por semana, histograma, extrato paginado; abre por duplo clique nas tabelas da análise). Verificar: E2E abre pelo top parados; prints.
- [ ] 5.3 Tela Acuracidade e contagem cíclica (lista do dia por prioridade A/B/C, campos grandes para bipar/digitar, aplicar como inventário com senha do gerente, linha de acuracidade com meta 98%, divergências). Verificar: E2E gera lista, conta e aplica; acuracidade nova aparece no gráfico.
- [ ] 5.4 Telas Perdas e parados (barras empilhadas por motivo/mês com alternância qtd/R$, tabela de parados) e Fornecedores (prometido × real, tabela). Verificar: E2E confere totais com a API; prints.
- [ ] 5.5 Commit da etapa 5.

## 6. Etapa 6 — Configurações e entrega

- [ ] 6.1 API `GET/PUT config` (gerente/admin escrevem; invalida cache) e tela Configurações com padrões visíveis e "restaurar padrão" (estoquista só leitura). Verificar: E2E muda dias de parado 90→60 e o dashboard/perdas refletem.
- [ ] 6.2 Integração final: checks da API, `mvn test` do estoque e do caixa com E2E, prints de todas as telas de análise nos dois tamanhos e como estoquista, `build-exe.ps1`. Verificar: tudo verde; zip gerado.
- [ ] 6.3 README do estoque (seção Análise: telas, fórmulas, configurações, seed) e memória do projeto atualizados; `graphify update .`. Commit da etapa 6.

## Workflow follow-up

- Publicar a API (migração 57 e worker) antes de distribuir o app com o menu Análise.
- Arquivar a change (`/opsx:archive analise-estoque`) após revisão.
