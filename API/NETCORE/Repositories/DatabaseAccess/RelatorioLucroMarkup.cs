using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;
using System.Globalization;
using System.Text.Json;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public partial class RelatorioAB
{
    private async Task<object> GerarLucroMarkupAsync(string companyId, Dictionary<string, JsonElement> filters)
    {
        var today = HorusDateTime.Now.Date;
        DateTime Date(string key, DateTime fallback)
        {
            var value = GetString(filters, key);
            if (string.IsNullOrWhiteSpace(value)) return fallback;
            if (DateTime.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed)
                && parsed.Year is >= 2000 and < 9999) return parsed;
            throw new InvalidOperationException("Informe uma data válida para o período.");
        }
        var start = Date("startDate", today.AddDays(-30));
        var end = Date("endDate", today);
        if (start > end) throw new InvalidOperationException("A data inicial deve ser anterior ou igual à data final.");
        DateTimeOffset Boundary(DateTime date) => new(date, HorusDateTime.TimeZone.GetUtcOffset(date));
        var category = GetString(filters, "categoriaId");
        var search = GetString(filters, "productSearch").Trim();
        if (search.Length > 180) throw new InvalidOperationException("A busca de produto deve ter até 180 caracteres.");
        // CHARINDEX trata a busca como texto literal, inclusive % e _, sem interpolar SQL.
        const string sql = """
            SELECT i.ProductCode, i.ProductName, i.Quantity, i.ItemTotal,
                   COALESCE(i.CostPrice, p.ProductUnitPrice) AS CostPrice,
                   CASE WHEN i.CostPrice IS NULL THEN 1 ELSE 0 END AS Estimated
            FROM Vendas v
            INNER JOIN VendaItens i ON i.VendaId = v.Id
            LEFT JOIN Produtos p ON p.CompanyId = v.CompanyId AND p.ProductCode = i.ProductCode
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = v.CompanyId
            WHERE v.CompanyId = @CompanyId
              AND ISNULL(v.Status, 'finalizada') <> 'cancelada'
              AND v.SaleDate >= @Start AND v.SaleDate < @End
              AND (@Category = '' OR p.CategoriaId = @Category OR c.CategoriaPaiId = @Category)
              AND (@Search = '' OR CHARINDEX(@Search, i.ProductCode) > 0 OR CHARINDEX(@Search, i.ProductName) > 0);
            """;
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Start", Boundary(start));
        command.Parameters.AddWithValue("@End", Boundary(end.AddDays(1)));
        command.Parameters.AddWithValue("@Category", category == "all" ? "" : category);
        command.Parameters.AddWithValue("@Search", search);
        await using var reader = await command.ExecuteReaderAsync();
        var lines = new List<SalesProfitLine>();
        while (await reader.ReadAsync())
            lines.Add(new(ReadString(reader, "ProductCode"), ReadString(reader, "ProductName"),
                ReadDecimal(reader, "Quantity"), ReadDecimal(reader, "ItemTotal"),
                reader.IsDBNull(reader.GetOrdinal("CostPrice")) ? null : ReadDecimal(reader, "CostPrice"),
                reader.GetInt32(reader.GetOrdinal("Estimated")) == 1));
        var result = SalesProfitReport.Build(lines);
        return new
        {
            columns = Columns(("codigo", "Código"), ("descricao", "Descrição do produto"),
                ("compra", "Compra média (un.)"), ("venda", "Venda média (un.)"),
                ("lucro", "Lucro bruto"), ("markup", "Markup (%)"), ("quantidade", "Quantidade vendida"),
                ("custo", "Custo total"), ("faturamento", "Total vendido"),
                ("baseCusto", "Base do custo")),
            result.Rows, result.TotalRow, result.Summary, result.Notes
        };
    }
}

public sealed record SalesProfitLine(string Code, string Name, decimal Quantity, decimal Revenue, decimal? UnitCost, bool Estimated);
public sealed record SalesProfitResult(List<Dictionary<string, object>> Rows, Dictionary<string, object> TotalRow,
    object[] Summary, string[] Notes);

