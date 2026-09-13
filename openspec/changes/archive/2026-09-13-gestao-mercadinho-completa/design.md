# Design: Gestão Completa de Mercadinho

## Context

O Hórus PDV é um sistema PDV multi-tenant com backend ASP.NET Core 8 (ADO.NET puro, SQL Server) e frontend React 19 + TypeScript + Vite + Tailwind v4. A arquitetura segue o padrão: Controller → Service → Repository (`*AB`) com data records (`*AD`), autenticação JWT via cookie HttpOnly, e response envelope `ApiResponse<T>`.

A base de dados atual tem ~18 tabelas. Produtos são armazenados na tabela `Produtos` sem campo de categoria. Vendas utilizam split payment (`VendaPagamentos`). NFC-e é emitida via outbox pattern assíncrono (`NfceOutboxWorker` + `ZeusFiscalProvider`). Migrations são scripts SQL idempotentes executados no boot pelo `HorusDatabaseInitializer`.

Ver motivação em `proposal.md` e requisitos detalhados em `specs/`.

## Goals / Non-Goals

**Goals:**
- Implementar 4 features (Categorias, Validade, Promoções, Fiado) com integração completa ao fluxo existente de venda, estoque, caixa e NFC-e
- Manter 100% de retrocompatibilidade com dados existentes (vendas, produtos, clientes)
- Seguir rigorosamente os padrões arquiteturais existentes (ADO.NET, AB/AD, ApiResponse, migrations idempotentes)
- Minimizar impacto no desempenho do PDV (consultas otimizadas, dados cacheados no frontend)

**Non-Goals:**
- Controle de lotes individuais (suficiente a data do lote mais próximo do vencimento)
- Motor de fidelidade/pontos (escopo separado)
- Integração TEF/PIX gateway (escopo separado, já marcado como "Under Development")
- Impressão em impressora de etiquetas (Zebra/Argox) — mantém impressão browser

## Decisions

### 1. Modelagem — Tabela `Categorias` com auto-referência

```sql
CREATE TABLE Categorias (
    Id              NVARCHAR(40)    NOT NULL,
    CompanyId       NVARCHAR(40)    NOT NULL DEFAULT 'empresa-principal',
    Nome            NVARCHAR(80)    NOT NULL,
    CategoriaPaiId  NVARCHAR(40)    NULL,
    Ordem           INT             NOT NULL DEFAULT 0,
    Ativa           BIT             NOT NULL DEFAULT 1,
    CONSTRAINT PK_Categorias PRIMARY KEY (Id),
    CONSTRAINT FK_Categorias_Pai FOREIGN KEY (CategoriaPaiId) REFERENCES Categorias(Id),
    CONSTRAINT UQ_Categorias_Nome UNIQUE (CompanyId, Nome, CategoriaPaiId)
);
```

- **Por que auto-referência e não tabelas separadas `Departamentos`/`Subcategorias`?** Porque o padrão do projeto é minimalismo — uma tabela resolve 2 níveis. Se no futuro precisar 3 níveis, a estrutura já suporta sem migration.
- `Produtos.CategoriaId NVARCHAR(40) NULL FK → Categorias(Id) ON DELETE SET NULL` — NULL = sem categoria, não bloqueia venda.
- IDs semânticos: `'cat-bebidas'`, `'cat-refrigerantes'` (slug legível, padrão do projeto).

### 2. Modelagem — Validade no Produto (campo simples, não tabela de lotes)

```sql
ALTER TABLE Produtos ADD DataValidade DATE NULL;
ALTER TABLE Produtos ADD ControlaValidade BIT NOT NULL DEFAULT 0;
ALTER TABLE Produtos ADD DiasAlertaValidade INT NOT NULL DEFAULT 15;
```

- **Por que não tabela de lotes?** Mercadinho pequeno não rastreia lote individual. O operador informa a data do lote mais próximo do vencimento ao receber mercadoria. Isso é viável operacionalmente e resolve 95% dos casos.
- Índice filtrado `WHERE ControlaValidade = 1 AND DataValidade IS NOT NULL` — evita scan na maioria dos produtos (limpeza, bazar).
- `DiasAlertaValidade` por produto porque iogurte (7 dias) é diferente de arroz (180 dias).

### 3. Modelagem — Promoções com tipo parametrizado

