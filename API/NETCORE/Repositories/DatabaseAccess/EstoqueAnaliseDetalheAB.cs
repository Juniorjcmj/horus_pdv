/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EstoqueAnaliseDetalheAB.cs
 * Objetivo: consultas da etapa 5 da análise de estoque: detalhe e extrato de um produto, contagem cíclica, acuracidade e
 *           divergências dos inventários aplicados, perdas por motivo/mês e produtos parados. Só leitura; as fórmulas
 *           ficam em EstoqueCalculos. Datas civis de Brasília; instantes gravados em UTC.
 */
using HORUSPDV_API.Services.Estoque;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public sealed record SaldoDia(DateOnly Data, decimal Quantidade);
public sealed record EntradaSaidaSemana(DateOnly Inicio, decimal Entradas, decimal Saidas);
public sealed record ProdutoSeries(List<SaldoDia> SaldoDiario, List<EntradaSaidaSemana> Semanal, List<decimal> DemandaDiaria);

public sealed record MovimentoExtrato(
    DateTime CriadoEmUtc, string Tipo, decimal Quantidade, decimal Anterior, decimal Novo, decimal? Custo, string? OrigemTipo,
    string? OrigemId, string? Motivo, string? Usuario);

public sealed record ItemContagemCiclica(
    string ProdutoId, string Codigo, string Nome, string Unidade, char Classe, DateTime? UltimaContagemUtc, int? DiasDesdeUltima);

public sealed record PontoAcuracidade(string InventarioId, int Numero, DateTime AplicadoEmUtc, int Itens, decimal Acuracidade, decimal ItensExatosPercentual);

public sealed record DivergenciaItem(
    string ProdutoId, string Codigo, string Nome, int Numero, DateTime AplicadoEmUtc, decimal SaldoAntes, decimal Contado, decimal Delta, decimal ValorDelta);

public sealed record PerdaMovimento(DateOnly Data, string Motivo, decimal Quantidade, decimal Valor, string ProdutoId, string Codigo, string Nome);

public class EstoqueAnaliseDetalheAB(Connection connection)
{
    private static int OffsetMinutos() => (int)HorusDateTime.TimeZone.GetUtcOffset(DateTime.UtcNow).TotalMinutes;

    private static (DateTime Ini, DateTime Fim) Janela(FiltroAnalise f)
        => (EstoqueSnapshotAB.FimDoDiaUtc(f.De.AddDays(-1)), EstoqueSnapshotAB.FimDoDiaUtc(f.Ate));

    /// <summary>Saldo diário (fotografias), entradas x saídas por semana e a demanda de cada dia do período (zeros incluídos).</summary>
    public async Task<ProdutoSeries> SeriesDoProdutoAsync(string companyId, string produtoId, FiltroAnalise f, CancellationToken ct = default)
    {
        var (ini, fim) = Janela(f);
        await using var db = await connection.OpenConnectionAsync(ct);

        var saldo = new List<SaldoDia>();
        await using (var cmd = new SqlCommand(
            "SELECT Data, Quantidade FROM EstoqueSnapshotDiario WHERE CompanyId=@C AND DepositoId=@Dep AND ProdutoId=@P AND Data>=@De AND Data<=@Ate ORDER BY Data;", db))
        {
            Parametros(cmd, companyId, produtoId, f, ini, fim);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) saldo.Add(new SaldoDia(DateOnly.FromDateTime(r.GetDateTime(0)), r.GetDecimal(1)));
        }

