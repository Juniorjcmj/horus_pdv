# Design

## Context

- Motivação e escopo: `proposal.md`. Requisitos: `specs/estoque/{movimentacao-estoque,indicadores-estoque,analise-estoque-desktop}/spec.md`.
- Decisões do usuário: depósito único implícito; registrar movimentação em **toda** mudança de saldo; telas **só** no
  app `ESTOQUE_JAVAFX` (não na web); entrega em 6 etapas, com commit e testes ao fim de cada uma.
- API .NET 8, SQL Server, SQL escrito à mão (`Connection.OpenConnectionAsync` / `OpenLeaseAsync`, `SqlCommand`), envelope
  `ApiResponse{success,message,data}`, roles por `[HorusAuthorizeRoles]` (`HorusRoles.Estoquista` existe), IP via
  `HorusClientIpResolver`. Migrações `.sql` idempotentes **registradas** em `Repositories/HorusDatabaseInitializer.cs`
  (última: `56_inventario`). Jobs: `AddHostedService` (ex.: `NfceOutboxWorker`).
- Pontos que mudam o saldo hoje (todos precisam gravar movimentação, ver D2):
  - `ProdutoAB.EntradaEstoqueAsync` (NF-e: `NfeImportService:310`; manual: `EntradaManualService:116`; pedido de
    compra: `OrdemCompraAB:395`), `ProdutoAB.EstornarEstoqueAsync` (estorno de nota: `NfeImportService:422`),
    `ProdutoAB.BaixarEstoqueAsync` (465), `ProdutoAB.SalvarAsync` (262, cadastro/edição), `ProdutoAB.AjustarEstoqueAsync`
    (907, já grava movimentação via `EstoqueMovimentacaoAB`);
  - `HistoricoVendasAB.BaixarEstoqueAsync` (717) e `BaixarEstoqueComPrecoFixoAsync` (831) — venda;
    `CancelarVendaComSupervisorAsync` (estorno por item); `CorrigirVendaAsync` (1422: devolve e baixa);
  - `DocumentoFiscalAB.MarcarCanceladoComAuditoriaAsync` (estorno ~1016, só quando a venda não estava cancelada) e
    `GravarDevolucaoNfeComAuditoriaAsync` (1280, ~1376);
  - `InventarioAB` aplicação (~631, já grava `inventario`);
  - `LoteAB` só altera validade, não saldo.
- Tabelas: `EstoqueMovimentacoes` (migração 52: Id, CompanyId, ProdutoId, Tipo NVARCHAR(20), Quantidade, EstoqueAnterior,
  EstoqueNovo, Motivo, UsuarioId, UsuarioNome, CriadoEm DATETIME2 UTC); `Produtos` (ProductQnt, ProductUnitPrice=custo,
  ProductSalePrice, CustoMedio, EstoqueMinimo, EstoqueMaximo, CategoriaId, SupplierId); `OrdemCompra` (CreatedAt,
  ReceivedAt, PrevisaoEntrega, Status, SupplierId) + `OrdemCompraItens`; `Fornecedores`; `Vendas`/`VendaItens`;
  `Inventarios`/`InventarioItens`/`InventarioContagens` (migração 56).
- App `ESTOQUE_JAVAFX`: `MainView` com menu lateral e `Tela`; helpers `Ui` (tabelas com colunas responsivas, barras que
  quebram linha), `Zoom`, `Fx.async`, `Toasts`, `Csv`, `PrintService`, `Perfil` (verCustos, relatoriosDeValor), APIs em
  `api/*Api.java` sobre `ApiClient`. Testes JUnit + E2E contra Docker (`-Dquack.e2e.url`), print de telas com
  `UiSnapshotTool`/`TelasE2ETest` (`-Dquack.test.size`, `-Dquack.test.zoom`).
- Docker de teste: `pdv-teste-sql` (14333, `Teste#Caixa2026`, `sqlcmd -C -I`), `pdv-teste-api` (5260) montado de
  contexto limpo (`tar --exclude=bin --exclude=obj API/NETCORE Shared`).

