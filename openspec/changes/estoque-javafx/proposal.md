# Proposal

## Why

O controle de estoque hoje só existe espalhado pela web (Estoque, Compras, Cadastro de Produtos, Validade), pensado
para o escritório. Quem trabalha no depósito e na loja (estoquista, gerente) precisa de um aplicativo leve, rápido
com leitor de código de barras e impressora térmica, igual ao caixa JavaFX — e falta a operação mais importante do
estoque: o **inventário** (contar a prateleira e o sistema corrigir a diferença com autorização e trilha).

## What Changes

- **Novo aplicativo desktop `ESTOQUE_JAVAFX/`** (Java 21 + JavaFX 21, mesma base do `CAIXA_JAVAFX`: login, tokens
  visuais Quack, fontes embutidas, modais centralizados, executável Windows com Java embutido). Online (sem modo
  offline). Perfis: administrador, gerente e estoquista (o estoquista não vê custo/margem nem cadastra fornecedor).
- **Entrada de mercadorias**: importação de NF-e por XML ou pela chave de acesso (SEFAZ), com conferência item a
  item, vínculo ao produto, conversão de unidade, lote/validade e atualização opcional do preço de venda; entrada
  manual (cupom, recibo, sem nota); consulta e estorno de notas de entrada. Usa as rotas já existentes
  (`/api/NfeImport/*`, `/api/Produto/entrada-manual`).
- **Baixas e ajustes avulsos**: entrada, saída, uso interno e perda por produto, com motivo, e histórico de
  movimentações (rotas existentes `/api/Produto/{id}/ajuste-estoque` e `/movimentacoes`).
- **Inventário (novo na API)**: abrir um inventário (loja toda, por departamento/categoria ou lista de produtos),
  contar com leitor de código de barras (inclusive etiqueta de balança) ou digitando, várias pessoas contando ao
  mesmo tempo, recontagem, relatório de divergências (quantidade e valor) e **aplicação do acerto com senha do
  gerente**. O acerto respeita as vendas feitas durante a contagem e grava trilha de auditoria append-only (quem
  contou, quem aprovou, antes/depois, valor da diferença).
- **Cadastro de produtos**: busca rápida, criação/edição (código, GTIN, nome, unidade, preço de custo e de venda,
  estoque mínimo/máximo, categoria, fornecedor, controle de validade), alteração rápida de preço; cadastro de
  categorias e fornecedores (gerente/administrador). Rotas existentes.
- **Etiquetas de gôndola**: fila de impressão (produtos e cópias, inclusive a partir de uma nota de entrada ou de
  produtos com preço alterado), modelos por tamanho de etiqueta (preço em destaque, nome, unidade, código de barras
  EAN-13/Code128), impressão na térmica pelo driver do Windows (mesma técnica do cupom do caixa) e configuração de
  tamanho/margens salva no terminal.
- **Relatórios para compra** (novos endpoints de relatório na API): sugestão de reposição por giro de vendas e
  estoque mínimo/máximo que vira **pedido de compra** por fornecedor; curva ABC por faturamento; estoque crítico
  (zerado/abaixo do mínimo) e parado (sem venda há X dias); validade (lotes a vencer/vencidos) e perdas no período.
  Exportação CSV e impressão.

## Capabilities

### New Capabilities
- `estoque/inventario`: ciclo de vida do inventário (abrir, contar, recontar, divergências, aplicar acerto com
  autorização do gerente, cancelar) e sua trilha de auditoria, na API e no app.
- `estoque/estoque-desktop`: o aplicativo desktop de estoque — acesso por perfil, entrada de mercadorias (NF-e e
  manual), baixas/ajustes, movimentações e cadastro de produtos/categorias/fornecedores pelo app.
- `estoque/etiquetas-gondola`: montagem da fila de etiquetas, modelos por tamanho e impressão pela térmica via
  driver do Windows.
- `estoque/relatorios-compra`: relatórios de apoio à compra (reposição por giro, curva ABC, crítico e parado,
  validade e perdas) e a geração de pedido de compra a partir da sugestão.

### Modified Capabilities
<!-- Nenhuma: controle de validade, categorias e o ajuste de estoque existentes são consumidos sem mudar seus
     requisitos. -->

## Impact

- **Novo módulo** `ESTOQUE_JAVAFX/` (Maven, offline como o caixa; reaproveita por cópia as peças do `CAIXA_JAVAFX`:
  `ApiClient`, login, `Fx`, CSS, fontes, `ReceiptPrinter` para impressão ajustada à térmica, ZXing). Executável
  `QuackEstoque.exe` via `build-exe.ps1`.
- **API (.NET)**: novo `InventarioController` + `InventarioAB` + migração `56_inventario.sql` (tabelas
  `Inventarios`, `InventarioItens`, `InventarioContagens`, registro na `HorusDatabaseInitializer`); novo tipo de
  movimentação `inventario` em `EstoqueMovimentacoes`; novos relatórios em `RelatorioAB`/endpoint de compras
  (`curva-abc`, `estoque-parado`, `reposicao-giro`, `perdas`). Rotas existentes não mudam de contrato.
- **Web**: opcionalmente lista os novos relatórios em `reportsConfig.ts` (mesmos ids do servidor).
- **Dependências**: nenhuma nova além das já usadas no caixa (JavaFX 21.0.8, Jackson, ZXing, JUnit).
- **Deploy**: a API precisa ser publicada (migração 56 roda no boot).