        var semanas = new Dictionary<int, (decimal Ent, decimal Sai)>();
        await using (var cmd = new SqlCommand(
            """
            SELECT DATEDIFF(day, @De, CAST(DATEADD(minute, @Off, CriadoEm) AS DATE)) / 7,
                   SUM(CASE WHEN EstoqueNovo > EstoqueAnterior THEN EstoqueNovo - EstoqueAnterior ELSE 0 END),
                   SUM(CASE WHEN EstoqueNovo < EstoqueAnterior THEN EstoqueAnterior - EstoqueNovo ELSE 0 END)
            FROM EstoqueMovimentacoes
            WHERE CompanyId=@C AND DepositoId=@Dep AND ProdutoId=@P AND CriadoEm>=@Ini AND CriadoEm<@Fim
            GROUP BY DATEDIFF(day, @De, CAST(DATEADD(minute, @Off, CriadoEm) AS DATE)) / 7;
            """, db))
        {
            Parametros(cmd, companyId, produtoId, f, ini, fim);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) semanas[r.GetInt32(0)] = (r.GetDecimal(1), r.GetDecimal(2));
        }
        var semanal = new List<EntradaSaidaSemana>();
        for (var i = 0; i <= (f.Dias - 1) / 7; i++)
            semanal.Add(new EntradaSaidaSemana(f.De.AddDays(i * 7), semanas.GetValueOrDefault(i).Ent, semanas.GetValueOrDefault(i).Sai));

        var porDia = new Dictionary<DateOnly, decimal>();
        await using (var cmd = new SqlCommand(
            """
            SELECT CAST(DATEADD(minute, @Off, CriadoEm) AS DATE),
                   SUM(CASE WHEN Tipo = 'saida_venda' THEN Quantidade ELSE -Quantidade END)
            FROM EstoqueMovimentacoes
            WHERE CompanyId=@C AND DepositoId=@Dep AND ProdutoId=@P AND Tipo IN ('saida_venda','devolucao') AND CriadoEm>=@Ini AND CriadoEm<@Fim
            GROUP BY CAST(DATEADD(minute, @Off, CriadoEm) AS DATE);
            """, db))
        {
            Parametros(cmd, companyId, produtoId, f, ini, fim);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) porDia[DateOnly.FromDateTime(r.GetDateTime(0))] = Math.Max(0m, r.GetDecimal(1));
        }
        var demanda = Enumerable.Range(0, f.Dias).Select(i => porDia.GetValueOrDefault(f.De.AddDays(i))).ToList();
        return new ProdutoSeries(saldo, semanal, demanda);
    }

    private static void Parametros(SqlCommand cmd, string companyId, string produtoId, FiltroAnalise f, DateTime ini, DateTime fim)
    {
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@P", produtoId);
        cmd.Parameters.AddWithValue("@Dep", f.DepositoId);
        cmd.Parameters.AddWithValue("@Off", OffsetMinutos());
        cmd.Parameters.AddWithValue("@Ini", ini);
        cmd.Parameters.AddWithValue("@Fim", fim);
        cmd.Parameters.AddWithValue("@De", f.De.ToDateTime(TimeOnly.MinValue));
        cmd.Parameters.AddWithValue("@Ate", f.Ate.ToDateTime(TimeOnly.MinValue));
    }

    /// <summary>Extrato do produto no período, do mais recente ao mais antigo, paginado.</summary>
    public async Task<(int Total, List<MovimentoExtrato> Itens)> ExtratoAsync(
        string companyId, string produtoId, FiltroAnalise f, int pagina, int tamanho, CancellationToken ct = default)
    {
        var (ini, fim) = Janela(f);
        await using var db = await connection.OpenConnectionAsync(ct);
        int total;
        await using (var cmd = new SqlCommand(
            "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId=@C AND DepositoId=@Dep AND ProdutoId=@P AND CriadoEm>=@Ini AND CriadoEm<@Fim;", db))
        {
            Parametros(cmd, companyId, produtoId, f, ini, fim);
            total = Convert.ToInt32(await cmd.ExecuteScalarAsync(ct));
        }
        var lista = new List<MovimentoExtrato>();
        await using (var cmd = new SqlCommand(
            """
            SELECT CriadoEm, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, CustoUnitario, OrigemTipo, OrigemId, Motivo, UsuarioNome
            FROM EstoqueMovimentacoes
            WHERE CompanyId=@C AND DepositoId=@Dep AND ProdutoId=@P AND CriadoEm>=@Ini AND CriadoEm<@Fim
            ORDER BY CriadoEm DESC, Id DESC OFFSET @Skip ROWS FETCH NEXT @Take ROWS ONLY;
            """, db))
        {
            Parametros(cmd, companyId, produtoId, f, ini, fim);
            cmd.Parameters.AddWithValue("@Skip", Math.Max(0, pagina - 1) * tamanho);
            cmd.Parameters.AddWithValue("@Take", tamanho);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct))
                lista.Add(new MovimentoExtrato(r.GetDateTime(0), r.GetString(1), r.GetDecimal(2), r.GetDecimal(3), r.GetDecimal(4),
                    r.IsDBNull(5) ? null : r.GetDecimal(5), r.IsDBNull(6) ? null : r.GetString(6), r.IsDBNull(7) ? null : r.GetString(7),
                    r.IsDBNull(8) ? null : r.GetString(8), r.IsDBNull(9) ? null : r.GetString(9)));
        }
        return (total, lista);
    }

    /// <summary>
    /// Lista do dia da contagem cíclica: A primeiro, depois B e C, os nunca contados e os mais antigos antes; só entra quem passou
    /// da periodicidade da classe (A 30, B 90, C 180 dias, configuráveis). Produto sem classificação conta como C.
    /// </summary>
    public async Task<List<ItemContagemCiclica>> ContagemCiclicaAsync(string companyId, EstoqueParametros p, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT pr.Id, pr.ProductCode, pr.ProductName, pr.UnidadeComercial, ISNULL(k.Abc, 'C'), u.Ultima
            FROM Produtos pr
            LEFT JOIN ProdutoClassificacao k ON k.CompanyId = pr.CompanyId AND k.ProdutoId = pr.Id
            LEFT JOIN (
                SELECT it.ProdutoId, MAX(i.AplicadoEm) AS Ultima
                FROM InventarioItens it INNER JOIN Inventarios i ON i.Id = it.InventarioId
                WHERE i.CompanyId = @C AND i.Status = 'aplicado' AND it.Contado IS NOT NULL
                GROUP BY it.ProdutoId) u ON u.ProdutoId = pr.Id
            WHERE pr.CompanyId = @C;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        var todos = new List<ItemContagemCiclica>();
        var agora = DateTime.UtcNow;
        await using (var r = await cmd.ExecuteReaderAsync(ct))
            while (await r.ReadAsync(ct))
            {
                var classe = r.GetString(4)[0];
                var ultima = r.IsDBNull(5) ? (DateTime?)null : r.GetDateTimeOffset(5).UtcDateTime;
                todos.Add(new ItemContagemCiclica(r.GetString(0), r.GetString(1), r.GetString(2), r.IsDBNull(3) ? "UN" : r.GetString(3), classe, ultima,
                    ultima is null ? null : (int)(agora - ultima.Value).TotalDays));
            }
        int Periodicidade(char c) => c == 'A' ? p.ContagemDiasA : c == 'B' ? p.ContagemDiasB : p.ContagemDiasC;
        return todos
            .Where(i => i.DiasDesdeUltima is null || i.DiasDesdeUltima >= Periodicidade(i.Classe))
            .OrderBy(i => i.Classe).ThenBy(i => i.UltimaContagemUtc ?? DateTime.MinValue).ThenBy(i => i.Nome)
            .Take(p.ContagemItensDia).ToList();
    }

    /// <summary>Uma linha por inventário aplicado no período: acuracidade (Σ min ÷ Σ sistema) e % de itens exatos.</summary>
    public async Task<List<PontoAcuracidade>> AcuracidadePorInventarioAsync(string companyId, FiltroAnalise f, CancellationToken ct = default)
    {
        var (ini, fim) = Janela(f);
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT i.Id, i.Numero, i.AplicadoEm, it.SaldoAntes, it.Contado
            FROM Inventarios i INNER JOIN InventarioItens it ON it.InventarioId = i.Id
            WHERE i.CompanyId=@C AND i.Status='aplicado' AND it.Contado IS NOT NULL AND it.SaldoAntes IS NOT NULL
              AND i.AplicadoEm >= @Ini AND i.AplicadoEm < @Fim
            ORDER BY i.AplicadoEm, i.Id;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Ini", ini);
        cmd.Parameters.AddWithValue("@Fim", fim);
        var grupos = new Dictionary<string, (int Numero, DateTime Em, List<(decimal, decimal)> Itens)>(StringComparer.Ordinal);
        var ordem = new List<string>();
        await using (var r = await cmd.ExecuteReaderAsync(ct))
            while (await r.ReadAsync(ct))
            {
                var id = r.GetString(0);
                if (!grupos.TryGetValue(id, out var g)) { g = (r.GetInt32(1), r.GetDateTimeOffset(2).UtcDateTime, []); grupos[id] = g; ordem.Add(id); }
                g.Itens.Add((r.GetDecimal(3), r.GetDecimal(4)));
            }
        var lista = new List<PontoAcuracidade>();
        foreach (var id in ordem)
        {
            var g = grupos[id];
            if (EstoqueCalculos.Acuracidade(g.Itens) is { } ac)
                lista.Add(new PontoAcuracidade(id, g.Numero, g.Em, ac.Itens, Math.Round(ac.AcuraciaPercentual, 2), Math.Round(ac.ItensExatosPercentual, 1)));
        }
        return lista;
    }

    /// <summary>Itens com divergência (contado diferente do sistema) nos inventários aplicados do período.</summary>
    public async Task<List<DivergenciaItem>> DivergenciasAsync(string companyId, FiltroAnalise f, CancellationToken ct = default)
    {
        var (ini, fim) = Janela(f);
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT it.ProdutoId, it.Codigo, it.Nome, i.Numero, i.AplicadoEm, it.SaldoAntes, it.Contado, it.Contado - it.SaldoAntes,
                   (it.Contado - it.SaldoAntes) * it.Custo
            FROM Inventarios i INNER JOIN InventarioItens it ON it.InventarioId = i.Id
            WHERE i.CompanyId=@C AND i.Status='aplicado' AND it.Contado IS NOT NULL AND it.SaldoAntes IS NOT NULL
              AND it.Contado <> it.SaldoAntes AND i.AplicadoEm >= @Ini AND i.AplicadoEm < @Fim
            ORDER BY ABS((it.Contado - it.SaldoAntes) * it.Custo) DESC;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Ini", ini);
        cmd.Parameters.AddWithValue("@Fim", fim);
        var lista = new List<DivergenciaItem>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct))
            lista.Add(new DivergenciaItem(r.GetString(0), r.GetString(1), r.GetString(2), r.GetInt32(3), r.GetDateTimeOffset(4).UtcDateTime,
                r.GetDecimal(5), r.GetDecimal(6), r.GetDecimal(7), r.GetDecimal(8)));
        return lista;
    }

    /// <summary>
    /// Perdas do período: movimentações "perda", faltas de inventário e ajustes negativos, em quantidade e valor (custo da
    /// movimentação, senão o atual). Filtra categoria (com filhas) e fornecedor.
    /// </summary>
    public async Task<List<PerdaMovimento>> PerdasAsync(string companyId, FiltroAnalise f, CancellationToken ct = default)
    {
        var (ini, fim) = Janela(f);
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            ;WITH Cat AS (
                SELECT Id FROM Categorias WHERE CompanyId = @C AND Id = @Cat
                UNION ALL
                SELECT c.Id FROM Categorias c INNER JOIN Cat ON c.CategoriaPaiId = Cat.Id WHERE c.CompanyId = @C
            )
            SELECT CAST(DATEADD(minute, @Off, m.CriadoEm) AS DATE),
                   CASE WHEN m.Tipo = 'inventario' THEN N'Inventário (falta)'
                        WHEN m.Tipo = 'saida' THEN N'Ajuste negativo'
                        ELSE ISNULL(NULLIF(LTRIM(RTRIM(m.Motivo)), ''), N'Sem motivo') END,
                   ABS(m.EstoqueNovo - m.EstoqueAnterior),
                   ABS(m.EstoqueNovo - m.EstoqueAnterior) * ISNULL(m.CustoUnitario, p.ProductUnitPrice),
                   p.Id, p.ProductCode, p.ProductName
            FROM EstoqueMovimentacoes m INNER JOIN Produtos p ON p.Id = m.ProdutoId
            WHERE m.CompanyId = @C AND m.DepositoId = @Dep AND m.CriadoEm >= @Ini AND m.CriadoEm < @Fim
              AND (m.Tipo = 'perda' OR (m.EstoqueNovo < m.EstoqueAnterior AND (m.Tipo = 'inventario' OR (m.Tipo = 'saida' AND m.OrigemTipo IN ('ajuste', 'cadastro')))))
              AND (@Cat IS NULL OR p.CategoriaId IN (SELECT Id FROM Cat))
              AND (@For IS NULL OR p.SupplierId = @For)
            OPTION (MAXRECURSION 30);
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Dep", f.DepositoId);
        cmd.Parameters.AddWithValue("@Off", OffsetMinutos());
        cmd.Parameters.AddWithValue("@Ini", ini);
        cmd.Parameters.AddWithValue("@Fim", fim);
        cmd.Parameters.AddWithValue("@Cat", string.IsNullOrWhiteSpace(f.CategoriaId) ? DBNull.Value : f.CategoriaId);
        cmd.Parameters.AddWithValue("@For", string.IsNullOrWhiteSpace(f.FornecedorId) ? DBNull.Value : f.FornecedorId);
        var lista = new List<PerdaMovimento>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct))
            lista.Add(new PerdaMovimento(DateOnly.FromDateTime(r.GetDateTime(0)), r.GetString(1), r.GetDecimal(2), r.GetDecimal(3), r.GetString(4), r.GetString(5), r.GetString(6)));
        return lista;
    }

    /// <summary>Classe ABC e FSN gravadas pelo job diário (null quando o produto ainda não foi classificado).</summary>
    public async Task<(char Abc, char Fsn)?> ClassificacaoDoProdutoAsync(string companyId, string produtoId, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand("SELECT Abc, Fsn FROM ProdutoClassificacao WHERE CompanyId=@C AND ProdutoId=@P;", db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@P", produtoId);
        await using var r = await cmd.ExecuteReaderAsync(ct);
        return await r.ReadAsync(ct) ? (r.GetString(0)[0], r.GetString(1)[0]) : null;
    }
}