## Goals / Non-Goals

**Goals:**
- Histórico confiável: nenhuma mudança de saldo sem movimentação, mais fotografia diária.
- Números calculados uma vez, no servidor, com fórmulas testadas e iguais em qualquer cliente.
- Telas de análise rápidas no JavaFX (gráficos nativos, carregamento assíncrono por bloco).

**Non-Goals:**
- Cadastro de depósitos, saldo por depósito e transferências (só o identificador preparado).
- Telas web; previsão de demanda por modelo estatístico (só média/desvio); reprocessar histórico anterior à publicação
  além da reconstrução por movimentações (D3).
- Envio automático de pedido ao fornecedor (só rascunho de pedido de compra).

## Decisions

### D1. Etapas (cada uma termina com testes verdes e commit)
1 Dados + movimentações em todos os pontos + snapshot + seed · 2 Cálculos + testes · 3 Dashboard · 4 ABC e Reposição ·
5 Produto, Acuracidade, Perdas, Fornecedores · 6 Configurações. As telas da etapa 3+ consomem os endpoints da etapa 2.

### D2. Registrador único de movimentação
`EstoqueMovimentacaoAB.RegistrarAsync(SqlConnection db, SqlTransaction tx, MovimentoNovo m)` (estático, na transação do
chamador) grava a linha e é o **único** jeito de mexer no saldo: helper `AplicarMovimentoAsync(db, tx, companyId,
produtoId, delta, tipo, custoUnitario, origemTipo, origemId, motivo, usuarioId, usuarioNome)` faz `UPDATE Produtos SET
ProductQnt = ProductQnt + @delta OUTPUT deleted.ProductQnt, inserted.ProductQnt ...` (anterior/novo exatos sob
concorrência) e insere a movimentação. Cada ponto de D-Context passa a usar o helper; métodos sem transação (ex.:
`EntradaEstoqueAsync`) abrem a sua. Quem chama sem usuário (worker) usa `UsuarioNome = "Sistema"`.
Colunas novas em `EstoqueMovimentacoes` (migração 57): `CustoUnitario DECIMAL(18,4) NULL`, `OrigemTipo NVARCHAR(20) NULL`
(`venda|nfce|nfe|nota_entrada|pedido_compra|inventario|ajuste|cadastro|correcao`), `OrigemId NVARCHAR(80) NULL`,
`DepositoId NVARCHAR(40) NOT NULL DEFAULT 'loja'`, e índice `(CompanyId, ProdutoId, CriadoEm)` + `(CompanyId, Tipo,
CriadoEm)` incluindo Quantidade/CustoUnitario. Tipos: `saida_venda` (venda), `devolucao` (cancelamento/devolução de
cliente), `entrada`, `saida` (estorno de nota, edição para menos, saída avulsa), `uso_interno`, `perda`, `inventario`,
`transferencia` (reservado). Movimentações antigas ficam com custo NULL → cálculos usam o custo atual como fallback.
Teste de regressão: um check que roda venda, cancelamento, correção, entrada NF-e, estorno, inventário e edição e
confere `Σ movimentações = saldo final − saldo inicial` por produto.

### D3. Fotografia diária e reconstrução
Tabela `EstoqueSnapshotDiario(CompanyId, DepositoId, Data DATE, ProdutoId, Quantidade DECIMAL(18,3), Valor
DECIMAL(18,2), CustoUnitario DECIMAL(18,4), PK(CompanyId, DepositoId, Data, ProdutoId))`. `EstoqueSnapshotWorker`
(`BackgroundService`) acorda a cada 30 min; se não existe o snapshot de ontem (dia de Brasília) para uma empresa ativa,
grava com `MERGE` (idempotente): quantidade de ontem = saldo atual − Σ movimentações com `CriadoEm` após o fim de ontem
(UTC convertido). Mesmo mecanismo serve o endpoint `POST api/Estoque/analise/snapshot?data=` (gerente/admin) e o seed.
No mesmo ciclo recalcula `ProdutoClassificacao(CompanyId, ProdutoId, Abc CHAR(1), Fsn CHAR(1), CalculadoEm)`.

