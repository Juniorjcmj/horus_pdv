# Design: Modelo de Produto Multi-Segmento

## Architecture Overview

A mudança afeta 3 camadas verticalmente: banco de dados → API backend → frontend React. O banco é a base (tipos corretos + novos campos), o backend expõe os novos dados, e o frontend adiciona UI para cadastro e busca.

```
┌─────────────────────────────────────────────────┐
│  Frontend React                                  │
│  ┌──────────────┐  ┌──────────────┐             │
│  │ ProductReg   │  │ SalesStart   │             │
│  │ Page (edit)  │  │ Page (PDV)   │             │
│  │ + seções     │  │ + marca/3lv  │             │
│  │ colapsáveis  │  │ + unidade    │             │
│  └──────┬───────┘  └──────┬───────┘             │
│         │                  │                     │
│  ┌──────┴──────────────────┴───────┐             │
│  │ apiClient / types.ts            │             │
│  │ (decimal em vez de string)      │             │
│  └──────┬──────────────────────────┘             │
├─────────┼───────────────────────────────────────┤
│  API .NET 8                                      │
│  ┌──────┴──────────────────────────┐             │
│  │ ProdutoController               │             │
│  │ (sem mudança de endpoints)      │             │
│  └──────┬──────────────────────────┘             │
│  ┌──────┴──────────────────────────┐             │
│  │ ProdutoService                  │             │
│  │ (validação de unidades, markup) │             │
│  └──────┬──────────────────────────┘             │
│  ┌──────┴──────────────────────────┐             │
│  │ ProdutoModel / ProdutoRequest   │             │
│  │ (string → decimal + novos)      │             │
│  └──────┬──────────────────────────┘             │
│  ┌──────┴──────────────────────────┐             │
│  │ ProdutoAB / ProdutoAD           │             │
│  │ (SQL decimal, novos campos)     │             │
│  └──────┬──────────────────────────┘             │
├─────────┼───────────────────────────────────────┤
│  SQL Server                                      │
│  ┌──────┴──────────────────────────┐             │
│  │ 13_modelo_produto_expandido.sql │             │
│  │ ALTER + conversão + ADD cols    │             │
│  └─────────────────────────────────┘             │
└─────────────────────────────────────────────────┘
```

## Database Migration Strategy

### Migration: `13_modelo_produto_expandido.sql`

A migração é a parte mais delicada — converte colunas NVARCHAR para DECIMAL em tabelas com dados existentes. Estratégia: **rename-convert-drop** para cada coluna.

#### Passo 1: Conversão de Tipos em Produtos

Para cada coluna NVARCHAR → DECIMAL (ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct):

```sql
-- Padrão para cada coluna (exemplo: ProductQnt)
IF COL_LENGTH(N'Produtos', N'ProductQnt') IS NOT NULL
   AND TYPE_NAME(
       (SELECT system_type_id FROM sys.columns
        WHERE object_id = OBJECT_ID(N'Produtos') AND name = N'ProductQnt')
   ) = N'nvarchar'
BEGIN
    -- 1. Criar coluna nova
    ALTER TABLE Produtos ADD ProductQnt_NEW DECIMAL(15,4) NOT NULL DEFAULT 0;

    -- 2. Converter dados (remove R$, troca vírgula por ponto)
    UPDATE Produtos
    SET ProductQnt_NEW = COALESCE(
        TRY_CAST(
            REPLACE(REPLACE(REPLACE(LTRIM(RTRIM(ProductQnt)), N'R$', N''), N'.', N''), N',', N'.')
        AS DECIMAL(15,4)), 0);

    -- 3. Dropar constraint default antiga
    -- 4. Dropar coluna antiga
    -- 5. Renomear nova
    EXEC sp_rename N'Produtos.ProductQnt_NEW', N'ProductQnt', N'COLUMN';
END;
```

#### Passo 2: Conversão de VendaItens

```sql
-- VendaItens.Quantity: INT → DECIMAL(15,4)
-- VendaItens.UnitPrice: NVARCHAR → DECIMAL(15,4)
-- VendaItens.ItemTotal: NVARCHAR → DECIMAL(15,4)
```

Mesma estratégia rename-convert-drop. INT → DECIMAL é mais simples (cast direto).

#### Passo 3: Novos campos em Produtos

