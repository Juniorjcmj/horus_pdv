/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EstoqueIndicadoresAB.cs
 * Objetivo: agregações por produto (D5) para a análise de estoque. O SQL soma (demanda diária, soma dos quadrados, CMV,
 *           fotografias, última saída, pedidos em trânsito); as fórmulas ficam em EstoqueCalculos (C#, testáveis).
 *           Filtros: período, categoria (com filhas), fornecedor e depósito; comparação com o período anterior.
 * Entradas: companyId + FiltroAnalise + EstoqueParametros. Saída: registros tipados (o controller aplica perfil/cache).
 */
using HORUSPDV_API.Services.Estoque;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Filtros comuns de todo indicador (spec "Filtros comuns"). Datas civis de Brasília, ambas inclusivas.</summary>
public sealed record FiltroAnalise(DateOnly De, DateOnly Ate, string? CategoriaId = null, string? FornecedorId = null, string DepositoId = "loja")
{
    public int Dias => Math.Max(1, Ate.DayNumber - De.DayNumber + 1);

    /// <summary>Período imediatamente anterior, de mesma duração.</summary>
    public FiltroAnalise Anterior() => this with { De = De.AddDays(-Dias), Ate = De.AddDays(-1) };

    public static DateOnly Hoje() => DateOnly.FromDateTime(HorusDateTime.Now.Date);

    /// <summary>Últimos N dias terminando hoje (padrão do spec: 30).</summary>
    public static FiltroAnalise UltimosDias(int dias = 30, string? categoriaId = null, string? fornecedorId = null)
        => new(Hoje().AddDays(-(Math.Max(1, dias) - 1)), Hoje(), categoriaId, fornecedorId);
}

/// <summary>Totais crus de um produto no período, vindos do SQL.</summary>
public sealed record ProdutoAgregado(
    string ProdutoId, string Codigo, string Nome, string Unidade, string? CategoriaId, string? FornecedorId,
    decimal SaldoAtual, decimal Custo, decimal Minimo, decimal Maximo, int? LeadTimeDias,
    decimal SaidasLiquidas, decimal SomaQuadrados, int DiasComVenda, decimal Cmv,
    decimal? EstoqueMedioValor, int DiasFotografados, int DiasRuptura, decimal? SaldoNoFim,
    DateTime? UltimaSaidaUtc, decimal EmTransito);

/// <summary>Produto com as fórmulas já aplicadas.</summary>
public sealed record IndicadorProduto(
    ProdutoAgregado Bruto, decimal Saldo, bool SaldoDisponivel, int Dias, decimal Consumo, decimal Desvio, int LeadTimeDias,
    decimal EstoqueSeguranca, decimal PontoPedido, decimal EstoqueAlvo, decimal Sugestao, decimal? CoberturaDias,
    decimal ValorEstoque, decimal ValorExcesso, decimal TaxaRupturaPercentual, bool EmRuptura, bool Parado,
    int? DiasSemSaida, bool HistoricoCurto)
{
    public string ProdutoId => Bruto.ProdutoId;
}

/// <summary>Indicadores consolidados. Campos nulos = "sem histórico"/"sem giro" (nunca zero falso).</summary>
public sealed record ResumoIndicadores(
    int Skus, int SkusComSaldo, decimal? ValorEstoque, decimal Cmv, decimal? EstoqueMedioValor, decimal? Giro,
    decimal? GiroAnualizado, decimal? Dio, decimal? CoberturaMediaDias, decimal? TaxaRupturaMediaPercentual,
    decimal EmRupturaAgoraPercentual, int EmRupturaAgora, int ItensParados, decimal ValorParado, int ItensExcesso,
    decimal ValorExcesso, decimal ConsumoTotalDia, string? MotivoSemGiro);

public sealed record ResumoComparado(ResumoIndicadores Atual, ResumoIndicadores? Anterior, FiltroAnalise FiltroAtual, FiltroAnalise? FiltroAnterior)
{
    public decimal? VariacaoValorEstoque => Variacao(Atual.ValorEstoque, Anterior?.ValorEstoque);
    public decimal? VariacaoCmv => Variacao(Atual.Cmv, Anterior?.Cmv);
    public decimal? VariacaoGiro => Variacao(Atual.Giro, Anterior?.Giro);
    public decimal? VariacaoEmRuptura => Variacao(Atual.EmRupturaAgoraPercentual, Anterior?.EmRupturaAgoraPercentual);

    private static decimal? Variacao(decimal? atual, decimal? anterior)
        => atual is { } a && anterior is { } b ? EstoqueCalculos.VariacaoPercentual(a, b) : null;
}

public sealed record LeadTimeFornecedor(
    string FornecedorId, string Nome, int Pedidos, decimal? MediaDias, decimal? DesvioDias, int PedidosComPrevisao,
    int PedidosNoPrazo, decimal? NoPrazoPercentual, int? PrometidoDias);

public class EstoqueIndicadoresAB(Connection connection)
{
    private static readonly HashSet<string> UnidadesFracionadas = new(StringComparer.OrdinalIgnoreCase)
    { "KG", "G", "L", "LT", "ML", "M", "MT", "M2", "M3", "CM", "TON" };

    public static bool UnidadeFracionada(string unidade) => UnidadesFracionadas.Contains((unidade ?? "").Trim());

    private static int OffsetMinutos() => (int)HorusDateTime.TimeZone.GetUtcOffset(DateTime.UtcNow).TotalMinutes;

    /// <summary>
    /// Agregações por produto. Demanda diária = saida_venda - devolucao agrupada por dia de Brasília (dias negativos contam 0);
    /// os dias sem venda não aparecem no SQL porque entram como zero nas somas (N vem do período).
    /// </summary>
    public async Task<List<ProdutoAgregado>> AgregarAsync(string companyId, FiltroAnalise f, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            ;WITH Cat AS (
                SELECT Id FROM Categorias WHERE CompanyId = @C AND Id = @Cat
                UNION ALL
                SELECT c.Id FROM Categorias c INNER JOIN Cat ON c.CategoriaPaiId = Cat.Id WHERE c.CompanyId = @C
            ),
            P AS (
                SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.CategoriaId, p.SupplierId,
                       p.ProductQnt, p.ProductUnitPrice, p.EstoqueMinimo, p.EstoqueMaximo, p.LeadTimeDias
                FROM Produtos p
                WHERE p.CompanyId = @C
                  AND (@Cat IS NULL OR p.CategoriaId IN (SELECT Id FROM Cat))
                  AND (@For IS NULL OR p.SupplierId = @For)
            ),
            Dia AS (
                SELECT m.ProdutoId, CAST(DATEADD(minute, @Off, m.CriadoEm) AS DATE) AS D,
                       SUM(CASE WHEN m.Tipo = 'saida_venda' THEN m.Quantidade ELSE -m.Quantidade END) AS Liq,
                       SUM(CASE WHEN m.Tipo = 'saida_venda' THEN m.Quantidade * ISNULL(m.CustoUnitario, p.ProductUnitPrice)
                                ELSE -m.Quantidade * ISNULL(m.CustoUnitario, p.ProductUnitPrice) END) AS Cmv
                FROM EstoqueMovimentacoes m
                INNER JOIN P p ON p.Id = m.ProdutoId
                WHERE m.CompanyId = @C AND m.DepositoId = @Dep AND m.CriadoEm >= @IniUtc AND m.CriadoEm < @FimUtc
                  AND m.Tipo IN ('saida_venda', 'devolucao')
                GROUP BY m.ProdutoId, CAST(DATEADD(minute, @Off, m.CriadoEm) AS DATE)
            ),
            Dem AS (
                SELECT ProdutoId,
                       SUM(CASE WHEN Liq > 0 THEN Liq ELSE 0 END) AS Soma,
                       SUM(CASE WHEN Liq > 0 THEN Liq * Liq ELSE 0 END) AS SomaQ,
                       SUM(CASE WHEN Liq > 0 THEN 1 ELSE 0 END) AS DiasVenda,
                       SUM(Cmv) AS Cmv
                FROM Dia GROUP BY ProdutoId
            ),
            Snap AS (
                SELECT ProdutoId, AVG(CAST(Valor AS DECIMAL(18,4))) AS MedVal, COUNT(*) AS Dias,
                       SUM(CASE WHEN Quantidade <= 0 THEN 1 ELSE 0 END) AS Rup
                FROM EstoqueSnapshotDiario
                WHERE CompanyId = @C AND DepositoId = @Dep AND Data >= @De AND Data <= @Ate
                GROUP BY ProdutoId
            ),
            Fim AS (
                SELECT ProdutoId, Quantidade FROM EstoqueSnapshotDiario
                WHERE CompanyId = @C AND DepositoId = @Dep AND Data = @Ate
            ),
            Ult AS (
                SELECT ProdutoId, MAX(CriadoEm) AS Ultima
                FROM EstoqueMovimentacoes
                WHERE CompanyId = @C AND DepositoId = @Dep AND Tipo = 'saida_venda'
                GROUP BY ProdutoId
            ),
            Tr AS (
                SELECT pr.Id AS ProdutoId, SUM(CASE WHEN i.Quantity > i.QuantityReceived THEN i.Quantity - i.QuantityReceived ELSE 0 END) AS Qtd
                FROM OrdemCompra oc
                INNER JOIN OrdemCompraItens i ON i.OrdemCompraId = oc.Id
                INNER JOIN Produtos pr ON pr.CompanyId = oc.CompanyId AND pr.ProductCode = i.ProductCode
                WHERE oc.CompanyId = @C AND oc.Status IN (0, 3)
                GROUP BY pr.Id
            )
            SELECT p.Id, p.ProductCode, p.ProductName, p.UnidadeComercial, p.CategoriaId, p.SupplierId,
                   p.ProductQnt, p.ProductUnitPrice, ISNULL(p.EstoqueMinimo, 0), ISNULL(p.EstoqueMaximo, 0), p.LeadTimeDias,
                   ISNULL(d.Soma, 0), ISNULL(d.SomaQ, 0), ISNULL(d.DiasVenda, 0), ISNULL(d.Cmv, 0),
                   s.MedVal, ISNULL(s.Dias, 0), ISNULL(s.Rup, 0), f.Quantidade, u.Ultima, ISNULL(t.Qtd, 0)
            FROM P p
            LEFT JOIN Dem d ON d.ProdutoId = p.Id
            LEFT JOIN Snap s ON s.ProdutoId = p.Id
            LEFT JOIN Fim f ON f.ProdutoId = p.Id
            LEFT JOIN Ult u ON u.ProdutoId = p.Id
            LEFT JOIN Tr t ON t.ProdutoId = p.Id
            ORDER BY p.ProductName
            OPTION (MAXRECURSION 30);
            """, db);
        cmd.CommandTimeout = 60;
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Cat", (object?)Normalizar(f.CategoriaId) ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@For", (object?)Normalizar(f.FornecedorId) ?? DBNull.Value);
        cmd.Parameters.AddWithValue("@Dep", f.DepositoId);
        cmd.Parameters.AddWithValue("@Off", OffsetMinutos());
        cmd.Parameters.AddWithValue("@IniUtc", EstoqueSnapshotAB.FimDoDiaUtc(f.De.AddDays(-1)));
        cmd.Parameters.AddWithValue("@FimUtc", EstoqueSnapshotAB.FimDoDiaUtc(f.Ate));
        cmd.Parameters.AddWithValue("@De", f.De.ToDateTime(TimeOnly.MinValue));
        cmd.Parameters.AddWithValue("@Ate", f.Ate.ToDateTime(TimeOnly.MinValue));

        var lista = new List<ProdutoAgregado>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct))
        {
            lista.Add(new ProdutoAgregado(
                r.GetString(0), r.GetString(1), r.GetString(2), r.IsDBNull(3) ? "UN" : r.GetString(3),
                r.IsDBNull(4) ? null : r.GetString(4), r.IsDBNull(5) ? null : r.GetString(5),
                r.GetDecimal(6), r.GetDecimal(7), r.GetDecimal(8), r.GetDecimal(9), r.IsDBNull(10) ? null : r.GetInt32(10),
                r.GetDecimal(11), r.GetDecimal(12), r.GetInt32(13), r.GetDecimal(14),
                r.IsDBNull(15) ? null : r.GetDecimal(15), r.GetInt32(16), r.GetInt32(17),
                r.IsDBNull(18) ? null : r.GetDecimal(18), r.IsDBNull(19) ? null : r.GetDateTime(19), r.GetDecimal(20)));
        }
        return lista;
    }

    private static string? Normalizar(string? v) => string.IsNullOrWhiteSpace(v) ? null : v.Trim();

    /// <summary>Aplica as fórmulas (EstoqueCalculos) a um produto agregado.</summary>
    public static IndicadorProduto Calcular(
        ProdutoAgregado b, FiltroAnalise f, EstoqueParametros p, IReadOnlyDictionary<string, LeadTimeFornecedor>? leadTimes = null,
        DateOnly? hoje = null)
    {
        var hojeDia = hoje ?? FiltroAnalise.Hoje();
        var dias = f.Dias;
        // Período que termina antes de hoje usa o saldo da fotografia do último dia; senão, o saldo atual.
        var saldoDisponivel = f.Ate >= hojeDia || b.SaldoNoFim is not null;
        var saldo = f.Ate >= hojeDia ? b.SaldoAtual : b.SaldoNoFim ?? 0m;

        var consumo = EstoqueCalculos.ConsumoMedio(b.SaidasLiquidas, dias);
        var desvio = EstoqueCalculos.DesvioPadraoDemanda(b.SaidasLiquidas, b.SomaQuadrados, dias);

        LeadTimeFornecedor? lt = b.FornecedorId is not null && leadTimes is not null && leadTimes.TryGetValue(b.FornecedorId, out var x) ? x : null;
        var leadTime = EstoqueCalculos.LeadTimeEfetivo(b.LeadTimeDias, lt?.MediaDias, lt?.PrometidoDias, p.LeadTimePadraoDias);

        var seguranca = EstoqueCalculos.EstoqueSeguranca(p.NivelServicoZ, desvio, leadTime);
        var rop = EstoqueCalculos.PontoPedido(consumo, leadTime, seguranca);
        var alvo = EstoqueCalculos.EstoqueAlvo(consumo, leadTime, p.CoberturaAlvoDias, seguranca);
        var historicoCurto = dias < 14;
        // Histórico curto: não sugere compra só pelo desvio — usa o alvo sem segurança.
        var sugestao = EstoqueCalculos.SugestaoCompra(historicoCurto ? alvo - seguranca : alvo, saldo, b.EmTransito, UnidadeFracionada(b.Unidade));

        int? diasSemSaida = b.UltimaSaidaUtc is { } u
            ? Math.Max(0, hojeDia.DayNumber - DateOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(u, DateTimeKind.Utc), HorusDateTime.TimeZone)).DayNumber)
            : null;
        var parado = saldo > 0 && (diasSemSaida is null || diasSemSaida > p.DiasParado);

        return new IndicadorProduto(
            b, saldo, saldoDisponivel, dias, consumo, desvio, leadTime, seguranca, rop, alvo, sugestao,
            EstoqueCalculos.Cobertura(saldo, consumo),
            EstoqueCalculos.ValorEmEstoque(saldo, b.Custo),
            EstoqueCalculos.ValorExcesso(saldo, consumo, b.Custo, p.DiasExcesso),
            EstoqueCalculos.TaxaRupturaPercentual(b.DiasRuptura, Math.Max(b.DiasFotografados, 1)),
            saldo <= 0, parado, diasSemSaida, historicoCurto);
    }

    /// <summary>Calcula todos os produtos do filtro (agrega + fórmulas).</summary>
    public async Task<List<IndicadorProduto>> ProdutosAsync(string companyId, FiltroAnalise f, EstoqueParametros p, CancellationToken ct = default)
    {
        var brutos = await AgregarAsync(companyId, f, ct);
        var lead = await LeadTimeFornecedoresAsync(companyId, ct);
        var mapa = lead.ToDictionary(l => l.FornecedorId, StringComparer.Ordinal);
        return brutos.Select(b => Calcular(b, f, p, mapa)).ToList();
    }

    /// <summary>Consolida os indicadores do período (cards do dashboard).</summary>
    public static ResumoIndicadores Consolidar(IReadOnlyList<IndicadorProduto> itens, FiltroAnalise f, EstoqueParametros p)
    {
        var comSaldo = itens.Where(i => i.SaldoDisponivel).ToList();
        var temFoto = itens.Any(i => i.Bruto.DiasFotografados > 0);
        decimal? valor = comSaldo.Count == 0 ? null : comSaldo.Sum(i => i.ValorEstoque);
        var cmv = itens.Sum(i => i.Bruto.Cmv);
        decimal? medio = temFoto ? itens.Where(i => i.Bruto.EstoqueMedioValor is not null).Sum(i => i.Bruto.EstoqueMedioValor!.Value) : null;
        var giro = EstoqueCalculos.Giro(cmv, medio);
        var anual = EstoqueCalculos.GiroAnualizado(giro, f.Dias);
        var dio = EstoqueCalculos.Dio(anual);
        var cobs = itens.Where(i => i.CoberturaDias is not null && i.SaldoDisponivel).Select(i => i.CoberturaDias!.Value).ToList();
        var fotografados = itens.Where(i => i.Bruto.DiasFotografados > 0).ToList();
        var emRuptura = comSaldo.Count(i => i.EmRuptura);
        var parados = comSaldo.Where(i => i.Parado).ToList();
        var excesso = comSaldo.Where(i => i.ValorExcesso > 0).ToList();
        string? motivo = !temFoto ? "sem histórico" : giro is null ? "sem giro" : null;

        return new ResumoIndicadores(
            itens.Count, comSaldo.Count(i => i.Saldo > 0), valor, cmv, medio, giro, anual, dio,
            cobs.Count == 0 ? null : cobs.Average(),
            fotografados.Count == 0 ? null : fotografados.Average(i => i.TaxaRupturaPercentual),
            comSaldo.Count == 0 ? 0m : 100m * emRuptura / comSaldo.Count, emRuptura,
            parados.Count, parados.Sum(i => i.ValorEstoque), excesso.Count, excesso.Sum(i => i.ValorExcesso),
            itens.Sum(i => i.Consumo), motivo);
    }

    /// <summary>Resumo do período e, quando pedido, o do período anterior de mesma duração com as variações.</summary>
    public async Task<ResumoComparado> ResumoAsync(string companyId, FiltroAnalise f, EstoqueParametros p, bool comparar, CancellationToken ct = default)
    {
        var atual = Consolidar(await ProdutosAsync(companyId, f, p, ct), f, p);
        if (!comparar) return new ResumoComparado(atual, null, f, null);
        var fa = f.Anterior();
        var anterior = Consolidar(await ProdutosAsync(companyId, fa, p, ct), fa, p);
        return new ResumoComparado(atual, anterior, f, fa);
    }

    /// <summary>
    /// Lead time real por fornecedor: média/desvio (dias) entre criação e recebimento dos pedidos recebidos, e % recebidos até a
    /// previsão (pedidos sem previsão entram na média, não no "no prazo"). Inclui o prometido cadastrado.
    /// </summary>
    public async Task<List<LeadTimeFornecedor>> LeadTimeFornecedoresAsync(string companyId, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT f.Id, ISNULL(NULLIF(f.FantasyName, ''), f.CompanyName), f.LeadTimePrometidoDias,
                   COUNT(oc.Id),
                   AVG(CAST(DATEDIFF(minute, oc.CreatedAt, oc.ReceivedAt) AS FLOAT) / 1440.0),
                   STDEVP(CAST(DATEDIFF(minute, oc.CreatedAt, oc.ReceivedAt) AS FLOAT) / 1440.0),
                   SUM(CASE WHEN oc.PrevisaoEntrega IS NOT NULL THEN 1 ELSE 0 END),
                   SUM(CASE WHEN oc.PrevisaoEntrega IS NOT NULL AND oc.ReceivedAt <= oc.PrevisaoEntrega THEN 1 ELSE 0 END)
            FROM Fornecedores f
            LEFT JOIN OrdemCompra oc ON oc.SupplierId = f.Id AND oc.CompanyId = f.CompanyId
                 AND oc.Status IN (1, 3) AND oc.ReceivedAt IS NOT NULL
            WHERE f.CompanyId = @C
            GROUP BY f.Id, ISNULL(NULLIF(f.FantasyName, ''), f.CompanyName), f.LeadTimePrometidoDias
            ORDER BY 2;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        var lista = new List<LeadTimeFornecedor>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct))
        {
            var pedidos = r.GetInt32(3);
            var comPrev = r.IsDBNull(6) ? 0 : r.GetInt32(6);
            var noPrazo = r.IsDBNull(7) ? 0 : r.GetInt32(7);
            lista.Add(new LeadTimeFornecedor(
                r.GetString(0), r.IsDBNull(1) ? "" : r.GetString(1), pedidos,
                pedidos == 0 || r.IsDBNull(4) ? null : Math.Round((decimal)r.GetDouble(4), 2),
                pedidos == 0 || r.IsDBNull(5) ? null : Math.Round((decimal)r.GetDouble(5), 2),
                comPrev, noPrazo, comPrev == 0 ? null : Math.Round(100m * noPrazo / comPrev, 1),
                r.IsDBNull(2) ? null : r.GetInt32(2)));
        }
        return lista;
    }

    /// <summary>Contagens aplicadas no período (sistema x contado) para a acuracidade do inventário.</summary>
    public async Task<AcuraciaResultado?> AcuraciaAsync(string companyId, FiltroAnalise f, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT it.SaldoAntes, it.Contado
            FROM InventarioItens it
            INNER JOIN Inventarios i ON i.Id = it.InventarioId
            WHERE i.CompanyId = @C AND i.Status = 'aplicado' AND it.Contado IS NOT NULL AND it.SaldoAntes IS NOT NULL
              AND i.AplicadoEm >= @Ini AND i.AplicadoEm < @Fim;
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Ini", EstoqueSnapshotAB.FimDoDiaUtc(f.De.AddDays(-1)));
        cmd.Parameters.AddWithValue("@Fim", EstoqueSnapshotAB.FimDoDiaUtc(f.Ate));
        var lista = new List<(decimal, decimal)>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct)) lista.Add((r.GetDecimal(0), r.GetDecimal(1)));
        return EstoqueCalculos.Acuracidade(lista);
    }
}