### D4. Configurações
`EstoqueConfigAnalise(CompanyId PK, JanelaConsumoDias, NivelServicoZ, AbcLimiteA, AbcLimiteB, AbcBase 'faturamento'|'cmv',
FsnLimiteFast, DiasParado, DiasExcesso, CoberturaAlvoDias, LeadTimePadraoDias, ContagemDiasA/B/C, ContagemItensDia,
MetasJson NVARCHAR(MAX), AtualizadoPor/Em)`. Ausência de linha = padrões do spec. `Produtos.LeadTimeDias INT NULL` e
`Fornecedores.LeadTimePrometidoDias INT NULL`.

### D5. Cálculo: SQL para agregar, C# para fórmula
`EstoqueIndicadoresAB` faz as agregações por produto num conjunto de CTEs parametrizadas (período, categoria com filhas,
fornecedor): demanda diária (de `EstoqueMovimentacoes` `saida_venda` − `devolucao` agrupada por dia de Brasília, com
calendário gerado por `GENERATE_SERIES` não disponível no SQL Server 2019 → tabela numérica inline `TOP (n) ROW_NUMBER()
OVER`), média e desvio populacional (com os dias zero), CMV, estoque médio dos snapshots, dias em ruptura, última saída,
pedidos em trânsito (`OrdemCompraItens` de pedidos abertos), contagens aplicadas. `EstoqueCalculos` (classe estática C#,
sem SQL) contém as fórmulas puras do spec (segurança, ponto de pedido, alvo, sugestão com arredondamento para cima em
unidade inteira e para 3 casas em KG/fracionado, cobertura com "sem giro", DIO, excesso, ABC, FSN, acuracidade) — é o que
os testes unitários exercitam. **Alternativa rejeitada:** tudo em C# carregando movimentações (volume de 12 meses ×
200+ SKUs × N lojas).

### D6. Endpoints (`EstoqueAnaliseController`, `api/Estoque/analise`)
`GET kpis`, `GET serie-estoque`, `GET serie-ruptura`, `GET cobertura-categorias`, `GET top-giro`, `GET top-parados`,
`GET abc-valor` (rosca), `GET abc` (pareto+tabela), `GET abc-fsn`, `GET reposicao`, `POST reposicao/pedidos`
(rascunhos por fornecedor via `OrdemCompraAB`), `GET produto/{id}` (KPIs+séries+histograma), `GET produto/{id}/extrato`
(paginado), `GET contagem-ciclica` (lista do dia), `GET acuracidade`, `GET divergencias`, `GET perdas`, `GET parados`,
`GET fornecedores`, `GET/PUT config`, `POST snapshot`. Query comum: `de, ate, categoriaId, fornecedorId, depositoId,
comparar`. Cache: `IMemoryCache` 5 min por (empresa, rota, filtros), invalidado ao salvar config; resposta inclui
`calculadoEm`. Roles: leitura administrador/gerente/estoquista; campos em R$ removidos para estoquista (mesmo padrão de
`EstoqueRelatorioController`); escrita (config, pedidos, snapshot) só administrador/gerente. Contagem cíclica aplica
via rotas do inventário já existentes (escopo `produtos`, justificativa "Contagem cíclica").