```sql
-- Unidades
IF COL_LENGTH(N'Produtos', N'UnidadeCompra') IS NULL
    ALTER TABLE Produtos ADD UnidadeCompra NVARCHAR(6) NOT NULL DEFAULT N'UN';
IF COL_LENGTH(N'Produtos', N'FatorConversao') IS NULL
    ALTER TABLE Produtos ADD FatorConversao DECIMAL(15,4) NOT NULL DEFAULT 1;
IF COL_LENGTH(N'Produtos', N'QtdEmbalagem') IS NULL
    ALTER TABLE Produtos ADD QtdEmbalagem DECIMAL(15,4) NOT NULL DEFAULT 1;

-- Marca/Fabricante
IF COL_LENGTH(N'Produtos', N'Marca') IS NULL
    ALTER TABLE Produtos ADD Marca NVARCHAR(120) NULL;
IF COL_LENGTH(N'Produtos', N'Fabricante') IS NULL
    ALTER TABLE Produtos ADD Fabricante NVARCHAR(120) NULL;
IF COL_LENGTH(N'Produtos', N'ReferenciaFabricante') IS NULL
    ALTER TABLE Produtos ADD ReferenciaFabricante NVARCHAR(80) NULL;

-- Peso/Dimensões
IF COL_LENGTH(N'Produtos', N'PesoLiquidoKg') IS NULL
    ALTER TABLE Produtos ADD PesoLiquidoKg DECIMAL(15,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'PesoBrutoKg') IS NULL
    ALTER TABLE Produtos ADD PesoBrutoKg DECIMAL(15,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'LarguraCm') IS NULL
    ALTER TABLE Produtos ADD LarguraCm DECIMAL(10,2) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'AlturaCm') IS NULL
    ALTER TABLE Produtos ADD AlturaCm DECIMAL(10,2) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'ComprimentoCm') IS NULL
    ALTER TABLE Produtos ADD ComprimentoCm DECIMAL(10,2) NOT NULL DEFAULT 0;

-- Estoque expandido
IF COL_LENGTH(N'Produtos', N'EstoqueMaximo') IS NULL
    ALTER TABLE Produtos ADD EstoqueMaximo DECIMAL(15,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'LocalizacaoEstoque') IS NULL
    ALTER TABLE Produtos ADD LocalizacaoEstoque NVARCHAR(100) NULL;

-- Custos
IF COL_LENGTH(N'Produtos', N'CustoMedio') IS NULL
    ALTER TABLE Produtos ADD CustoMedio DECIMAL(15,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'CustoComImposto') IS NULL
    ALTER TABLE Produtos ADD CustoComImposto DECIMAL(15,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'CustoSemImposto') IS NULL
    ALTER TABLE Produtos ADD CustoSemImposto DECIMAL(15,4) NOT NULL DEFAULT 0;

-- Campos comerciais
IF COL_LENGTH(N'Produtos', N'DescontoMaximoPercentual') IS NULL
    ALTER TABLE Produtos ADD DescontoMaximoPercentual DECIMAL(9,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'ComissaoPercentual') IS NULL
    ALTER TABLE Produtos ADD ComissaoPercentual DECIMAL(9,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'MarkupCadastrado') IS NULL
    ALTER TABLE Produtos ADD MarkupCadastrado DECIMAL(9,4) NOT NULL DEFAULT 0;
IF COL_LENGTH(N'Produtos', N'MarkupPraticado') IS NULL
    ALTER TABLE Produtos ADD MarkupPraticado DECIMAL(9,4) NOT NULL DEFAULT 0;
```

## Backend Changes

### ProdutoModel.cs — Mudanças de Tipo

```csharp
// ANTES (string)              →  DEPOIS (decimal)
public string ProductQnt          →  public decimal ProductQnt { get; set; }
public string ProductUnitPrice    →  public decimal ProductUnitPrice { get; set; }
public string ProductSalePrice    →  public decimal ProductSalePrice { get; set; }
public string TotalPriceOnProduct →  public decimal TotalPriceOnProduct { get; set; }
public string EstoqueMinimo       →  public decimal EstoqueMinimo { get; set; }

// NOVOS CAMPOS
public string UnidadeCompra { get; set; } = "UN";
public decimal FatorConversao { get; set; } = 1;
public decimal QtdEmbalagem { get; set; } = 1;
public string? Marca { get; set; }
public string? Fabricante { get; set; }
public string? ReferenciaFabricante { get; set; }
public decimal PesoLiquidoKg { get; set; }
public decimal PesoBrutoKg { get; set; }
public decimal LarguraCm { get; set; }
public decimal AlturaCm { get; set; }
public decimal ComprimentoCm { get; set; }
public decimal EstoqueMaximo { get; set; }
public string? LocalizacaoEstoque { get; set; }
public decimal CustoMedio { get; set; }
public decimal CustoComImposto { get; set; }
public decimal CustoSemImposto { get; set; }
public decimal DescontoMaximoPercentual { get; set; }
public decimal ComissaoPercentual { get; set; }
public decimal MarkupCadastrado { get; set; }
public decimal MarkupPraticado { get; set; }
```

### ProdutoAB.cs / ProdutoAD.cs — SQL Queries