```sql
CREATE TABLE Promocoes (
    Id              NVARCHAR(40)    NOT NULL,
    CompanyId       NVARCHAR(40)    NOT NULL DEFAULT 'empresa-principal',
    Nome            NVARCHAR(180)   NOT NULL,
    Tipo            NVARCHAR(30)    NOT NULL,
    ValorDesconto   DECIMAL(18,2)   NULL,
    PrecoFixo       DECIMAL(18,2)   NULL,
    QuantidadeLeva  INT             NULL,
    QuantidadePaga  INT             NULL,
    QuantidadeMinima INT            NULL,
    InicioVigencia  DATETIMEOFFSET  NOT NULL,
    FimVigencia     DATETIMEOFFSET  NOT NULL,
    Ativa           BIT             NOT NULL DEFAULT 1,
    CategoriaId     NVARCHAR(40)    NULL,
    CriadoPor       NVARCHAR(180)   NOT NULL DEFAULT '',
    CriadoEm        DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT PK_Promocoes PRIMARY KEY (Id)
);

CREATE TABLE PromocaoProdutos (
    Id          NVARCHAR(40)    NOT NULL,
    PromocaoId  NVARCHAR(40)    NOT NULL,
    ProdutoId   NVARCHAR(40)    NOT NULL,
    CONSTRAINT PK_PromocaoProdutos PRIMARY KEY (Id),
    CONSTRAINT FK_PromocaoProdutos_Promocao FOREIGN KEY (PromocaoId) REFERENCES Promocoes(Id) ON DELETE CASCADE,
    CONSTRAINT FK_PromocaoProdutos_Produto FOREIGN KEY (ProdutoId) REFERENCES Produtos(Id) ON DELETE CASCADE,
    CONSTRAINT UQ_PromocaoProdutos UNIQUE (PromocaoId, ProdutoId)
);
```

- **Por que coluna `Tipo` + campos nullable em vez de tabelas polimórficas?** Simplicidade. São 6 tipos fixos, não extensíveis por usuário. Uma tabela com poucos campos nullable é mais simples que herança ou EAV.
- **Resolução de conflitos:** Motor no frontend calcula desconto efetivo de cada promo candidata e aplica a de maior desconto. Sem acúmulo.
- **Vínculo por categoria:** Se `CategoriaId` está preenchido E `PromocaoProdutos` está vazio, a promo se aplica a todos os produtos da categoria. Se ambos existem, `PromocaoProdutos` tem precedência.
- Campo `Desconto DECIMAL(18,2) NOT NULL DEFAULT 0` adicionado à `VendaItens` para persistir desconto aplicado.

### 4. Modelagem — Fiado como extensão do split payment

```sql
ALTER TABLE Clientes ADD LimiteCredito DECIMAL(18,2) NOT NULL DEFAULT 0;
ALTER TABLE Clientes ADD SaldoDevedor DECIMAL(18,2) NOT NULL DEFAULT 0;

CREATE TABLE FiadoMovimentos (
    Id              NVARCHAR(40)    NOT NULL,
    CompanyId       NVARCHAR(40)    NOT NULL DEFAULT 'empresa-principal',
    ClienteId       NVARCHAR(40)    NOT NULL,
    Tipo            TINYINT         NOT NULL, -- 1=Débito, 2=Crédito
    Valor           DECIMAL(18,2)   NOT NULL,
    SaldoAnterior   DECIMAL(18,2)   NOT NULL,
    SaldoAtual      DECIMAL(18,2)   NOT NULL,
    VendaId         NVARCHAR(40)    NULL,
    Observacao      NVARCHAR(300)   NOT NULL DEFAULT '',
    OperadorNome    NVARCHAR(180)   NOT NULL,
    CriadoEm        DATETIMEOFFSET  NOT NULL DEFAULT SYSDATETIMEOFFSET(),
    CONSTRAINT PK_FiadoMovimentos PRIMARY KEY (Id),
    CONSTRAINT FK_FiadoMovimentos_Clientes FOREIGN KEY (ClienteId) REFERENCES Clientes(Id),
    CONSTRAINT FK_FiadoMovimentos_Vendas FOREIGN KEY (VendaId) REFERENCES Vendas(Id)
);
```