### D7. Telas JavaFX
Novo grupo de menu "Análise" em `MainView` com 8 telas (`analise/*View.java`), filtros num componente
`FiltrosAnalise` (período com atalhos, comparação, categoria, fornecedor; depósito oculto com 1 depósito; últimos
filtros em `%APPDATA%\QuackEstoque\analise.json`). Gráficos com `javafx.scene.chart`: `LineChart` (séries no tempo,
com linhas de referência desenhadas por um `ReferenceLines` overlay), `BarChart` horizontal/vertical, `StackedBarChart`
(perdas), `PieChart` com buraco (rosca via `Circle` sobreposto), histograma (`BarChart` de faixas). Pareto = `BarChart`
com `LineChart` sobreposto (StackPane, eixo Y direito 0–100% e linhas em 80/95%). Tooltips via `Tooltip.install` nos
nós de dados (`Data.getNode()`), formatados por `Fmt` (pt-BR). Cards KPI num `TilePane`/`FlowPane` que reorganiza
conforme a largura; cor do semáforo por classe CSS `rg-kpi-verde|amarelo|vermelho`. Cada bloco carrega com `Fx.async`
independente e mostra esqueleto/"Carregando…" e estado vazio. Exportação Excel: `XlsxWriter` próprio (zip com
`[Content_Types].xml`, `xl/workbook.xml`, `xl/worksheets/sheet1.xml`, `sharedStrings`), sem dependência nova; CSV
pelo `Csv` existente.

### D8. Dados de demonstração
`API/tests/EstoqueAnaliseSeed` (console) gera numa empresa `demo-analise` do banco de teste: 200 produtos em 10
categorias e 12 fornecedores (lead times prometidos e reais diferentes), 12 meses de vendas diárias com sazonalidade
(fim de semana, dezembro), entradas por pedido de compra, perdas, 15 parados, rupturas, 6 inventários/contagens, e os
snapshots diários reconstruídos (D3). Grava via SQL direto (rápido) mas usando `AplicarMovimentoAsync` para manter a
regra. Usuários `gerente.demo@teste.com`/`Demo@12345` e `estoquista.demo@teste.com`/`Demo@12345`. Nunca roda em
produção (exige `--connection` explícita e recusa host que não seja localhost).

### D9. Testes
- `API/tests/EstoqueIndicadoresChecks`: (a) fórmulas puras de `EstoqueCalculos` com os exemplos numéricos do spec e
  bordas (consumo zero, sem histórico, saldo negativo, lead time ausente, desvio com dias zero); (b) integração contra o
  SQL de teste: regra "Σ movimentações = variação do saldo" em todos os fluxos de D2, snapshot idempotente e reconstrução,
  agregações por endpoint com dados controlados.
- App: JUnit para formatação de tooltips, semáforo, XlsxWriter (abre e lê de volta com `java.util.zip`), filtros; E2E
  contra a empresa demo; prints das 8 telas em 1366×690 (zoom 0,85) e 1024×680.

## Risks / Trade-offs

- [Venda fica um pouco mais lenta (1 INSERT por item)] → mesma transação, índice adequado; medir no check com 50 itens.
- [Esquecer algum caminho que mexe no saldo] → check de conciliação (D2) e `grep "ProductQnt ="` no review da etapa 1;
  qualquer `UPDATE Produtos SET ProductQnt` fora do helper é bug.
- [Histórico real só a partir da publicação] → telas mostram estado vazio explicativo; reconstrução por movimentações
  vale só a partir da etapa 1 publicada.
- [Desvio/sugestão instáveis com pouco histórico] → exibir "histórico curto" quando a janela tiver menos de 14 dias de
  dados e não sugerir compra só pelo desvio.
- [Gráficos JavaFX com muitos pontos (365 dias × séries)] → agregação semanal acima de 120 pontos; Pareto limitado aos
  100 primeiros SKUs com o restante agrupado em "Outros".

## Migration Plan

1. Publicar a API com a migração 57 (aditiva) e o worker. Movimentações antigas ficam como estão (custo NULL).
2. Distribuir o app de estoque com o menu "Análise".
3. Rollback: reverter a API; colunas/tabelas novas são ignoradas pela versão anterior; vendas continuam funcionando.

## Open Questions

- Metas padrão do semáforo além das do spec (ex.: giro, cobertura) — usar valores conservadores e ajustar na tela de
  configurações; não altera specs nem tarefas.
