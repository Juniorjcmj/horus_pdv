# Proposal: Modelo de Produto Multi-Segmento — Material de Construção e Varejo Geral

## Why

O Hórus PDV foi construído para mercadinho e tem a base fiscal sólida (NFC-e, contingência, importação NF-e). Porém, o modelo de produto é minimalista: todos os campos numéricos são NVARCHAR, não há marca/fabricante, a hierarquia de categorias tem apenas 2 níveis, e faltam campos essenciais para operar em segmentos como **material de construção**.

Análise de uma planilha real de loja de material de construção (3.239 produtos, 114 colunas) revelou gaps concretos:

1. **Tipos de dados incorretos**: Preços (`ProductUnitPrice`, `ProductSalePrice`, `TotalPriceOnProduct`) e quantidade (`ProductQnt`) são NVARCHAR no banco. `VendaItens.Quantity` é INT. Isso causa erros de arredondamento em cálculos fiscais e impede venda fracionária (2,5m de madeira).

2. **Unidades de medida limitadas**: O sistema usa "UN" fixo. A planilha real tem 17 unidades de venda (UN, m², M, PC, RL, PAR, SC, GL, KG, etc.) e 27 unidades de compra, com fator de conversão (compra por CX de 100, vende por UN).

3. **Sem marca/fabricante**: 218 marcas na planilha. Campo essencial para busca no PDV e para o vendedor identificar o produto.

4. **Categorias com apenas 2 níveis**: A planilha usa 3 níveis (Classe > Grupo > Sub-Grupo: 18 classes, 117 grupos, 500 subgrupos). A tabela `Categorias` já suporta auto-referência, mas a UI e o seed limitam a 2 níveis.

5. **Sem peso/dimensões**: Campos como Peso Líquido, Peso Bruto, Largura, Altura, Comprimento são necessários para frete, logística e NF-e.

6. **Estoque básico demais**: Só tem quantidade e mínimo. Faltam estoque máximo, localização no depósito, e campos de custo detalhados (custo médio, custo c/ e s/ impostos).

7. **Sem campos comerciais**: Desconto máximo %, comissão %, referência do fabricante, markup cadastrado/praticado.

Todas essas adaptações são **genéricas** — beneficiam qualquer segmento de varejo, não apenas material de construção.

## What Changes

### Feature 1 — Correção de Tipos de Dados (Pré-requisito)
- Migrar `ProductQnt` de NVARCHAR(30) para DECIMAL(15,4) na tabela `Produtos`
- Migrar `ProductUnitPrice`, `ProductSalePrice`, `TotalPriceOnProduct` de NVARCHAR(30) para DECIMAL(15,4)
- Migrar `VendaItens.Quantity` de INT para DECIMAL(15,4)
- Migrar `VendaItens.UnitPrice` e `VendaItens.ItemTotal` de NVARCHAR(30) para DECIMAL(15,4)
- Atualizar `ProdutoModel.cs` para usar `decimal` nos campos numéricos
- Atualizar repositórios `ProdutoAB`, `ProdutoAD` para ler/gravar DECIMAL
- Atualizar frontend para tratar valores como número, não string

### Feature 2 — Unidades de Medida Flexíveis
- Adicionar campo `UnidadeCompra` NVARCHAR(6) na tabela `Produtos` (a `UnidadeComercial` já existe para venda)
- Adicionar campo `FatorConversao` DECIMAL(15,4) DEFAULT 1 na tabela `Produtos` (ex: 1 CX = 100 UN)
- Adicionar campo `QtdEmbalagem` DECIMAL(15,4) DEFAULT 1 na tabela `Produtos`
- Atualizar cadastro de produto no frontend com selects para unidade de venda e compra
- Validar unidades aceitas pela SEFAZ (UN, KG, M, M2, M3, L, etc.)

### Feature 3 — Marca e Fabricante
- Adicionar campos `Marca` NVARCHAR(120) e `Fabricante` NVARCHAR(120) na tabela `Produtos`
- Adicionar campo `ReferenciaFabricante` NVARCHAR(80) na tabela `Produtos`
- Atualizar formulário de cadastro com campos de marca/fabricante
- Adicionar filtro por marca na listagem de produtos
- Adicionar busca por marca no PDV

