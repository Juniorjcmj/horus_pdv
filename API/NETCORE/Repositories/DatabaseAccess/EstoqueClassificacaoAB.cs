/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EstoqueClassificacaoAB.cs
 * Objetivo: classificação ABC (faturamento ou CMV, limites da configuração) e FSN (semanas com saída) por produto,
 *           gravada em ProdutoClassificacao pelo job diário, e a matriz ABC x FSN (3x3).
 */
using HORUSPDV_API.Services.Estoque;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public sealed record MatrizCelula(char Abc, char Fsn, int Produtos, decimal ValorEstoque);

public sealed record ItemCurvaAbc(
    string ProdutoId, string Codigo, string Nome, decimal Quantidade, decimal Valor, decimal Participacao, decimal Acumulada,
    char Classe, char? Fsn, decimal Saldo, decimal ValorEstoque);

public class EstoqueClassificacaoAB(Connection connection)
{
    public const int DiasClassificacaoPadrao = 90;

    /// <summary>Recalcula e grava a classificação da empresa nos últimos <paramref name="dias"/> dias. Retorna a quantidade de produtos.</summary>
    public async Task<int> RecalcularAsync(string companyId, EstoqueParametros p, int dias = DiasClassificacaoPadrao, CancellationToken ct = default)
    {
        var ate = FiltroAnalise.Hoje();
        var de = ate.AddDays(-(dias - 1));
        var iniUtc = EstoqueSnapshotAB.FimDoDiaUtc(de.AddDays(-1));
        var fimUtc = EstoqueSnapshotAB.FimDoDiaUtc(ate);
        var offset = (int)HorusDateTime.TimeZone.GetUtcOffset(DateTime.UtcNow).TotalMinutes;
        var semanasNoPeriodo = (int)Math.Ceiling(dias / 7.0);

        await using var db = await connection.OpenConnectionAsync(ct);
        var produtos = new List<string>();
        await using (var cmd = new SqlCommand("SELECT Id FROM Produtos WHERE CompanyId = @C;", db))
        {
            cmd.Parameters.AddWithValue("@C", companyId);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) produtos.Add(r.GetString(0));
        }