public static class SalesProfitReport
{
    private static readonly CultureInfo PtBr = new("pt-BR");
    private static string Money(decimal value) => value.ToString("C", PtBr);
    private static string Markup(decimal revenue, decimal cost) => cost > 0
        ? ((revenue - cost) / cost * 100).ToString("N2", PtBr) + "%" : "—";
    private static decimal Cost(SalesProfitLine line) => Math.Round(line.UnitCost!.Value * line.Quantity, 2, MidpointRounding.AwayFromZero);

    public static SalesProfitResult Build(IReadOnlyList<SalesProfitLine> lines)
    {
        var rows = lines.GroupBy(line => line.Code, StringComparer.OrdinalIgnoreCase)
            .Select(group =>
            {
                var quantity = group.Sum(line => line.Quantity);
                var revenue = group.Sum(line => line.Revenue); // ItemTotal já desconta promoções/descontos.
                var known = group.All(line => line.UnitCost.HasValue);
                var cost = known ? group.Sum(Cost) : 0;
                return new Dictionary<string, object>
                {
                    ["codigo"] = group.Key, ["descricao"] = group.Last().Name,
                    ["quantidade"] = quantity.ToString("0.###", PtBr),
                    ["compra"] = known && quantity > 0 ? Money(cost / quantity) : "—",
                    ["venda"] = quantity > 0 ? Money(revenue / quantity) : "—",
                    ["custo"] = known ? Money(cost) : "—", ["faturamento"] = Money(revenue),
                    ["lucro"] = known ? Money(revenue - cost) : "—",
                    ["markup"] = known ? Markup(revenue, cost) : "—",
                    ["baseCusto"] = !known ? "Custo indisponível" : group.Any(line => line.Estimated)
                        ? "Inclui estimativa do cadastro" : "Registrado na venda"
                };
            }).OrderBy(row => (string)row["descricao"], StringComparer.Create(PtBr, true)).ToList();
        var totalRevenue = lines.Sum(line => line.Revenue);
        var allKnown = lines.All(line => line.UnitCost.HasValue);
        var totalCost = allKnown ? lines.Sum(Cost) : 0;
        var costText = allKnown ? Money(totalCost) : "Não calculado";
        var profitText = allKnown ? Money(totalRevenue - totalCost) : "Não calculado";
        var markupText = allKnown ? Markup(totalRevenue, totalCost) : "Não calculado";
        var totalRow = new Dictionary<string, object>
        {
            ["codigo"] = "TOTAL", ["descricao"] = "Todos os produtos filtrados", ["quantidade"] = "—",
            ["compra"] = "—", ["venda"] = "—", ["custo"] = costText, ["faturamento"] = Money(totalRevenue),
            ["lucro"] = profitText, ["markup"] = markupText, ["baseCusto"] = "—"
        };
        object Metric(string label, string value) => new { label, value };
        var notes = new List<string>
        {
            "Valores unitários são médias ponderadas pela quantidade vendida. O total vendido considera os descontos; vendas canceladas são excluídas.",
            "Lucro bruto = total vendido − custo. Não desconta impostos, taxas ou despesas. Markup do período = lucro bruto ÷ custo total × 100; os percentuais dos produtos não são somados. Custo zero não permite calcular markup.",
            "Nas vendas offline, o custo salvo é o cadastrado no servidor no momento da sincronização."
        };
        var estimated = lines.Where(line => line.Estimated).Select(line => line.Code).Distinct(StringComparer.OrdinalIgnoreCase).Count();
        if (estimated > 0) notes.Add($"{estimated} produto(s) incluem vendas sem custo histórico salvo. Nessas vendas, o custo é estimado pelo cadastro atual; alterações no cadastro podem mudar esses resultados.");
        if (!allKnown) notes.Add("Há produtos sem custo disponível. O lucro e o markup desses produtos e os totais de custo/lucro/markup não foram calculados.");
        return new(rows, totalRow, [Metric("Custo dos produtos vendidos", costText), Metric("Total vendido", Money(totalRevenue)),
            Metric("Lucro bruto do período", profitText), Metric("Markup do período", markupText)], notes.ToArray());
    }
}