- **Por que `SaldoDevedor` desnormalizado no `Clientes`?** Performance. A lista de devedores é consultada frequentemente. Recalcular via `SUM()` em `FiadoMovimentos` a cada consulta é inviável com muitos clientes.
- **`SaldoAnterior` + `SaldoAtual` no movimento:** Auditoria. Permite reconstruir o extrato sem recalcular, e detectar inconsistências.
- **Transaction scope:** O INSERT em `FiadoMovimentos` + UPDATE em `Clientes.SaldoDevedor` + INSERT em `VendaPagamentos` ocorrem na mesma transação da venda (`HistoricoVendasAB`).
- **Lock:** `SELECT ... WITH (UPDLOCK, ROWLOCK)` no `Clientes` durante venda fiado para evitar race condition com recebimento simultâneo.

### 5. Motor de Promoção no Frontend

O motor roda inteiramente no frontend para não adicionar latência ao caixa:

1. No boot do PDV: `GET /api/Promocao/ativas` carrega todas as promoções ativas com seus `produtoIds` e `categoriaId`
2. Ao adicionar item ao carrinho: `evaluatePromotions(cartItems, activePromotions)` calcula desconto
3. Função pura sem side effects — recebe carrinho + promos, retorna carrinho com descontos aplicados
4. Recalcula todo o carrinho a cada adição/remoção (necessário para promos tipo combo/atacado que dependem de quantidade)
5. Dados enviados ao backend na venda: cada item com `unitPrice` (preço cheio), `discount` (desconto) e `itemTotal` (líquido)

**Por que no frontend?** O PDV precisa mostrar feedback instantâneo. Uma chamada API a cada item adicionado mataria a UX. O backend valida na gravação.

### 6. Integração Fiscal

- **Promoção → vDesc:** No `ZeusFiscalProvider`, ao montar `det[i].prod`, se `VendaItem.Desconto > 0`, preenche `vDesc` com o valor. `vProd` permanece como preço cheio × quantidade.
- **Fiado → tPag=05:** No mapeamento de formas de pagamento, `"fiado"` mapeia para `FormaPagamento.fpCreditoLoja` (código 05), conforme NT 2020.006.

### 7. Novos Relatórios

| Report ID | Tipo | Joins |
|---|---|---|
| `margem-por-categoria` | Novo | `VendaItens → Produtos → Categorias` + custo |
| `vencimentos` | Novo | `Produtos` filtrado por `ControlaValidade` + `DataValidade` |
| `inadimplencia` | Novo | `Clientes` + `FiadoMovimentos` (última compra/pagamento) |
| `resultado-promocao` | Novo (via endpoint) | `VendaItens` filtrado por `PromocaoId` + desconto |
| Todos existentes | Modificados | Filtro `categoriaId` opcional via LEFT JOIN com `Produtos → Categorias` |

### 8. Novas Rotas Frontend

```typescript
// No router existente, dentro do layout Admin:
{ path: "promocoes",  element: <PromocoesPage /> }
{ path: "fiado",      element: <FiadoPage /> }
```

### 9. Ordem de Implementação

1. **Categorias** (migration 09) — base para promoções por departamento e filtros
2. **Validade** (migration 10) — independente, alto valor imediato
3. **Promoções** (migration 11) — depende de categorias para promo por departamento
4. **Fiado** (migration 12) — independente mas maior complexidade transacional

## Risks / Trade-offs

- **[Risco] Saldo devedor inconsistente por crash entre INSERT e UPDATE**
  → *Mitigação*: Tudo dentro de `SqlTransaction` com `UPDLOCK, ROWLOCK`. Se falhar, rollback atômico.

- **[Risco] Motor de promoção no frontend pode ficar desatualizado**
  → *Mitigação*: PDV recarrega promoções ativas no boot e a cada 5 minutos via polling. Backend valida desconto na gravação.

- **[Risco] Promoção combo (3 por R$10) com quantidade fracionada**
  → *Mitigação*: Promoções combo/atacado só se aplicam quando `UnidadeComercial = 'UN'` (unidade inteira). Produtos pesáveis (KG) usam apenas desconto % ou preço fixo.

- **[Risco] DataValidade como campo único vs. múltiplos lotes**
  → *Trade-off aceito*: Simplifica operação (operador informa a menor validade). Se o mercadinho crescer e precisar de controle por lote, será uma feature futura. O campo `DataValidade` permanece como "validade do lote mais próximo".

- **[Risco] Volume de FiadoMovimentos crescente ao longo do tempo**
  → *Mitigação*: Índice `IX_FiadoMovimentos_Cliente` com `CriadoEm DESC` para consultas recentes. Extrato filtrado por período por padrão.
