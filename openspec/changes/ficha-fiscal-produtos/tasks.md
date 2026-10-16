## 1. Backend — método de geração do relatório

- [x] 1.1 Criar método `GerarFichaFiscalAsync(string companyId, Dictionary<string, JsonElement> filters, string? restrictToUserId)` em `RelatorioAB.cs` com verificação de role (rejeitar atendente/caixa via parâmetro `restrictToUserId` ou verificação interna)
- [x] 1.2 Implementar query de produtos com todos os campos fiscais e contábeis do `ProdutoAD`: NCM, CEST, CFOP, OrigemMercadoria, Gtin, UnidadeComercial, UnidadeTributavel, CsosnIcms, CstIcms, AliquotaIcms, CstPis, CstCofins, CstIbsCbs, CClassTrib, ProductUnitPrice, ProductSalePrice, CustoMedio, CustoComImposto, CustoSemImposto, MargemDesejadaPercentual, MarkupCadastrado, MarkupPraticado, Lucro, ProductQnt, TotalPriceOnProduct, CategoriaId, CategoriaNome
- [x] 1.3 Implementar query de movimentação de vendas: agregar `VendaItens` por `ProductCode` no período (startDate/endDate), somando quantidade e faturamento (ItemTotal). Excluir vendas canceladas (`Status <> 'cancelada'`)
- [x] 1.4 Implementar query de movimentação de entrada: ler `NotasEntradaArquivo` do período (por `CriadaEm`), filtrar `Estornada=0`, parsear `EntradaJson` e agregar por `produtoExistenteId` (soma de quantidade e custo)
- [x] 1.5 Fazer merge das 3 fontes por produto (chave = ProductCode para vendas, Id para entradas), aplicar filtros de categoria e busca textual, montar linhas de resultado no formato `{ columns, rows }`
- [x] 1.6 Adicionar entrada `"ficha-fiscal-produtos" => await GerarFichaFiscalAsync(...)` no switch do `GerarAsync`

## 2. Backend — controle de acesso

- [x] 2.1 Passar a role do usuário para `GerarFichaFiscalAsync` e rejeitar com `InvalidOperationException` se não for administrador ou gerente (verificar como `log-atividades` faz a restrição por `restrictToUserId` e seguir o mesmo padrão)

## 3. Frontend — card no catálogo de relatórios

- [x] 3.1 Adicionar entrada `ficha-fiscal-produtos` em `reportsConfig.ts` com título "Ficha Fiscal e Contábil", descrição, ícone (`FileSpreadsheet` ou similar do lucide-react), e filtros: `startDate`, `endDate`, `categoriaId`, `productSearch`
- [x] 3.2 Importar o ícone escolhido no bloco de imports do `reportsConfig.ts`

## 4. Verificação

- [x] 4.1 Verificar compilação do backend (`dotnet build --no-restore`)
- [x] 4.2 Verificar compilação do frontend (`npx tsc --noEmit`)
