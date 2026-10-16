## Why

O sistema não oferece uma visão consolidada dos dados fiscais e contábeis dos produtos com movimentação. Para conferência fiscal, apuração de impostos e análise de custos, o operador precisa abrir produto por produto. Um relatório que cruze cadastro fiscal com movimentação real (vendas e entradas) em um período economiza horas de trabalho e reduz erros de apuração.

## What Changes

- Novo relatório **"Ficha Fiscal e Contábil"** (`ficha-fiscal-produtos`) no catálogo de relatórios existente.
- Para cada produto com movimentação no período selecionado, exibe:
  - **Dados fiscais**: NCM, CEST, CFOP, Origem, GTIN, Unidades, CSOSN/CST ICMS, Alíquota ICMS, CST PIS, CST COFINS, CST IBS/CBS, Classificação Tributária.
  - **Dados contábeis**: Custo unitário, Preço de venda, Custo médio, Custo c/ e s/ imposto, Margem desejada, Markups, Lucro unitário, Estoque atual, Valor em estoque.
  - **Movimentação no período**: Quantidade vendida, Faturamento total, Quantidade entrada (notas), Custo total de entradas.
- Filtros: período (obrigatório), departamento/categoria, busca por código ou nome.
- Restrito a administrador e gerente (atendente não vê dados fiscais).
- Produtos sem movimentação no período **não aparecem** — o foco é nos produtos que tiveram giro.

## Capabilities

### New Capabilities
- `relatorios/ficha-fiscal-produtos`: Relatório que consolida dados fiscais, contábeis e movimentação de produtos em um período.

### Modified Capabilities
_(nenhuma — o catálogo de relatórios é extensível por design, sem alterar requisitos existentes)_

## Impact

- **Backend**: `RelatorioAB.cs` — novo método `GerarFichaFiscalAsync` + entrada no switch de `GerarAsync`. Consulta `Produtos`, `VendaItens`/`Vendas` e `NotasEntradaArquivo`.
- **Frontend**: `reportsConfig.ts` — novo card no catálogo. Usa `ReportFiltersView` existente sem alteração.
- **Acesso**: O endpoint `POST /api/Relatorio/Gerar` já tem autorização por role no controller; o relatório fiscal será filtrado para administrador/gerente via verificação no backend.
- **Banco de dados**: Nenhuma migration — todos os campos necessários já existem nas tabelas atuais.
