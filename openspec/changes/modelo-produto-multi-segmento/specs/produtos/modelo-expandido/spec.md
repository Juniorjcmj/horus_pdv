# Spec: Modelo de Produto Expandido para Multi-Segmento

## Overview

Expansão do modelo de dados de produto do Hórus PDV para suportar múltiplos segmentos de varejo (mercado, material de construção, etc.). Inclui correção de tipos de dados numéricos, unidades de medida flexíveis, marca/fabricante, peso/dimensões, estoque expandido, custos detalhados e campos comerciais.

## Requirements

### REQ-1: Correção de Tipos Numéricos

**Banco de dados:**
- `Produtos.ProductQnt`: NVARCHAR(30) → DECIMAL(15,4)
- `Produtos.ProductUnitPrice`: NVARCHAR(30) → DECIMAL(15,4)
- `Produtos.ProductSalePrice`: NVARCHAR(30) → DECIMAL(15,4)
- `Produtos.TotalPriceOnProduct`: NVARCHAR(30) → DECIMAL(15,4)
- `VendaItens.Quantity`: INT → DECIMAL(15,4)
- `VendaItens.UnitPrice`: NVARCHAR(30) → DECIMAL(15,4)
- `VendaItens.ItemTotal`: NVARCHAR(30) → DECIMAL(15,4)

**Migração:**
- Criar coluna temporária DECIMAL, converter dados existentes com TRY_CAST (fallback 0), dropar coluna antiga, renomear nova
- Atualizar constraints e defaults
- Migração idempotente (verificar tipo antes de alterar)

**Model C#:**
- `ProdutoModel`: propriedades `ProductQnt`, `ProductUnitPrice`, `ProductSalePrice`, `TotalPriceOnProduct` mudam de `string` para `decimal`
- `ProdutoRequest`: idem para os campos de entrada

**Frontend:**
- Tratar valores como `number` em vez de `string` em todos os componentes que manipulam preço/quantidade

### REQ-2: Unidades de Medida Flexíveis

**Novos campos no banco (tabela Produtos):**
- `UnidadeCompra` NVARCHAR(6) NOT NULL DEFAULT 'UN' — unidade usada na compra/entrada
- `FatorConversao` DECIMAL(15,4) NOT NULL DEFAULT 1 — quantas unidades de venda cabem em 1 unidade de compra
- `QtdEmbalagem` DECIMAL(15,4) NOT NULL DEFAULT 1 — quantidade de itens por embalagem

**Comportamento:**
- `UnidadeComercial` (já existente) = unidade de venda exibida no PDV e na NFC-e
- `UnidadeCompra` = unidade usada na entrada de mercadoria
- `FatorConversao` permite conversão automática: ao dar entrada de 1 CX (compra), o estoque incrementa 100 UN (venda) se fator = 100
- Valores aceitos para unidades (alinhados com SEFAZ): UN, KG, G, M, M2, M3, L, ML, PC, PAR, CX, CT, RL, SC, GL, BD, BL, JG, BOB, LT, SACH, FD, PCT, BAR, ROL, MIL, PLT, PT, BEM

**Frontend:**
- Select dropdown para unidade de venda e unidade de compra no cadastro de produto
- Campo numérico para fator de conversão (exibido quando unidade de compra ≠ unidade de venda)
- PDV exibe a unidade de venda junto ao preço (ex: "R$ 45,00 / m²")

### REQ-3: Marca e Fabricante

**Novos campos no banco (tabela Produtos):**
- `Marca` NVARCHAR(120) NULL
- `Fabricante` NVARCHAR(120) NULL
- `ReferenciaFabricante` NVARCHAR(80) NULL

**Comportamento:**
- Campos opcionais — NULL quando não informados
- Filtro por marca na listagem de produtos (ProductRegisterPage)
- Busca por marca no PDV (SalesStartPage) — digitando a marca retorna produtos da marca
- Exibição da marca na linha do produto no PDV

**Frontend:**
- Campos de texto no formulário de cadastro (seção "Identificação")
- Autocomplete baseado em marcas já cadastradas (query DISTINCT na coluna)

### REQ-4: Hierarquia de 3 Níveis de Categoria

**Banco de dados:**
- Nenhuma alteração — a tabela `Categorias` já suporta N níveis via `CategoriaPaiId`

**Backend:**
- Ajustar query de `CategoriaAB`/`CategoriaAD` para carregar 3 níveis na árvore
- Endpoint existente de categorias já retorna árvore recursiva — verificar se funciona para 3 níveis

