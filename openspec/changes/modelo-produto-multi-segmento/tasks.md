# Tasks: Modelo de Produto Multi-Segmento

## Fase 1 — Migração SQL (Base para tudo)

### Task 1.1: Conversão de tipos NVARCHAR→DECIMAL
- [x] Já feita pela migração `01_migracao_valores.sql` — banco já é DECIMAL nativo
- [x] ProdutoAB/ProdutoAD já usam `decimal` no C#

**Nota**: A migração 01 já converteu ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct (Produtos) e Quantity, UnitPrice, ItemTotal (VendaItens) para DECIMAL. O contrato HTTP (ProdutoModel/ProdutoRequest) usa string pt-BR por design — a conversão acontece no ProdutoService via HorusMoneyFormat.

### Task 1.2: Adicionar novos campos na migração
- [x] Criar arquivo `API/NETCORE/DataBase/Migrations/12_modelo_produto_expandido.sql`
- [x] Adicionar campos de unidade: `UnidadeCompra`, `FatorConversao`, `QtdEmbalagem`
- [x] Adicionar campos de marca: `Marca`, `Fabricante`, `ReferenciaFabricante`
- [x] Adicionar campos de peso/dimensão: `PesoLiquidoKg`, `PesoBrutoKg`, `LarguraCm`, `AlturaCm`, `ComprimentoCm`
- [x] Adicionar campos de estoque: `EstoqueMaximo`, `LocalizacaoEstoque`
- [x] Adicionar campos de custo: `CustoMedio`, `CustoComImposto`, `CustoSemImposto`
- [x] Adicionar campos comerciais: `DescontoMaximoPercentual`, `ComissaoPercentual`, `MarkupCadastrado`, `MarkupPraticado`
- [x] Todos com IF COL_LENGTH IS NULL (idempotente)
- [x] Criar índice `IX_Produtos_Company_Marca` para busca por marca
- [x] Backfill: CustoMedio = ProductUnitPrice, UnidadeCompra = UnidadeComercial

**Arquivos**: `API/NETCORE/DataBase/Migrations/12_modelo_produto_expandido.sql`

---

## Fase 2 — Backend: Models e Repositórios

### Task 2.1: Atualizar ProdutoAD, ProdutoModel e ProdutoRequest
- [x] Adicionar novos campos em `ProdutoAD.cs` (decimal nativo)
- [x] Adicionar novos campos em `ProdutoModel.cs` (string pt-BR para HTTP)
- [x] Adicionar novos campos em `ProdutoRequest.cs` (string pt-BR para HTTP)

**Arquivos**: `ProdutoAD.cs`, `ProdutoModel.cs`, `ProdutoRequest.cs`

### Task 2.2: Atualizar ProdutoAB (DatabaseAccess)
- [x] Atualizar Columns const com novos campos no SELECT
- [x] Atualizar Map() para ler novos campos do SqlDataReader
- [x] Atualizar SalvarAsync SQL (INSERT/UPDATE) com novos campos
- [x] Atualizar AddParameters com novos parâmetros

**Arquivos**: `API/NETCORE/Repositories/DatabaseAccess/ProdutoAB.cs`

### Task 2.3: Atualizar ProdutoAD (DataAccess DTO)
- [x] ProdutoAD já atualizado junto com Task 2.1

### Task 2.4: Atualizar ProdutoService (mapeamento e validação)
- [x] Atualizar MapRequest para mapear novos campos de ProdutoRequest → ProdutoAD
- [x] Validar FatorConversao >= 1 (Math.Max)
- [x] Calcular MarkupPraticado automaticamente
- [x] Calcular CustoMedio default = ProductUnitPrice quando não informado
- [x] Atualizar ToModel para mapear novos campos de ProdutoAD → ProdutoModel

**Arquivos**: `API/NETCORE/Services/Produtos/ProdutoService.cs`

### Task 2.5: VendaItens — sem alteração necessária
- [x] Verificado: migração 01 já converteu VendaItens para DECIMAL
- [x] Repositórios já usam decimal nativo

