/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/RelatorioFichaFiscal.cs
 * Objetivo: gerar o relatório "Ficha Fiscal e Contábil" (ficha-fiscal-produtos), consolidando dados
 *           fiscais e contábeis do cadastro com a movimentação real de vendas e entradas no período.
 * Entradas esperadas: companyId, filtros (startDate, endDate, categoriaId, productSearch) e
 *                     restrictToUserId que carrega a role — apenas administrador e gerente têm acesso.
 */
using Microsoft.Data.SqlClient;
using System.Globalization;
using System.Text.Json;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public partial class RelatorioAB
{
    /// <summary>
    /// Gera o relatório Ficha Fiscal e Contábil: um produto por linha, com dados fiscais,
    /// contábeis e movimentação (vendas + entradas) no período.
    /// Restrição: apenas administrador e gerente podem gerar este relatório.
    /// </summary>
    private async Task<object> GerarFichaFiscalAsync(
        string companyId,
        Dictionary<string, JsonElement> filters,
        string? restrictToUserId)
    {
        // Task 2.1 — controle de acesso: somente administrador e gerente.
        // O controller define restrictToUserId = null para gerente/administrador e = currentUser.Id para atendente/caixa.
        // Se não for null, o usuário não é gerente nem administrador e não tem acesso a este relatório.
        if (restrictToUserId is not null)
        {
            throw new InvalidOperationException(
                "Acesso negado. O relatório Ficha Fiscal e Contábil está disponível apenas para administradores e gerentes.");
        }

        var startDate = GetDate(filters, "startDate") ?? DateTimeOffset.Now.AddDays(-30).Date;
        var endDate = (GetDate(filters, "endDate") ?? DateTimeOffset.Now.Date).AddDays(1);
        var categoriaFilter = GetString(filters, "categoriaId");
        var productSearch = GetString(filters, "productSearch").Trim();

        // Task 1.2 — query de produtos com campos fiscais e contábeis
        var produtos = await ListarProdutosFiscaisAsync(companyId);

        // Task 1.3 — query de movimentação de vendas
        var vendasPorCodigo = await ListarVendasFiscalAsync(companyId, startDate, endDate);

        // Task 1.4 — query de movimentação de entradas
        var entradasPorId = await ListarEntradasFiscalAsync(companyId, startDate, endDate);

        // Task 1.5 — merge das 3 fontes, filtros e montagem do resultado
        var linhas = new List<Dictionary<string, object>>();
        foreach (var produto in produtos)
        {
            // Filtro por categoria
            if (!string.IsNullOrWhiteSpace(categoriaFilter) && categoriaFilter != "all")
            {
                if (produto.CategoriaId != categoriaFilter && produto.CategoriaPaiId != categoriaFilter)
                    continue;
            }

            // Filtro por busca textual
            if (!string.IsNullOrWhiteSpace(productSearch))
            {
                var searchLower = productSearch.ToLowerInvariant();
                if (!produto.ProductCode.ToLowerInvariant().Contains(searchLower) &&
                    !produto.ProductName.ToLowerInvariant().Contains(searchLower))
                    continue;
            }

            // Movimentação de vendas
            vendasPorCodigo.TryGetValue(produto.ProductCode, out var venda);

            // Movimentação de entradas
            entradasPorId.TryGetValue(produto.Id, out var entrada);

            // Apenas produtos com movimentação no período
            if (venda is null && entrada is null) continue;

            var qtdVendida = venda?.Quantidade ?? 0m;
            var faturamento = venda?.Faturamento ?? 0m;
            var qtdEntrada = entrada?.Quantidade ?? 0m;
            var custoEntrada = entrada?.CustoTotal ?? 0m;
            var valorEstoque = Math.Round(produto.ProductQnt * produto.ProductUnitPrice, 2);

            linhas.Add(Row(
                // Identificação
                ("codigo",        produto.ProductCode),
                ("produto",       produto.ProductName),
                ("categoria",     string.IsNullOrWhiteSpace(produto.CategoriaNome) ? "-" : produto.CategoriaNome),
                ("gtin",          produto.Gtin),
                // Classificação fiscal
                ("ncm",           produto.Ncm),
                ("cest",          produto.Cest ?? "-"),
                ("cfop",          produto.Cfop),
                ("origem",        produto.OrigemMercadoria.ToString()),
                ("unCom",         produto.UnidadeComercial),
                ("unTrib",        produto.UnidadeTributavel),
                // Tributação
                ("csosnCstIcms",  !string.IsNullOrWhiteSpace(produto.CsosnIcms) ? produto.CsosnIcms : (produto.CstIcms ?? "-")),
                ("aliqIcms",      $"{produto.AliquotaIcms:F2}%"),
                ("cstPis",        produto.CstPis),
                ("cstCofins",     produto.CstCofins),
                ("cstIbsCbs",     produto.CstIbsCbs ?? "-"),
                ("classeTrib",    produto.CClassTrib ?? "-"),
                // Dados contábeis
                ("custoUnitario", FormatMoney(produto.ProductUnitPrice)),
                ("precoVenda",    FormatMoney(produto.ProductSalePrice)),
                ("custoMedio",    FormatMoney(produto.CustoMedio)),
                ("custoComImp",   FormatMoney(produto.CustoComImposto)),
                ("custoSemImp",   FormatMoney(produto.CustoSemImposto)),
                ("margemDesejada",produto.MargemDesejadaPercentual.HasValue ? $"{produto.MargemDesejadaPercentual:F2}%" : "-"),
                ("markupCad",     $"{produto.MarkupCadastrado:F2}%"),
                ("markupPrat",    $"{produto.MarkupPraticado:F2}%"),
                ("lucroUnitario", FormatMoney(produto.Lucro)),
                ("estoque",       produto.ProductQnt),
                ("valorEstoque",  FormatMoney(valorEstoque)),
                // Movimentação no período
                ("qtdVendida",    qtdVendida),
                ("faturamento",   FormatMoney(faturamento)),
                ("qtdEntrada",    qtdEntrada),
                ("custoEntrada",  FormatMoney(custoEntrada))));
        }

        return Result(
            Columns(
                // Identificação
                ("codigo",        "Código"),
                ("produto",       "Produto"),
                ("categoria",     "Categoria"),
                ("gtin",          "GTIN"),
                // Classificação fiscal
                ("ncm",           "NCM"),
                ("cest",          "CEST"),
                ("cfop",          "CFOP"),
                ("origem",        "Origem"),
                ("unCom",         "Un. Comercial"),
                ("unTrib",        "Un. Tributável"),
                // Tributação
                ("csosnCstIcms",  "CSOSN/CST ICMS"),
                ("aliqIcms",      "Alíq. ICMS"),
                ("cstPis",        "CST PIS"),
                ("cstCofins",     "CST COFINS"),
                ("cstIbsCbs",     "CST IBS/CBS"),
                ("classeTrib",    "Class. Tributária"),
                // Dados contábeis
                ("custoUnitario", "Custo Unitário"),
                ("precoVenda",    "Preço de Venda"),
                ("custoMedio",    "Custo Médio"),
                ("custoComImp",   "Custo c/ Imposto"),
                ("custoSemImp",   "Custo s/ Imposto"),
                ("margemDesejada","Margem Desejada"),
                ("markupCad",     "Markup Cadastrado"),
                ("markupPrat",    "Markup Praticado"),
                ("lucroUnitario", "Lucro Unitário"),
                ("estoque",       "Estoque Atual"),
                ("valorEstoque",  "Valor em Estoque"),
                // Movimentação no período
                ("qtdVendida",    "Qtd. Vendida"),
                ("faturamento",   "Faturamento"),
                ("qtdEntrada",    "Qtd. Entrada"),
                ("custoEntrada",  "Custo Entradas")),
            linhas);
    }

    // Task 1.2 — query de produtos com campos fiscais e contábeis
    private async Task<List<FiscalProdutoRow>> ListarProdutosFiscaisAsync(string companyId)
    {
        const string sql = """
            SELECT p.Id, p.ProductCode, p.ProductName, p.Gtin,
                   p.ProductQnt, p.ProductUnitPrice, p.ProductSalePrice,
                   p.CustoMedio, p.CustoComImposto, p.CustoSemImposto,
                   p.MargemDesejadaPercentual, p.MarkupCadastrado, p.MarkupPraticado,
                   p.Lucro, p.TotalPriceOnProduct,
                   p.Ncm, p.Cest, p.Cfop, p.OrigemMercadoria,
                   p.UnidadeComercial, p.UnidadeTributavel,
                   p.CsosnIcms, p.CstIcms, p.AliquotaIcms,
                   p.CstPis, p.CstCofins, p.CstIbsCbs, p.CClassTrib,
                   p.CategoriaId, c.Nome AS CategoriaNome, c.CategoriaPaiId
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId
            WHERE p.CompanyId = @CompanyId
            ORDER BY p.ProductName;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<FiscalProdutoRow>();
        while (await reader.ReadAsync())
        {
            rows.Add(new FiscalProdutoRow(
                Id:                   ReadString(reader, "Id"),
                ProductCode:          ReadString(reader, "ProductCode"),
                ProductName:          ReadString(reader, "ProductName"),
                Gtin:                 ReadString(reader, "Gtin"),
                ProductQnt:           ReadDecimal(reader, "ProductQnt"),
                ProductUnitPrice:     ReadDecimal(reader, "ProductUnitPrice"),
                ProductSalePrice:     ReadDecimal(reader, "ProductSalePrice"),
                CustoMedio:           ReadDecimal(reader, "CustoMedio"),
                CustoComImposto:      ReadDecimal(reader, "CustoComImposto"),
                CustoSemImposto:      ReadDecimal(reader, "CustoSemImposto"),
                MargemDesejadaPercentual: ReadNullableDecimal(reader, "MargemDesejadaPercentual"),
                MarkupCadastrado:     ReadDecimal(reader, "MarkupCadastrado"),
                MarkupPraticado:      ReadDecimal(reader, "MarkupPraticado"),
                Lucro:                ReadDecimal(reader, "Lucro"),
                Ncm:                  ReadString(reader, "Ncm"),
                Cest:                 ReadNullableString(reader, "Cest"),
                Cfop:                 ReadString(reader, "Cfop"),
                OrigemMercadoria:     (byte)ReadDecimal(reader, "OrigemMercadoria"),
                UnidadeComercial:     ReadString(reader, "UnidadeComercial"),
                UnidadeTributavel:    ReadString(reader, "UnidadeTributavel"),
                CsosnIcms:            ReadNullableString(reader, "CsosnIcms"),
                CstIcms:              ReadNullableString(reader, "CstIcms"),
                AliquotaIcms:         ReadDecimal(reader, "AliquotaIcms"),
                CstPis:               ReadString(reader, "CstPis"),
                CstCofins:            ReadString(reader, "CstCofins"),
                CstIbsCbs:            ReadNullableString(reader, "CstIbsCbs"),
                CClassTrib:           ReadNullableString(reader, "CClassTrib"),
                CategoriaId:          ReadNullableString(reader, "CategoriaId"),
                CategoriaNome:        ReadNullableString(reader, "CategoriaNome"),
                CategoriaPaiId:       ReadNullableString(reader, "CategoriaPaiId")));
        }

        return rows;
    }

    // Task 1.3 — query de movimentação de vendas agregada por ProductCode
    private async Task<Dictionary<string, FiscalVendaAgregado>> ListarVendasFiscalAsync(
        string companyId, DateTimeOffset startDate, DateTimeOffset endDate)
    {
        const string sql = """
            SELECT i.ProductCode,
                   SUM(i.Quantity)  AS QtdVendida,
                   SUM(i.ItemTotal) AS Faturamento
            FROM Vendas v
            INNER JOIN VendaItens i ON i.VendaId = v.Id
            WHERE v.CompanyId = @CompanyId
              AND ISNULL(v.Status, 'finalizada') <> 'cancelada'
              AND v.SaleDate >= @StartDate
              AND v.SaleDate  < @EndDate
            GROUP BY i.ProductCode;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@StartDate", startDate);
        command.Parameters.AddWithValue("@EndDate", endDate);
        await using var reader = await command.ExecuteReaderAsync();
        var result = new Dictionary<string, FiscalVendaAgregado>(StringComparer.OrdinalIgnoreCase);
        while (await reader.ReadAsync())
        {
            var code = ReadString(reader, "ProductCode");
            if (!string.IsNullOrWhiteSpace(code))
                result[code] = new FiscalVendaAgregado(ReadDecimal(reader, "QtdVendida"), ReadDecimal(reader, "Faturamento"));
        }

        return result;
    }

    // Task 1.4 — query de movimentação de entradas, parseando EntradaJson em C#
    private async Task<Dictionary<string, FiscalEntradaAgregado>> ListarEntradasFiscalAsync(
        string companyId, DateTimeOffset startDate, DateTimeOffset endDate)
    {
        const string sql = """
            SELECT EntradaJson
            FROM NotasEntradaArquivo
            WHERE CompanyId = @CompanyId
              AND Estornada = 0
              AND CriadaEm >= @StartDate
              AND CriadaEm  < @EndDate;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@StartDate", startDate);
        command.Parameters.AddWithValue("@EndDate", endDate);
        await using var reader = await command.ExecuteReaderAsync();

        // Agregar por produtoExistenteId
        var agrupado = new Dictionary<string, (decimal Qtd, decimal Custo)>(StringComparer.OrdinalIgnoreCase);
        while (await reader.ReadAsync())
        {
            var jsonStr = ReadString(reader, "EntradaJson");
            if (string.IsNullOrWhiteSpace(jsonStr)) continue;
            try
            {
                using var doc = JsonDocument.Parse(jsonStr);
                var root = doc.RootElement;
                // Tenta "itens" (camelCase, serialização Web) e "Itens" (PascalCase, legado)
                if (!root.TryGetProperty("itens", out var itensEl) &&
                    !root.TryGetProperty("Itens", out itensEl))
                    continue;

                foreach (var item in itensEl.EnumerateArray())
                {
                    // produtoExistenteId
                    string? prodId = null;
                    if (item.TryGetProperty("produtoExistenteId", out var pidEl) || item.TryGetProperty("ProdutoExistenteId", out pidEl))
                        prodId = pidEl.ValueKind == JsonValueKind.String ? pidEl.GetString() : null;
                    if (string.IsNullOrWhiteSpace(prodId)) continue;

                    // quantidade (string pt-BR ou número)
                    decimal qtd = 0;
                    if (item.TryGetProperty("quantidade", out var qEl) || item.TryGetProperty("Quantidade", out qEl))
                        qtd = ParseJsonDecimal(qEl);
                    if (qtd <= 0) continue;

                    // precoCusto (string pt-BR ou número)
                    decimal custo = 0;
                    if (item.TryGetProperty("precoCusto", out var cEl) || item.TryGetProperty("PrecoCusto", out cEl))
                        custo = ParseJsonDecimal(cEl);

                    if (agrupado.TryGetValue(prodId, out var existing))
                        agrupado[prodId] = (existing.Qtd + qtd, existing.Custo + custo * qtd);
                    else
                        agrupado[prodId] = (qtd, custo * qtd);
                }
            }
            catch (JsonException) { /* JSON inválido — ignora esta nota */ }
        }

        return agrupado.ToDictionary(
            kvp => kvp.Key,
            kvp => new FiscalEntradaAgregado(kvp.Value.Qtd, kvp.Value.Custo),
            StringComparer.OrdinalIgnoreCase);
    }

    /// <summary>Lê um decimal de um JsonElement que pode ser string pt-BR ou número JSON.</summary>
    private static decimal ParseJsonDecimal(JsonElement el)
    {
        if (el.ValueKind == JsonValueKind.Number)
            return el.GetDecimal();
        if (el.ValueKind == JsonValueKind.String)
        {
            var str = el.GetString() ?? "";
            // Formato pt-BR: "7,45" ou "1.234,56"
            if (decimal.TryParse(str, NumberStyles.Number, new CultureInfo("pt-BR"), out var ptBr))
                return ptBr;
            // Formato invariant fallback
            if (decimal.TryParse(str, NumberStyles.Number, CultureInfo.InvariantCulture, out var inv))
                return inv;
        }
        return 0;
    }

    private static decimal? ReadNullableDecimal(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDecimal(ordinal);
    }

    private sealed record FiscalProdutoRow(
        string Id,
        string ProductCode,
        string ProductName,
        string Gtin,
        decimal ProductQnt,
        decimal ProductUnitPrice,
        decimal ProductSalePrice,
        decimal CustoMedio,
        decimal CustoComImposto,
        decimal CustoSemImposto,
        decimal? MargemDesejadaPercentual,
        decimal MarkupCadastrado,
        decimal MarkupPraticado,
        decimal Lucro,
        string Ncm,
        string? Cest,
        string Cfop,
        byte OrigemMercadoria,
        string UnidadeComercial,
        string UnidadeTributavel,
        string? CsosnIcms,
        string? CstIcms,
        decimal AliquotaIcms,
        string CstPis,
        string CstCofins,
        string? CstIbsCbs,
        string? CClassTrib,
        string? CategoriaId,
        string? CategoriaNome,
        string? CategoriaPaiId);

    private sealed record FiscalVendaAgregado(decimal Quantidade, decimal Faturamento);
    private sealed record FiscalEntradaAgregado(decimal Quantidade, decimal CustoTotal);
}