**Frontend:**
- Cadastro de categorias: permitir criar sub-subcategoria (nível 3) dentro de uma subcategoria
- Cadastro de produto: select cascata com 3 níveis — Classe > Grupo > Sub-Grupo
- PDV (SalesStartPage): grid de categorias com navegação drill-down em 3 níveis
  - Nível 1: mostra classes (ex: "Materiais Hidráulicos", "Ferragens")
  - Nível 2: mostra grupos dentro da classe selecionada (ex: "Torneiras", "Conexões")
  - Nível 3: mostra subgrupos (ex: "Torneira de Jardim", "Torneira de Cozinha")
  - Breadcrumb para voltar aos níveis anteriores

### REQ-5: Peso e Dimensões

**Novos campos no banco (tabela Produtos):**
- `PesoLiquidoKg` DECIMAL(15,4) NOT NULL DEFAULT 0
- `PesoBrutoKg` DECIMAL(15,4) NOT NULL DEFAULT 0
- `LarguraCm` DECIMAL(10,2) NOT NULL DEFAULT 0
- `AlturaCm` DECIMAL(10,2) NOT NULL DEFAULT 0
- `ComprimentoCm` DECIMAL(10,2) NOT NULL DEFAULT 0

**Comportamento:**
- Campos opcionais — default 0 significa "não informado"
- Usados futuramente para cálculo de frete e NF-e (tag `<prod><pesoL>`, `<pesoB>`)
- Não afetam lógica de venda atual

**Frontend:**
- Seção colapsável "Peso e Dimensões" no formulário de cadastro
- Oculta por padrão se todos os valores são 0
- Inputs numéricos com sufixo (kg, cm)

### REQ-6: Estoque Expandido

**Novos campos no banco (tabela Produtos):**
- `EstoqueMaximo` DECIMAL(15,4) NOT NULL DEFAULT 0
- `LocalizacaoEstoque` NVARCHAR(100) NULL

**Comportamento:**
- `EstoqueMaximo` = 0 significa "sem limite"
- `LocalizacaoEstoque` = texto livre (ex: "RA - P29", "Corredor 3 - Prateleira B")
- StockPage exibe localização e permite filtrar por ela
- Alerta visual quando estoque > máximo (possível erro de entrada)

**Frontend:**
- Campos no formulário de cadastro, seção "Estoque"
- Filtro por localização na StockPage
- Coluna de localização na tabela de estoque

### REQ-7: Dados de Custo e Campos Comerciais

**Novos campos no banco (tabela Produtos):**
- `CustoMedio` DECIMAL(15,4) NOT NULL DEFAULT 0
- `CustoComImposto` DECIMAL(15,4) NOT NULL DEFAULT 0
- `CustoSemImposto` DECIMAL(15,4) NOT NULL DEFAULT 0
- `DescontoMaximoPercentual` DECIMAL(9,4) NOT NULL DEFAULT 0
- `ComissaoPercentual` DECIMAL(9,4) NOT NULL DEFAULT 0
- `MarkupCadastrado` DECIMAL(9,4) NOT NULL DEFAULT 0
- `MarkupPraticado` DECIMAL(9,4) NOT NULL DEFAULT 0

**Comportamento:**
- `CustoMedio` = calculado automaticamente nas entradas de estoque (média ponderada)
- `CustoComImposto` e `CustoSemImposto` = informativos, atualizados na importação
- `DescontoMaximoPercentual` = 0 significa "sem limite" — validado no PDV se > 0
- `ComissaoPercentual` = informativo para cálculo de comissão de vendedores
- `MarkupCadastrado` = margem de lucro desejada (% sobre custo)
- `MarkupPraticado` = calculado: ((precoVenda - custoMedio) / custoMedio) * 100
- `MargemDesejadaPercentual` (já existente) mantém compatibilidade — `MarkupCadastrado` é a versão nova

**Frontend:**
- Seção colapsável "Dados Comerciais" no formulário de cadastro
- Exibição read-only de markup praticado (calculado)
- Campos opcionais — seção colapsada por padrão

## Acceptance Criteria

1. Migração SQL executa sem erro em banco com dados existentes (mercadinho com ~4.700 produtos)
2. Nenhum dado existente é perdido na conversão NVARCHAR → DECIMAL
3. NFC-e continua emitindo corretamente após mudança de tipos
4. PDV funciona normalmente para produtos "simples" (UN, sem marca, sem dimensões) — a expansão é opt-in
5. Produtos com unidade m², M, KG etc. exibem a unidade no PDV e na NFC-e
6. Categorias de 3 níveis navegáveis no PDV
7. Busca por marca funciona no PDV
8. Formulário de cadastro não fica poluído — seções colapsáveis para campos opcionais

## Out of Scope

- Múltiplas tabelas de preço no sistema (usuário mapeia na importação)
- NF-e (modelo 55) — continua como desenvolvimento separado
- Importação automática de planilha Excel — será feature futura
- Cálculo automático de preço por unidade de medida diferente
- Controle de lote/rastreabilidade