### Feature 4 — Hierarquia de 3 Níveis de Categoria
- A tabela `Categorias` já suporta auto-referência (N níveis)
- Atualizar UI do cadastro de categorias para permitir 3 níveis: Classe > Grupo > Sub-Grupo
- Atualizar select cascata no cadastro de produto para 3 níveis
- Atualizar grid de categorias no PDV para navegação em 3 níveis
- Seed idempotente não muda — cada empresa cadastra suas categorias

### Feature 5 — Peso e Dimensões do Produto
- Adicionar campos na tabela `Produtos`:
  - `PesoLiquidoKg` DECIMAL(15,4) DEFAULT 0
  - `PesoBrutoKg` DECIMAL(15,4) DEFAULT 0
  - `LarguraCm` DECIMAL(10,2) DEFAULT 0
  - `AlturaCm` DECIMAL(10,2) DEFAULT 0
  - `ComprimentoCm` DECIMAL(10,2) DEFAULT 0
- Atualizar formulário de cadastro com seção "Dimensões e Peso"
- Campos opcionais — não exibidos se todos zerados

### Feature 6 — Estoque Expandido
- Adicionar campos na tabela `Produtos`:
  - `EstoqueMaximo` DECIMAL(15,4) DEFAULT 0
  - `LocalizacaoEstoque` NVARCHAR(100) NULL
- Atualizar `ProdutoModel.cs` e repositórios
- Atualizar formulário de cadastro com campos de localização e máximo
- Atualizar página de estoque (`StockPage.tsx`) com filtro por localização

### Feature 7 — Dados de Custo e Campos Comerciais
- Adicionar campos na tabela `Produtos`:
  - `CustoMedio` DECIMAL(15,4) DEFAULT 0
  - `CustoComImposto` DECIMAL(15,4) DEFAULT 0
  - `CustoSemImposto` DECIMAL(15,4) DEFAULT 0
  - `DescontoMaximoPercentual` DECIMAL(9,4) DEFAULT 0
  - `ComissaoPercentual` DECIMAL(9,4) DEFAULT 0
  - `MarkupCadastrado` DECIMAL(9,4) DEFAULT 0
  - `MarkupPraticado` DECIMAL(9,4) DEFAULT 0
- Atualizar formulário de cadastro com seção "Dados Comerciais"
- Campos opcionais — seção colapsada por padrão

## Capabilities

### New Capabilities
- `produtos/modelo-expandido`: Expansão do modelo de produto com tipos corretos, unidades de medida, marca/fabricante, peso/dimensões, estoque expandido, custos e campos comerciais

### Modified Capabilities
- `produtos/categorias`: Ajuste para suportar 3 níveis de hierarquia na UI (a tabela já suporta)

## Impact

- **Banco de Dados**: 1 nova migração SQL (13_modelo_produto_expandido.sql) com:
  - ALTER de 6+ colunas NVARCHAR para DECIMAL com conversão de dados existentes
  - ALTER de VendaItens.Quantity de INT para DECIMAL(15,4)
  - ADD de ~18 novas colunas na tabela Produtos
  - Conversão idempotente de dados string para decimal

- **API Backend**:
  - Alterações: `ProdutoModel.cs` (tipos string → decimal, novos campos), `ProdutoRequest.cs`, `ProdutoAB.cs`, `ProdutoAD.cs`, `ProdutoService.cs`, `ProdutoController.cs`
  - Alterações: `VendaItens` model/repository (Quantity para decimal)
  - Alterações: NFC-e fiscal provider (usar decimal nativo em vez de parse de string)

- **Frontend**:
  - Alterações: `ProductRegisterPage.tsx` (novos campos organizados em seções colapsáveis)
  - Alterações: `StockPage.tsx` (localização, estoque máximo)
  - Alterações: `SalesStartPage.tsx` (busca por marca, navegação 3 níveis)
  - Alterações: services/types (tipos numéricos)

## Assumptions

1. Dados existentes em NVARCHAR podem ser convertidos para DECIMAL sem perda — a migração faz parse com fallback para 0
2. O seed de categorias de mercadinho permanece intacto — novas categorias de material de construção são cadastradas pela empresa, não por seed
3. O modelo de preço permanece simples (custo + venda) — campos extras de custo são informativos, não alteram lógica de venda
4. A UI agrupa novos campos em seções colapsáveis para não poluir o formulário para quem não precisa
