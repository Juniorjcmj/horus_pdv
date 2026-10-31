/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EstoqueRelatorioAB.cs
 * Objetivo: relatórios de apoio à compra calculados no servidor (mesmos números para web e app de estoque):
 *           reposição por giro, curva ABC, estoque crítico, estoque parado, validade e perdas.
 *           Vendas consideradas: Status diferente de 'cancelada' e 'corrigida'.
 * Entradas esperadas: companyId + parâmetros do período; devolve registros tipados (o controller aplica as
 *           restrições de perfil e o RelatorioAB converte em colunas/linhas para a tela web).
 */
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public sealed record ReposicaoItem(
    string ProdutoId, string Codigo, string Nome, string Unidade, string? FornecedorId, string Fornecedor,
    decimal Saldo, decimal Minimo, decimal Maximo, decimal VendaPeriodo, decimal MediaDiaria, decimal? CoberturaDias,
    decimal Sugerido, string Motivo, decimal? CustoUnitario, decimal? CustoEstimado);

public sealed record ReposicaoResultado(int Dias, int Cobertura, List<ReposicaoItem> Itens, decimal? CustoTotal);

public sealed record CurvaAbcItem(
    string ProdutoId, string Codigo, string Nome, string Classe, decimal Quantidade, decimal Faturamento,
    decimal Participacao, decimal ParticipacaoAcumulada, decimal Saldo, decimal? CoberturaDias);

public sealed record CurvaAbcResultado(DateTime De, DateTime Ate, decimal FaturamentoTotal, List<CurvaAbcItem> Itens,
    List<CurvaAbcClasse> Classes);

public sealed record CurvaAbcClasse(string Classe, int Produtos, decimal Faturamento, decimal Participacao);

public sealed record CriticoItem(
    string ProdutoId, string Codigo, string Nome, string Unidade, string Fornecedor, decimal Saldo, decimal Minimo,
    decimal Faltam, string Situacao, decimal? CustoReposicao);

public sealed record ParadoItem(
    string ProdutoId, string Codigo, string Nome, string Unidade, decimal Saldo, DateTimeOffset? UltimaVenda,
    DateTimeOffset? UltimaEntrada, int? DiasSemVenda, bool NuncaVendido, decimal? Custo, decimal? ValorParado);

public sealed record ValidadeItem(
    string ProdutoId, string Codigo, string Nome, string? NumeroLote, DateTime DataValidade, int DiasRestantes,
    decimal Quantidade, bool Vencido, decimal? Custo, decimal? Valor);

public sealed record PerdaItem(
    string ProdutoId, string Codigo, string Nome, string Motivo, string Usuario, DateTime Data, decimal Quantidade,
    decimal? Custo, decimal? Valor);

public sealed record PerdasResultado(DateTime De, DateTime Ate, List<PerdaItem> Itens, decimal QuantidadeTotal, decimal? ValorTotal);

public class EstoqueRelatorioAB(Connection connection)
{
    public const string SemFornecedor = "Sem fornecedor";
    private const string VendaValida = "ISNULL(v.Status, 'finalizada') NOT IN ('cancelada', 'corrigida')";

    private static DateTimeOffset InicioDoDia(DateTime dia)
    {
        var d = dia.Date;
        return new DateTimeOffset(d, HorusDateTime.TimeZone.GetUtcOffset(d));
    }

    // ------------------------------------------------------------------ reposição

