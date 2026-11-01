/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EstoqueSnapshotAB.cs
 * Objetivo: fotografia diária do estoque (D3): quantidade e valor ao custo de cada produto ao fim de um dia (horário de
 *           Brasília). Idempotente (MERGE). O dia passado é reconstruído pelo saldo atual menos as movimentações
 *           registradas depois do fim daquele dia. Base do histórico, da ruptura por dia e do estoque médio.
 */
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class EstoqueSnapshotAB(Connection connection)
{
    public const string DepositoPadrao = "loja";

    /// <summary>Dia (data civil de Brasília) que acabou de fechar: ontem.</summary>
    public static DateOnly Ontem() => DateOnly.FromDateTime(HorusDateTime.Now.Date).AddDays(-1);

    /// <summary>Instante UTC em que o dia <paramref name="data"/> termina (meia-noite de Brasília do dia seguinte).</summary>
    public static DateTime FimDoDiaUtc(DateOnly data)
    {
        var local = DateTime.SpecifyKind(data.AddDays(1).ToDateTime(TimeOnly.MinValue), DateTimeKind.Unspecified);
        return TimeZoneInfo.ConvertTimeToUtc(local, HorusDateTime.TimeZone);
    }

    /// <summary>Grava (ou atualiza) a fotografia do dia. Retorna o número de produtos fotografados.</summary>
    public async Task<int> GravarAsync(string companyId, DateOnly data, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        return await GravarAsync(db, null, companyId, data, ct);
    }

    public static async Task<int> GravarAsync(SqlConnection db, SqlTransaction? tx, string companyId, DateOnly data, CancellationToken ct = default)
    {
        await using var cmd = new SqlCommand(
            """
            ;WITH Dep AS (
                SELECT ProdutoId, SUM(EstoqueNovo - EstoqueAnterior) AS Delta
                FROM EstoqueMovimentacoes
                WHERE CompanyId = @CompanyId AND DepositoId = @Deposito AND CriadoEm >= @FimUtc
                GROUP BY ProdutoId
            ),
            Calc AS (
                SELECT p.Id AS ProdutoId,
                       CAST(p.ProductQnt - ISNULL(d.Delta, 0) AS DECIMAL(18,3)) AS Quantidade,
                       p.ProductUnitPrice AS Custo
                FROM Produtos p
                LEFT JOIN Dep d ON d.ProdutoId = p.Id
                WHERE p.CompanyId = @CompanyId
                  -- produto cadastrado depois do dia (primeira movimentação é o cadastro, depois do fim do dia) não existia
                  AND NOT (
                        NOT EXISTS (SELECT 1 FROM EstoqueMovimentacoes m0
                                    WHERE m0.CompanyId = @CompanyId AND m0.ProdutoId = p.Id AND m0.CriadoEm < @FimUtc)
                        AND EXISTS (SELECT 1 FROM EstoqueMovimentacoes m1
                                    WHERE m1.CompanyId = @CompanyId AND m1.ProdutoId = p.Id AND m1.CriadoEm >= @FimUtc
                                      AND m1.OrigemTipo = 'cadastro' AND m1.EstoqueAnterior = 0)
                  )
            )
            MERGE EstoqueSnapshotDiario AS t
            USING Calc AS s
               ON t.CompanyId = @CompanyId AND t.DepositoId = @Deposito AND t.Data = @Data AND t.ProdutoId = s.ProdutoId
            WHEN MATCHED THEN
                UPDATE SET Quantidade = s.Quantidade,
                           Valor = CAST(CASE WHEN s.Quantidade > 0 THEN s.Quantidade * s.Custo ELSE 0 END AS DECIMAL(18,2)),
                           CustoUnitario = s.Custo
            WHEN NOT MATCHED THEN
                INSERT (CompanyId, DepositoId, Data, ProdutoId, Quantidade, Valor, CustoUnitario)
                VALUES (@CompanyId, @Deposito, @Data, s.ProdutoId, s.Quantidade,
                        CAST(CASE WHEN s.Quantidade > 0 THEN s.Quantidade * s.Custo ELSE 0 END AS DECIMAL(18,2)), s.Custo);
            SELECT @@ROWCOUNT;
            """, db, tx);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Deposito", DepositoPadrao);
        cmd.Parameters.AddWithValue("@Data", data.ToDateTime(TimeOnly.MinValue));
        cmd.Parameters.AddWithValue("@FimUtc", FimDoDiaUtc(data));
        return Convert.ToInt32(await cmd.ExecuteScalarAsync(ct));
    }

    /// <summary>Empresas que têm produtos mas ainda não têm a fotografia do dia.</summary>
    public async Task<List<string>> EmpresasSemSnapshotAsync(DateOnly data, CancellationToken ct = default)
    {
        await using var db = await connection.OpenConnectionAsync(ct);
        await using var cmd = new SqlCommand(
            """
            SELECT DISTINCT p.CompanyId
            FROM Produtos p
            WHERE NOT EXISTS (SELECT 1 FROM EstoqueSnapshotDiario s
                              WHERE s.CompanyId = p.CompanyId AND s.DepositoId = @Deposito AND s.Data = @Data);
            """, db);
        cmd.Parameters.AddWithValue("@Deposito", DepositoPadrao);
        cmd.Parameters.AddWithValue("@Data", data.ToDateTime(TimeOnly.MinValue));
        var list = new List<string>();
        await using var r = await cmd.ExecuteReaderAsync(ct);
        while (await r.ReadAsync(ct)) list.Add(r.GetString(0));
        return list;
    }
}