- SELECT: adicionar novos campos nas queries de leitura
- INSERT/UPDATE: incluir novos campos nos statements
- Usar `reader.GetDecimal()` em vez de `reader.GetString()` para campos numéricos
- Calcular `MarkupPraticado` no SELECT: `((ProductSalePrice - CustoMedio) / NULLIF(CustoMedio, 0)) * 100`

### ProdutoService.cs — Validações

- Validar `UnidadeComercial` e `UnidadeCompra` contra lista de unidades aceitas pela SEFAZ
- Se `FatorConversao` < 1, rejeitar com erro de validação
- Calcular `CustoMedio` na entrada de estoque (média ponderada)

### VendaItens — Modelo e Repository

- Alterar tipo de `Quantity` para `decimal` no model
- Atualizar queries de INSERT e SELECT

## Frontend Changes

### ProductRegisterPage.tsx — Layout Reorganizado

O formulário atual será reorganizado em seções colapsáveis:

```
┌─ Identificação ──────────────────────────────┐
│ Nome | Código | Código de Barras              │
│ Marca | Fabricante | Ref. Fabricante          │
│ Descrição                                     │
│ Categoria (3 níveis cascata)                  │
└───────────────────────────────────────────────┘

┌─ Preços e Custos ─────────────────────────────┐
│ Preço Custo | Preço Venda | Margem Desejada   │
│ ▸ Dados Comerciais (colapsável)               │
│   Custo Médio | C/ Imposto | S/ Imposto       │
│   Markup Cadastrado | Praticado (read-only)   │
│   Desconto Máx % | Comissão %                 │
└───────────────────────────────────────────────┘

┌─ Estoque ─────────────────────────────────────┐
│ Quantidade | Mínimo | Máximo                   │
│ Localização                                    │
│ Unidade Venda | Unidade Compra | Fator Conv.  │
│ Qtd. Embalagem                                │
└───────────────────────────────────────────────┘

┌─ ▸ Peso e Dimensões (colapsável) ────────────┐
│ Peso Líquido | Peso Bruto                      │
│ Largura | Altura | Comprimento                 │
└───────────────────────────────────────────────┘

┌─ Dados Fiscais ───────────────────────────────┐
│ (campos fiscais existentes, sem alteração)     │
└───────────────────────────────────────────────┘
```

### SalesStartPage.tsx — PDV

- Grid de categorias: navegação drill-down em 3 níveis com breadcrumb
- Busca: adicionar filtro por marca (autocomplete)
- Exibição: mostrar unidade de venda junto ao preço ("R$ 45,00/m²")
- Exibição: mostrar marca abaixo do nome do produto (texto menor, cor secundária)

### StockPage.tsx

- Nova coluna "Localização" na tabela de estoque
- Filtro por localização (select com valores distintos)
- Indicador visual quando estoque > máximo

### Tipos TypeScript

```typescript
interface Product {
  // existentes (string → number)
  productQnt: number;
  productUnitPrice: number;
  productSalePrice: number;
  totalPriceOnProduct: number;
  estoqueMinimo: number;

  // novos
  unidadeCompra: string;
  fatorConversao: number;
  qtdEmbalagem: number;
  marca?: string;
  fabricante?: string;
  referenciaFabricante?: string;
  pesoLiquidoKg: number;
  pesoBrutoKg: number;
  larguraCm: number;
  alturaCm: number;
  comprimentoCm: number;
  estoqueMaximo: number;
  localizacaoEstoque?: string;
  custoMedio: number;
  custoComImposto: number;
  custoSemImposto: number;
  descontoMaximoPercentual: number;
  comissaoPercentual: number;
  markupCadastrado: number;
  markupPraticado: number;
}
```

## Risks and Mitigations

| Risco | Probabilidade | Impacto | Mitigação |
|-------|--------------|---------|-----------|
| Conversão NVARCHAR→DECIMAL corrompe dados | Média | Alto | TRY_CAST com fallback 0 + log de valores não convertidos |
| Queries existentes quebram com novos tipos | Alta | Alto | Atualizar TODOS os repositórios que leem/escrevem Produtos e VendaItens |
| Frontend quebra com tipo number vs string | Alta | Médio | Atualizar types + testar formulários de cadastro e PDV |
| NFC-e rejeita por mudança de tipo | Baixa | Alto | Fiscal provider já faz formatação — testar emissão após mudança |
| Migração lenta em base grande | Baixa | Baixo | Base maior tem ~4.700 produtos — migração instantânea |

## Migration Safety

- **Backup**: Recomendado antes de executar a migração 12
- **Rollback**: Não automatizado — manter backup do banco
- **Idempotência**: Cada ALTER verificado com `COL_LENGTH` e `TYPE_NAME` antes de executar
- **Ordem**: Migração 12 executa após todas as anteriores (01-11)