    public async Task<ReposicaoResultado> ReposicaoAsync(string companyId, int dias, int cobertura, string? categoriaId, bool comValores)
    {
        dias = Math.Clamp(dias, 1, 365);
        cobertura = Math.Clamp(cobertura, 1, 365);
        var de = InicioDoDia(HorusDateTime.NowDateTime.AddDays(-dias));

        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            $"""
            SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.SupplierId,
                   COALESCE(NULLIF(f.FantasyName, N''), NULLIF(f.CompanyName, N''), N'{SemFornecedor}') AS Fornecedor,
                   p.ProductQnt, p.EstoqueMinimo, p.EstoqueMaximo, p.ProductUnitPrice,
                   ISNULL(s.Qtd, 0) AS Vendido
            FROM Produtos p
            LEFT JOIN Fornecedores f ON f.Id = p.SupplierId
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId
            LEFT JOIN (
                SELECT vi.ProductCode, SUM(vi.Quantity) AS Qtd
                FROM VendaItens vi JOIN Vendas v ON v.Id = vi.VendaId
                WHERE v.CompanyId = @C AND v.SaleDate >= @De AND {VendaValida}
                GROUP BY vi.ProductCode) s ON s.ProductCode = p.ProductCode
            WHERE p.CompanyId = @C
              AND (@Cat IS NULL OR p.CategoriaId = @Cat OR c.CategoriaPaiId = @Cat)
            ORDER BY p.ProductName;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@De", de);
        cmd.Parameters.AddWithValue("@Cat", Cat(categoriaId));

        var itens = new List<ReposicaoItem>();
        await using (var r = await cmd.ExecuteReaderAsync())
        {
            while (await r.ReadAsync())
            {
                var unidade = r.GetString(3);
                var saldo = r.GetDecimal(6);
                var minimo = r.GetDecimal(7);
                var maximo = r.GetDecimal(8);
                var custo = r.GetDecimal(9);
                var vendido = r.GetDecimal(10);
                var media = vendido / dias;

                var porGiro = 0m;
                if (media > 0)
                {
                    var alvo = media * cobertura;
                    if (maximo > 0) alvo = Math.Min(maximo, alvo);
                    porGiro = Math.Max(0m, alvo - saldo);
                }

                var porMinimo = saldo < minimo ? minimo - saldo : 0m;
                var sugerido = Math.Max(porGiro, porMinimo);
                if (sugerido <= 0) continue;

                var motivo = porMinimo > 0 && porMinimo >= porGiro && (media == 0 || porMinimo > porGiro)
                    ? "abaixo do mínimo"
                    : porMinimo > 0 ? "giro de vendas e abaixo do mínimo" : "giro de vendas";
                sugerido = string.Equals(unidade.Trim(), "UN", StringComparison.OrdinalIgnoreCase)
                    ? Math.Ceiling(sugerido)
                    : Math.Round(sugerido, 3);

                var fornecedorId = r.IsDBNull(4) ? null : r.GetString(4);
                itens.Add(new ReposicaoItem(
                    r.GetString(0), r.GetString(1), r.GetString(2), unidade, fornecedorId, r.GetString(5), saldo, minimo, maximo,
                    vendido, Math.Round(media, 4), media > 0 ? Math.Round(saldo / media, 1) : null, sugerido, motivo,
                    comValores ? custo : null, comValores ? Math.Round(custo * sugerido, 2) : null));
            }
        }

        itens = itens.OrderBy(i => i.FornecedorId is null ? 1 : 0).ThenBy(i => i.Fornecedor).ThenBy(i => i.Nome).ToList();
        return new ReposicaoResultado(dias, cobertura, itens, comValores ? itens.Sum(i => i.CustoEstimado ?? 0) : null);
    }

    // ------------------------------------------------------------------ curva ABC

    public async Task<(CurvaAbcResultado? Resultado, string? Erro)> CurvaAbcAsync(
        string companyId, DateTime? de, DateTime? ate, string? categoriaId, decimal a, decimal b)
    {
        var fim = (ate ?? HorusDateTime.NowDateTime).Date;
        var inicio = (de ?? fim.AddDays(-89)).Date;
        if (inicio > fim) return (null, "A data inicial não pode ser maior que a final.");
        var dias = (fim - inicio).Days + 1;
        if (dias > 365) return (null, "O período máximo da curva ABC é de 365 dias.");
        if (a <= 0 || b <= a || b > 100) return (null, "Os limites devem respeitar 0 < A < B <= 100.");

        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            $"""
            SELECT p.Id, p.ProductCode, p.ProductName, SUM(vi.Quantity) AS Qtd, SUM(vi.ItemTotal) AS Fat, p.ProductQnt
            FROM VendaItens vi
            JOIN Vendas v ON v.Id = vi.VendaId
            JOIN Produtos p ON p.CompanyId = v.CompanyId AND p.ProductCode = vi.ProductCode
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId
            WHERE v.CompanyId = @C AND v.SaleDate >= @De AND v.SaleDate < @Ate AND {VendaValida}
              AND (@Cat IS NULL OR p.CategoriaId = @Cat OR c.CategoriaPaiId = @Cat)
            GROUP BY p.Id, p.ProductCode, p.ProductName, p.ProductQnt
            ORDER BY SUM(vi.ItemTotal) DESC, p.ProductName;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@De", InicioDoDia(inicio));
        cmd.Parameters.AddWithValue("@Ate", InicioDoDia(fim.AddDays(1)));
        cmd.Parameters.AddWithValue("@Cat", Cat(categoriaId));

        var linhas = new List<(string Id, string Codigo, string Nome, decimal Qtd, decimal Fat, decimal Saldo)>();
        await using (var r = await cmd.ExecuteReaderAsync())
            while (await r.ReadAsync())
                linhas.Add((r.GetString(0), r.GetString(1), r.GetString(2), r.GetDecimal(3), r.GetDecimal(4), r.GetDecimal(5)));

        var total = linhas.Sum(l => l.Fat);
        var itens = new List<CurvaAbcItem>();
        decimal acum = 0;
        foreach (var l in linhas)
        {
            var part = total > 0 ? l.Fat / total * 100m : 0m;
            acum += part;
            var classe = itens.Count == 0 || acum <= a + 0.0001m ? "A" : acum <= b + 0.0001m ? "B" : "C";
            var mediaDia = l.Qtd / dias;
            itens.Add(new CurvaAbcItem(l.Id, l.Codigo, l.Nome, classe, l.Qtd, l.Fat, Math.Round(part, 2), Math.Round(acum, 2),
                l.Saldo, mediaDia > 0 ? Math.Round(l.Saldo / mediaDia, 1) : null));
        }

        var classes = new[] { "A", "B", "C" }.Select(c =>
        {
            var grupo = itens.Where(i => i.Classe == c).ToList();
            var fat = grupo.Sum(i => i.Faturamento);
            return new CurvaAbcClasse(c, grupo.Count, fat, total > 0 ? Math.Round(fat / total * 100m, 2) : 0m);
        }).ToList();
        return (new CurvaAbcResultado(inicio, fim, total, itens, classes), null);
    }