        var valor = new Dictionary<string, decimal>(StringComparer.Ordinal);
        var sqlValor = p.AbcBase == "cmv"
            ? """
              SELECT s.ProdutoId, SUM(s.Cmv)
              FROM SAIDAS s
              GROUP BY s.ProdutoId;
              """.Replace("SAIDAS", SaidasVendaSql.Derivada("@Ini", "@Fim"))
            : """
              SELECT pr.Id, SUM(vi.ItemTotal)
              FROM VendaItens vi
              INNER JOIN Vendas v ON v.Id = vi.VendaId
              INNER JOIN Produtos pr ON pr.CompanyId = v.CompanyId AND pr.ProductCode = vi.ProductCode
              WHERE v.CompanyId = @C AND ISNULL(v.Status, 'finalizada') NOT IN ('cancelada', 'corrigida')
                AND v.SaleDate >= @Ini AND v.SaleDate < @Fim
              GROUP BY pr.Id;
              """;
        await using (var cmd = new SqlCommand(sqlValor, db))
        {
            cmd.Parameters.AddWithValue("@C", companyId);
            cmd.Parameters.AddWithValue("@Ini", iniUtc);
            cmd.Parameters.AddWithValue("@Fim", fimUtc);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) valor[r.GetString(0)] = r.IsDBNull(1) ? 0m : r.GetDecimal(1);
        }

        var semanas = new Dictionary<string, int>(StringComparer.Ordinal);
        await using (var cmd = new SqlCommand(
            """
            SELECT s.ProdutoId, COUNT(DISTINCT DATEDIFF(day, @De, CAST(DATEADD(minute, @Off, s.Em) AS DATE)) / 7)
            FROM SAIDAS s
            WHERE s.Qtd > 0
            GROUP BY s.ProdutoId;
            """.Replace("SAIDAS", SaidasVendaSql.Derivada("@Ini", "@Fim")), db))
        {
            cmd.Parameters.AddWithValue("@C", companyId);
            cmd.Parameters.AddWithValue("@De", de.ToDateTime(TimeOnly.MinValue));
            cmd.Parameters.AddWithValue("@Off", offset);
            cmd.Parameters.AddWithValue("@Ini", iniUtc);
            cmd.Parameters.AddWithValue("@Fim", fimUtc);
            await using var r = await cmd.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) semanas[r.GetString(0)] = r.GetInt32(1);
        }

        var abc = EstoqueCalculos.ClassificarAbc(produtos.Select(id => (id, valor.GetValueOrDefault(id))), p.AbcLimiteA, p.AbcLimiteB);

        await using var tx = (SqlTransaction)await db.BeginTransactionAsync(ct);
        await using (var del = new SqlCommand("DELETE FROM ProdutoClassificacao WHERE CompanyId = @C;", db, tx))
        {
            del.Parameters.AddWithValue("@C", companyId);
            await del.ExecuteNonQueryAsync(ct);
        }
        foreach (var lote in produtos.Chunk(200))
        {
            var sql = new System.Text.StringBuilder("INSERT INTO ProdutoClassificacao (CompanyId, ProdutoId, Abc, Fsn) VALUES ");
            await using var ins = new SqlCommand { Connection = db, Transaction = tx };
            ins.Parameters.AddWithValue("@C", companyId);
            for (var i = 0; i < lote.Length; i++)
            {
                var id = lote[i];
                var fsn = EstoqueCalculos.ClassificarFsn(semanas.GetValueOrDefault(id), semanasNoPeriodo, p.FsnLimiteFast);
                sql.Append(i == 0 ? "" : ",").Append($"(@C,@P{i},'{abc[id]}','{fsn}')");
                ins.Parameters.AddWithValue($"@P{i}", id);
            }
            ins.CommandText = sql.ToString();
            await ins.ExecuteNonQueryAsync(ct);
        }
        await tx.CommitAsync(ct);
        return produtos.Count;
    }

    /// <summary>Empresas com produtos cuja classificação não foi calculada hoje (horário de Brasília).</summary>
    public async Task<List<string>> EmpresasDesatualizadasAsync(CancellationToken ct = default)
    {
        var inicioHojeUtc = EstoqueSnapshotAB.FimDoDiaUtc(FiltroAnalise.Hoje().AddDays(-1));
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT DISTINCT p.CompanyId FROM Produtos p
            WHERE NOT EXISTS (SELECT 1 FROM ProdutoClassificacao c WHERE c.CompanyId = p.CompanyId AND c.CalculadoEm >= @Ini);
            """, db);
        cmd.Parameters.AddWithValue("@Ini", inicioHojeUtc);
        var lista = new List<string>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct)) lista.Add(r.GetString(0));
        return lista;
    }

    /// <summary>Matriz ABC x FSN: sempre as 9 combinações, com quantidade de produtos e valor em estoque (saldo negativo conta 0).</summary>
    public async Task<List<MatrizCelula>> MatrizAsync(string companyId, string? categoriaId = null, string? fornecedorId = null, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            ;WITH Cat AS (
                SELECT Id FROM Categorias WHERE CompanyId = @C AND Id = @Cat
                UNION ALL
                SELECT c.Id FROM Categorias c INNER JOIN Cat ON c.CategoriaPaiId = Cat.Id WHERE c.CompanyId = @C
            )
            SELECT k.Abc, k.Fsn, COUNT(*), SUM(CASE WHEN p.ProductQnt > 0 THEN p.ProductQnt * p.ProductUnitPrice ELSE 0 END)
            FROM ProdutoClassificacao k INNER JOIN Produtos p ON p.Id = k.ProdutoId AND p.CompanyId = k.CompanyId
            WHERE k.CompanyId = @C
              AND (@Cat IS NULL OR p.CategoriaId IN (SELECT Id FROM Cat))
              AND (@For IS NULL OR p.SupplierId = @For)
            GROUP BY k.Abc, k.Fsn
            OPTION (MAXRECURSION 30);
            """, db);
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Cat", string.IsNullOrWhiteSpace(categoriaId) ? DBNull.Value : categoriaId);
        cmd.Parameters.AddWithValue("@For", string.IsNullOrWhiteSpace(fornecedorId) ? DBNull.Value : fornecedorId);
        var achados = new Dictionary<(char, char), MatrizCelula>();
        await using (var r = await cmd.ExecuteReaderAsync(ct))
            while (await r.ReadAsync(ct))
            {
                var a = r.GetString(0)[0];
                var f = r.GetString(1)[0];
                achados[(a, f)] = new MatrizCelula(a, f, r.GetInt32(2), r.IsDBNull(3) ? 0m : r.GetDecimal(3));
            }
        var matriz = new List<MatrizCelula>();
        foreach (var a in "ABC")
            foreach (var f in "FSN")
                matriz.Add(achados.TryGetValue((a, f), out var c) ? c : new MatrizCelula(a, f, 0, 0m));
        return matriz;
    }

    /// <summary>
    /// Curva ABC do período (base faturamento ou CMV da configuração) com filtros de categoria (com filhas) e fornecedor.
    /// Ordenada por valor decrescente, com participação, acumulado e classe pelos limites da configuração.
    /// </summary>
    public async Task<List<ItemCurvaAbc>> CurvaAbcAsync(string companyId, FiltroAnalise f, EstoqueParametros p, CancellationToken ct = default)
    {
        var porCmv = p.AbcBase == "cmv";
        await using var db = await connection.OpenConnectionAsync(ct);
        var valorCte = porCmv
            ? """
              SELECT s.ProdutoId AS ProdutoId, SUM(s.Qtd) AS Qtd, SUM(s.Cmv) AS Valor
              FROM SAIDAS s INNER JOIN P pr ON pr.Id = s.ProdutoId
              GROUP BY s.ProdutoId
              """.Replace("SAIDAS", SaidasVendaSql.Derivada("@Ini", "@Fim"))
            : """
              SELECT pr.Id AS ProdutoId, SUM(vi.Quantity) AS Qtd, SUM(vi.ItemTotal) AS Valor
              FROM VendaItens vi
              INNER JOIN Vendas v ON v.Id = vi.VendaId
              INNER JOIN P pr ON pr.ProductCode = vi.ProductCode
              WHERE v.CompanyId = @C AND ISNULL(v.Status, 'finalizada') NOT IN ('cancelada', 'corrigida')
                AND v.SaleDate >= @Ini AND v.SaleDate < @Fim
              GROUP BY pr.Id
              """;
        await using var cmd = new SqlCommand(
            $$"""
            ;WITH Cat AS (
                SELECT Id FROM Categorias WHERE CompanyId = @C AND Id = @Cat
                UNION ALL
                SELECT c.Id FROM Categorias c INNER JOIN Cat ON c.CategoriaPaiId = Cat.Id WHERE c.CompanyId = @C
            ),
            P AS (
                SELECT p.Id, p.ProductCode, p.ProductName, p.ProductQnt, p.ProductUnitPrice
                FROM Produtos p
                WHERE p.CompanyId = @C
                  AND (@Cat IS NULL OR p.CategoriaId IN (SELECT Id FROM Cat))
                  AND (@For IS NULL OR p.SupplierId = @For)
            ),
            V AS ({{valorCte}})
            SELECT p.Id, p.ProductCode, p.ProductName, v.Qtd, v.Valor, p.ProductQnt, p.ProductUnitPrice, k.Fsn
            FROM V v
            INNER JOIN P p ON p.Id = v.ProdutoId
            LEFT JOIN ProdutoClassificacao k ON k.CompanyId = @C AND k.ProdutoId = p.Id
            WHERE v.Valor > 0
            ORDER BY v.Valor DESC, p.ProductName
            OPTION (MAXRECURSION 30);
            """, db);
        cmd.CommandTimeout = 60;
        cmd.Parameters.AddWithValue("@C", companyId);
        cmd.Parameters.AddWithValue("@Cat", string.IsNullOrWhiteSpace(f.CategoriaId) ? DBNull.Value : f.CategoriaId);
        cmd.Parameters.AddWithValue("@For", string.IsNullOrWhiteSpace(f.FornecedorId) ? DBNull.Value : f.FornecedorId);
        cmd.Parameters.AddWithValue("@Ini", EstoqueSnapshotAB.FimDoDiaUtc(f.De.AddDays(-1)));
        cmd.Parameters.AddWithValue("@Fim", EstoqueSnapshotAB.FimDoDiaUtc(f.Ate));

        var brutos = new List<(string Id, string Codigo, string Nome, decimal Qtd, decimal Valor, decimal Saldo, decimal Custo, char? Fsn)>();
        await using (var r = await cmd.ExecuteReaderAsync(ct))
            while (await r.ReadAsync(ct))
                brutos.Add((r.GetString(0), r.GetString(1), r.GetString(2), r.GetDecimal(3), r.GetDecimal(4), r.GetDecimal(5), r.GetDecimal(6),
                    r.IsDBNull(7) ? null : r.GetString(7)[0]));

        var total = brutos.Sum(b => b.Valor);
        var classes = EstoqueCalculos.ClassificarAbc(brutos.Select(b => (b.Id, b.Valor)), p.AbcLimiteA, p.AbcLimiteB);
        decimal acumulado = 0;
        var itens = new List<ItemCurvaAbc>(brutos.Count);
        foreach (var b in brutos)
        {
            acumulado += b.Valor;
            itens.Add(new ItemCurvaAbc(b.Id, b.Codigo, b.Nome, b.Qtd, b.Valor, total > 0 ? 100m * b.Valor / total : 0m,
                total > 0 ? 100m * acumulado / total : 0m, classes[b.Id], b.Fsn, b.Saldo, EstoqueCalculos.ValorEmEstoque(b.Saldo, b.Custo)));
        }
        return itens;
    }
}