### Task 2.6: NFC-e fiscal provider — sem alteração necessária
- [x] Verificado: ZeusFiscalProvider usa UnidadeComercial (já existente)
- [x] Novos campos não afetam emissão fiscal

### Task 2.7: NfeImportService — sem alteração necessária
- [x] Verificado: ProdutoAD tem defaults nos novos campos
- [x] new ProdutoAD { ... } sem os novos campos funciona (defaults C#)

---

## Fase 3 — Frontend: Tipos e Formulários

### Task 3.1: Atualizar tipos TypeScript
- [x] Encontrar interface/tipo de Product no frontend
- [x] Adicionar novos campos ao tipo
- [x] Atualizar funções de formatação se necessário

**Arquivos**: `FRONTEND/src/services/api/productService.ts`, `FRONTEND/src/pages/Admin/ProductRegisterPage.tsx`

### Task 3.2: Atualizar ProductRegisterPage.tsx
- [x] Seção "Identificação": adicionar campos Marca, Fabricante, Ref. Fabricante
- [x] Seção "Estoque": adicionar Unidade Compra (select), Fator Conversão, Qtd Embalagem, Estoque Máximo, Localização
- [x] Seção colapsável "Peso e Dimensões": PesoLíquido, PesoBruto, Largura, Altura, Comprimento
- [x] Seção colapsável "Dados Comerciais": Custo Médio, C/Imposto, S/Imposto, Markup Cadastrado, Praticado (read-only), Desconto Máx %, Comissão %
- [x] Select de Unidade Venda com lista de unidades SEFAZ
- [x] Select cascata de categoria para 3 níveis

**Arquivos**: `FRONTEND/src/pages/Admin/ProductRegisterPage.tsx`

### Task 3.3: Atualizar SalesStartPage.tsx (PDV)
- [x] Exibir unidade de venda junto ao preço (ex: "R$ 45,00/m²")
- [x] Exibir marca abaixo do nome (texto menor, cor secundária)
- [x] Permitir busca por marca no campo de busca
- [x] Navegação de categorias com 3 níveis e breadcrumb
- [x] Input de quantidade aceitar decimais para unidades fracionárias

**Arquivos**: `FRONTEND/src/pages/Admin/SalesStartPage.tsx`

### Task 3.4: Atualizar StockPage.tsx
- [x] Adicionar coluna "Localização" na tabela de estoque
- [x] Adicionar coluna "Estoque Máximo"
- [x] Filtro por localização (select com valores distintos)
- [x] Indicador visual quando estoque > máximo

**Arquivos**: `FRONTEND/src/pages/Admin/StockPage.tsx`

### Task 3.5: Atualizar CategoriaService e UI para 3 níveis
- [x] Verificar se o backend já retorna 3 níveis na árvore
- [x] Atualizar UI de gestão de categorias para permitir criar nível 3
- [x] Testar navegação de 3 níveis no PDV

**Arquivos**: `FRONTEND/src/` (componentes de categoria), `API/NETCORE/Services/Categorias/CategoriaService.cs`

---

## Fase 4 — Testes e Validação

### Task 4.1: Teste de migração com dados reais
- [ ] Fazer backup do banco
- [ ] Executar migração 12
- [ ] Verificar colunas criadas e backfill correto

### Task 4.2: Teste end-to-end do fluxo de venda
- [ ] Cadastrar produto com unidade m² e preço por m²
- [ ] Realizar venda fracionária no PDV
- [ ] Verificar NFC-e e estoque

### Task 4.3: Teste de regressão
- [ ] Vender produto normal (UN)
- [ ] Importar XML NF-e
- [ ] Verificar promoções e fiado com tipos decimais

---

## Resumo de Progresso

**Fase 1**: 2/2 tasks completas ✓
**Fase 2**: 7/7 tasks completas ✓
**Fase 3**: 5/5 tasks completas ✓
**Fase 4**: 0/3 tasks pendentes (requer deploy)