    // ------------------------------------------------------------------ crítico

    public async Task<List<CriticoItem>> CriticoAsync(string companyId, bool comValores)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            $"""
            SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial,
                   COALESCE(NULLIF(f.FantasyName, N''), NULLIF(f.CompanyName, N''), N'{SemFornecedor}'),
                   p.ProductQnt, p.EstoqueMinimo, p.ProductUnitPrice
            FROM Produtos p LEFT JOIN Fornecedores f ON f.Id = p.SupplierId
            WHERE p.CompanyId = @C AND (p.ProductQnt <= 0 OR (p.EstoqueMinimo > 0 AND p.ProductQnt < p.EstoqueMinimo))
            ORDER BY CASE WHEN p.ProductQnt <= 0 THEN 0 ELSE 1 END, p.ProductName;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        var list = new List<CriticoItem>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            var saldo = r.GetDecimal(5);
            var minimo = r.GetDecimal(6);
            var custo = r.GetDecimal(7);
            var faltam = Math.Max(0m, minimo - Math.Max(saldo, 0m));
            list.Add(new CriticoItem(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), r.GetString(4), saldo, minimo,
                faltam, saldo <= 0 ? "zerado" : "abaixo do mínimo", comValores ? Math.Round(faltam * custo, 2) : null));
        }
        return list;
    }

    // ------------------------------------------------------------------ parado

    public async Task<List<ParadoItem>> ParadoAsync(string companyId, int dias, bool comValores)
    {
        dias = Math.Clamp(dias, 1, 3650);
        var limite = DateTimeOffset.UtcNow.AddDays(-dias);
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            $"""
            SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.ProductQnt, p.ProductUnitPrice,
                   (SELECT MAX(v.SaleDate) FROM VendaItens vi JOIN Vendas v ON v.Id = vi.VendaId
                     WHERE v.CompanyId = p.CompanyId AND vi.ProductCode = p.ProductCode AND {VendaValida}) AS UltimaVenda,
                   (SELECT MAX(x.Em) FROM (
                        SELECT CAST(m.CriadoEm AS DATETIMEOFFSET) AS Em FROM EstoqueMovimentacoes m
                         WHERE m.CompanyId = p.CompanyId AND m.ProdutoId = p.Id AND m.Tipo = N'entrada'
                        UNION ALL
                        SELECT l.CriadoEm FROM ProdutoLotes l WHERE l.CompanyId = p.CompanyId AND l.ProdutoId = p.Id
                        UNION ALL
                        SELECT n.CriadaEm FROM NotasEntradaArquivo n
                         WHERE n.CompanyId = p.CompanyId AND ISNULL(n.Estornada, 0) = 0
                           AND CHARINDEX(N'"' + p.ProductCode + N'"', n.EntradaJson) > 0) x) AS UltimaEntrada
            FROM Produtos p
            WHERE p.CompanyId = @C AND p.ProductQnt > 0;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        var agora = DateTimeOffset.UtcNow;
        var list = new List<ParadoItem>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            DateTimeOffset? venda = r.IsDBNull(6) ? null : r.GetDateTimeOffset(6);
            DateTimeOffset? entrada = r.IsDBNull(7) ? null : r.GetDateTimeOffset(7);
            if (venda is not null && venda > limite) continue;
            var saldo = r.GetDecimal(4);
            var custo = r.GetDecimal(5);
            list.Add(new ParadoItem(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), saldo, venda, entrada,
                venda is null ? null : (int)Math.Floor((agora - venda.Value).TotalDays), venda is null,
                comValores ? custo : null, comValores ? Math.Round(saldo * custo, 2) : null));
        }

        return list.OrderByDescending(i => i.NuncaVendido).ThenByDescending(i => i.DiasSemVenda ?? 0).ThenBy(i => i.Nome).ToList();
    }

    // ------------------------------------------------------------------ validade

    public async Task<List<ValidadeItem>> ValidadeAsync(string companyId, int dias, bool comValores)
    {
        dias = Math.Clamp(dias, 0, 3650);
        var hoje = HorusDateTime.NowDateTime.Date;
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            """
            SELECT p.Id, p.ProductCode, p.ProductName, l.NumeroLote, l.DataValidade, ISNULL(l.QtdAtual, 0), p.ProductUnitPrice
            FROM ProdutoLotes l JOIN Produtos p ON p.Id = l.ProdutoId AND p.CompanyId = l.CompanyId
            WHERE l.CompanyId = @C AND ISNULL(l.QtdAtual, 0) > 0 AND l.DataValidade <= @Limite
            ORDER BY l.DataValidade, p.ProductName;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Limite", hoje.AddDays(dias));
        var list = new List<ValidadeItem>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            var validade = r.GetDateTime(4).Date;
            var qtd = r.GetDecimal(5);
            var custo = r.GetDecimal(6);
            var restantes = (validade - hoje).Days;
            list.Add(new ValidadeItem(r.GetString(0), r.GetString(1), r.GetString(2), r.IsDBNull(3) ? null : r.GetString(3),
                validade, restantes, qtd, restantes < 0, comValores ? custo : null, comValores ? Math.Round(qtd * custo, 2) : null));
        }
        return list;
    }

    // ------------------------------------------------------------------ perdas

    public async Task<PerdasResultado> PerdasAsync(string companyId, DateTime? de, DateTime? ate, bool comValores)
    {
        var hoje = HorusDateTime.NowDateTime.Date;
        var fim = (ate ?? hoje).Date;
        var inicio = (de ?? new DateTime(hoje.Year, hoje.Month, 1)).Date;
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(
            """
            SELECT p.Id, p.ProductCode, p.ProductName, m.Motivo, m.UsuarioNome, m.CriadoEm, m.Quantidade, p.ProductUnitPrice
            FROM EstoqueMovimentacoes m JOIN Produtos p ON p.Id = m.ProdutoId
            WHERE m.CompanyId = @C AND m.Tipo = N'perda' AND m.CriadoEm >= @De AND m.CriadoEm < @Ate
            ORDER BY p.ProductName, m.CriadoEm;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        // CriadoEm é gravado em UTC (SYSUTCDATETIME): converte o dia local de Brasília para UTC.
        cmd.Parameters.AddWithValue("@De", InicioDoDia(inicio).UtcDateTime);
        cmd.Parameters.AddWithValue("@Ate", InicioDoDia(fim.AddDays(1)).UtcDateTime);
        var list = new List<PerdaItem>();
        await using var r = await cmd.ExecuteReaderAsync();
        while (await r.ReadAsync())
        {
            var qtd = r.GetDecimal(6);
            var custo = r.GetDecimal(7);
            var quando = DateTime.SpecifyKind(r.GetDateTime(5), DateTimeKind.Utc);
            list.Add(new PerdaItem(r.GetString(0), r.GetString(1), r.GetString(2), r.GetString(3), r.GetString(4),
                HorusDateTime.ToBrasilia(new DateTimeOffset(quando)).DateTime, qtd,
                comValores ? custo : null, comValores ? Math.Round(qtd * custo, 2) : null));
        }

        return new PerdasResultado(inicio, fim, list, list.Sum(i => i.Quantidade),
            comValores ? list.Sum(i => i.Valor ?? 0) : null);
    }

    private static object Cat(string? categoriaId) =>
        string.IsNullOrWhiteSpace(categoriaId) || categoriaId == "all" ? DBNull.Value : categoriaId.Trim();
}
