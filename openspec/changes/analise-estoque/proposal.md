# Proposal

## Why

O estoque hoje responde "quanto tenho", mas não "como estou": não há giro, cobertura, ruptura ao longo do tempo,
acuracidade, estoque de segurança nem lead time real por fornecedor. Esses números exigem histórico confiável — e hoje
só os ajustes manuais e o inventário gravam movimentação; venda, entrada por nota, cancelamento, correção e estorno
alteram o saldo sem deixar rastro, e não existe fotografia diária do estoque. Sem isso, qualquer indicador sai errado.

## What Changes

- **Toda mudança de saldo passa a gravar movimentação** em `EstoqueMovimentacoes`, com tipo, custo unitário e
  documento de origem: venda (inclusive pedido com preço fixo), cancelamento de venda e de NFC-e/NF-e, correção de
  venda, devolução (NF-e de entrada), entrada por NF-e, entrada manual, recebimento de pedido de compra, estorno de nota
  de entrada, ajuste avulso, inventário e cadastro/edição de produto que mude a quantidade. Tipos novos: `saida_venda`,
  `devolucao`, `transferencia` (reservado), além dos atuais (`entrada`, `saida`, `uso_interno`, `perda`,
  `inventario`). **BREAKING (dados)**: relatórios que somam "saídas" passam a ver as vendas como movimentação; os que
  filtram por tipo continuam iguais.
- **Fotografia diária do estoque** (job no servidor, 1x por dia e sob demanda): quantidade e valor por produto, base do
  histórico, da ruptura por dia e do estoque médio. Recalcula ABC/FSN no mesmo job.
- **Prazo de entrega (lead time)** no produto (dias) e no fornecedor (prometido), e lead time real calculado pelos
  pedidos de compra recebidos.
- **Configurações de análise por empresa**: janela de consumo, nível de serviço (Z), limites ABC, dias de "parado" e
  de "excesso", metas do semáforo de cada KPI e periodicidade de contagem por classe.
- **Serviço de indicadores (API)** com as fórmulas da especificação (consumo médio, desvio da demanda, estoque médio,
  giro, cobertura, DIO, estoque de segurança, ponto de pedido, sugestão de compra, ruptura, acuracidade, valor em
  estoque, parado, excesso, perdas, ABC, FSN, lead time real), parametrizável por período, categoria, fornecedor e
  depósito (depósito único implícito "Loja", filtro preparado), agregação no SQL, um endpoint por bloco de tela com
  cache curto e testes de cada fórmula.
- **Telas no app de estoque JavaFX** (somente nele), com gráficos nativos do JavaFX: Dashboard geral, Curva ABC
  (Pareto + matriz ABC×FSN), Reposição/Mapa de risco (com rascunho de pedido por fornecedor e CSV/Excel), Detalhe do
  produto, Acuracidade e contagem cíclica, Perdas e parados, Fornecedores e Configurações. Tooltips e números em pt-BR,
  estado vazio amigável, cards e gráficos que se reorganizam em janelas estreitas.
- **Dados de demonstração** (~200 SKUs, 12 meses, sazonalidade, parados e rupturas) gerados só numa empresa de
  demonstração do ambiente de teste, por script/endpoint de desenvolvimento desligado em produção.

## Capabilities

### New Capabilities
- `estoque/movimentacao-estoque`: regra de que todo saldo muda por movimentação registrada (tipos, custo, origem,
  quem e quando) e a fotografia diária do estoque.
- `estoque/indicadores-estoque`: fórmulas e regras dos indicadores de estoque, configurações de análise por empresa,
  classificação ABC/FSN, lead time real e os endpoints que entregam esses números.
- `estoque/analise-estoque-desktop`: telas de análise no app de estoque JavaFX (dashboard, ABC, reposição, produto,
  acuracidade/contagem cíclica, perdas e parados, fornecedores, configurações).

### Modified Capabilities
<!-- Nenhuma capacidade já arquivada muda de requisito. A change estoque-javafx (ainda não arquivada) não é alterada:
     suas telas e relatórios continuam; este módulo acrescenta telas e endpoints novos. -->

## Impact

- **API (.NET 8, SQL direto)**: migração `57_analise_estoque.sql` (colunas novas em `EstoqueMovimentacoes`,
  `EstoqueSnapshotDiario`, `EstoqueConfigAnalise`, `Produtos.LeadTimeDias`, `Fornecedores.LeadTimePrometidoDias`,
  `ProdutoClassificacao` ABC/FSN), registrada no `HorusDatabaseInitializer`; um registrador único de movimentação usado
  por `ProdutoAB`, `HistoricoVendasAB`, `DocumentoFiscalAB`, `InventarioAB`, `NfeImportService`,
  `EntradaManualService`, `OrdemCompraAB`, `ProdutoService`; job `EstoqueSnapshotWorker`; `EstoqueIndicadoresAB`/
  `EstoqueIndicadoresService` + `EstoqueAnaliseController` (`api/Estoque/analise/*`); projeto de testes
  `API/tests/EstoqueIndicadoresChecks`.
- **App `ESTOQUE_JAVAFX`**: novo item de menu "Análise" com 8 telas, cliente `AnaliseApi`, componentes de gráfico
  (Pareto, linha com faixas de referência, histograma) sobre `javafx.scene.chart`.
- **Web**: sem telas novas (decisão do usuário).
- **Desempenho**: venda grava uma linha a mais por item (mesma transação); índices novos nas tabelas de análise.
- **Deploy**: publicar a API (migração 57 e o job); o histórico real começa a se formar a partir da publicação.
