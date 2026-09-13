/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/PromocaoAB.cs
 * Objetivo: gerencia persistência e consultas de promoções e produtos vinculados.
 * Entradas esperadas: recebe conexão configurada e parâmetros de negócio escopados por CompanyId.
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class PromocaoAB(Connection connection)
{
    private const string Columns = """
        p.Id, p.CompanyId, p.Nome, p.Tipo, p.ValorDesconto, p.PrecoFixo,
        p.QuantidadeLeva, p.QuantidadePaga, p.QuantidadeMinima,
        p.InicioVigencia, p.FimVigencia, p.Ativa, p.CategoriaId,
        c.Nome AS CategoriaNome, p.CriadoPor, p.CriadoEm
        """;

    public async Task<List<PromocaoAD>> ListarAsync(string companyId)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Promocoes p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId
            ORDER BY p.CriadoEm DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var promos = new List<PromocaoAD>();
        while (await reader.ReadAsync())
        {
            promos.Add(Map(reader));
        }
        await reader.CloseAsync();

        await PreencherProdutosEmLoteAsync(db, companyId, promos);
        return promos;
    }

    public async Task<List<PromocaoAD>> ListarAtivasAsync(string companyId)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Promocoes p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId
              AND p.Ativa = 1
              AND SYSDATETIMEOFFSET() BETWEEN p.InicioVigencia AND p.FimVigencia
            ORDER BY p.CriadoEm DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var promos = new List<PromocaoAD>();
        while (await reader.ReadAsync())
        {
            promos.Add(Map(reader));
        }
        await reader.CloseAsync();

        await PreencherProdutosEmLoteAsync(db, companyId, promos);
        return promos;
    }

    public async Task<PromocaoAD?> ObterPorIdAsync(string companyId, string id)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Promocoes p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId AND p.Id = @Id;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;

        var promo = Map(reader);
        await reader.CloseAsync();

        await PreencherProdutosAsync(db, promo);
        return promo;
    }

    public async Task<PromocaoAD> SalvarAsync(string companyId, PromocaoAD promo)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            const string upsertSql = """
                IF EXISTS (SELECT 1 FROM Promocoes WHERE Id = @Id AND CompanyId = @CompanyId)
                BEGIN
                    UPDATE Promocoes
                       SET Nome = @Nome,
                           Tipo = @Tipo,
                           ValorDesconto = @ValorDesconto,
                           PrecoFixo = @PrecoFixo,
                           QuantidadeLeva = @QuantidadeLeva,
                           QuantidadePaga = @QuantidadePaga,
                           QuantidadeMinima = @QuantidadeMinima,
                           InicioVigencia = @InicioVigencia,
                           FimVigencia = @FimVigencia,
                           Ativa = @Ativa,
                           CategoriaId = @CategoriaId
                     WHERE Id = @Id AND CompanyId = @CompanyId;
                END
                ELSE
                BEGIN
                    INSERT INTO Promocoes
                        (Id, CompanyId, Nome, Tipo, ValorDesconto, PrecoFixo,
                         QuantidadeLeva, QuantidadePaga, QuantidadeMinima,
                         InicioVigencia, FimVigencia, Ativa, CategoriaId, CriadoPor, CriadoEm)
                    VALUES
                        (@Id, @CompanyId, @Nome, @Tipo, @ValorDesconto, @PrecoFixo,
                         @QuantidadeLeva, @QuantidadePaga, @QuantidadeMinima,
                         @InicioVigencia, @FimVigencia, @Ativa, @CategoriaId, @CriadoPor, @CriadoEm);
                END;
                """;

            await using var cmd = new SqlCommand(upsertSql, db, tx);
            cmd.Parameters.AddWithValue("@Id", promo.Id);
            cmd.Parameters.AddWithValue("@CompanyId", companyId);
            cmd.Parameters.AddWithValue("@Nome", promo.Nome);
            cmd.Parameters.AddWithValue("@Tipo", promo.Tipo);
            cmd.Parameters.AddWithValue("@ValorDesconto", (object?)promo.ValorDesconto ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@PrecoFixo", (object?)promo.PrecoFixo ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@QuantidadeLeva", (object?)promo.QuantidadeLeva ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@QuantidadePaga", (object?)promo.QuantidadePaga ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@QuantidadeMinima", (object?)promo.QuantidadeMinima ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@InicioVigencia", promo.InicioVigencia);
            cmd.Parameters.AddWithValue("@FimVigencia", promo.FimVigencia);
            cmd.Parameters.AddWithValue("@Ativa", promo.Ativa);
            cmd.Parameters.AddWithValue("@CategoriaId", (object?)promo.CategoriaId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@CriadoPor", promo.CriadoPor);
            cmd.Parameters.AddWithValue("@CriadoEm", promo.CriadoEm);
            await cmd.ExecuteNonQueryAsync();

            // Sincronizar produtos vinculados
            await using var delCmd = new SqlCommand("DELETE FROM PromocaoProdutos WHERE PromocaoId = @PromocaoId;", db, tx);
            delCmd.Parameters.AddWithValue("@PromocaoId", promo.Id);
            await delCmd.ExecuteNonQueryAsync();

            if (promo.ProdutoIds.Count > 0)
            {
                foreach (var prodId in promo.ProdutoIds.Distinct())
                {
                    await using var insProd = new SqlCommand(
                        "INSERT INTO PromocaoProdutos (Id, PromocaoId, ProdutoId) VALUES (@Id, @PromocaoId, @ProdutoId);",
                        db,
                        tx);
                    insProd.Parameters.AddWithValue("@Id", Guid.NewGuid().ToString("N"));
                    insProd.Parameters.AddWithValue("@PromocaoId", promo.Id);
                    insProd.Parameters.AddWithValue("@ProdutoId", prodId);
                    await insProd.ExecuteNonQueryAsync();
                }
            }

            await tx.CommitAsync();
            return promo;
        }
        catch
        {
            await tx.RollbackAsync();
            throw;
        }
    }

    public async Task<bool> AtivarDesativarAsync(string companyId, string id, bool ativa)
    {
        const string sql = "UPDATE Promocoes SET Ativa = @Ativa WHERE Id = @Id AND CompanyId = @CompanyId;";
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@Ativa", ativa);
        cmd.Parameters.AddWithValue("@Id", id);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        return await cmd.ExecuteNonQueryAsync() > 0;
    }

    public async Task<bool> ExcluirAsync(string companyId, string id)
    {
        const string sql = "DELETE FROM Promocoes WHERE Id = @Id AND CompanyId = @CompanyId;";
        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@Id", id);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        return await cmd.ExecuteNonQueryAsync() > 0;
    }

    public async Task<PromocaoResultadoAD?> ObterResultadoAsync(string companyId, string promocaoId)
    {
        var promo = await ObterPorIdAsync(companyId, promocaoId);
        if (promo is null) return null;

        const string sql = """
            SELECT
                COUNT(DISTINCT v.Id) AS QuantidadeVendas,
                ISNULL(SUM(i.Quantity * i.UnitPrice), 0) AS ReceitaBruta,
                ISNULL(SUM(i.ItemTotal), 0) AS ReceitaLiquida,
                ISNULL(SUM(i.Desconto), 0) AS DescontoTotal,
                ISNULL(SUM(i.ItemTotal - (i.Quantity * p.ProductUnitPrice)), 0) AS MargemLiquida
            FROM VendaItens i
            INNER JOIN Vendas v ON v.Id = i.VendaId AND v.CompanyId = @CompanyId
            INNER JOIN Produtos p ON p.ProductCode = i.ProductCode AND p.CompanyId = @CompanyId
            WHERE i.PromocaoId = @PromocaoId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@PromocaoId", promocaoId);
        await using var reader = await cmd.ExecuteReaderAsync();

        if (await reader.ReadAsync())
        {
            return new PromocaoResultadoAD
            {
                PromocaoId = promocaoId,
                Nome = promo.Nome,
                QuantidadeVendas = ReadInt(reader, "QuantidadeVendas"),
                ReceitaBruta = ReadDecimal(reader, "ReceitaBruta"),
                ReceitaLiquida = ReadDecimal(reader, "ReceitaLiquida"),
                DescontoTotal = ReadDecimal(reader, "DescontoTotal"),
                MargemLiquida = ReadDecimal(reader, "MargemLiquida")
            };
        }

        return new PromocaoResultadoAD
        {
            PromocaoId = promocaoId,
            Nome = promo.Nome
        };
    }

    private static async Task PreencherProdutosEmLoteAsync(SqlConnection db, string companyId, List<PromocaoAD> promos)
    {
        if (promos.Count == 0) return;

        const string sql = """
            SELECT pp.PromocaoId, pp.ProdutoId
            FROM PromocaoProdutos pp
            INNER JOIN Promocoes p ON p.Id = pp.PromocaoId
            WHERE p.CompanyId = @CompanyId;
            """;

        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await cmd.ExecuteReaderAsync();
        var map = new Dictionary<string, List<string>>();
        while (await reader.ReadAsync())
        {
            var pId = ReadString(reader, "PromocaoId");
            var prodId = ReadString(reader, "ProdutoId");
            if (!map.TryGetValue(pId, out var list))
            {
                list = [];
                map[pId] = list;
            }
            list.Add(prodId);
        }

        foreach (var promo in promos)
        {
            if (map.TryGetValue(promo.Id, out var ids))
            {
                promo.ProdutoIds = ids;
            }
        }
    }

    private static async Task PreencherProdutosAsync(SqlConnection db, PromocaoAD promo)
    {
        const string sql = "SELECT ProdutoId FROM PromocaoProdutos WHERE PromocaoId = @PromocaoId;";
        await using var cmd = new SqlCommand(sql, db);
        cmd.Parameters.AddWithValue("@PromocaoId", promo.Id);
        await using var reader = await cmd.ExecuteReaderAsync();
        var list = new List<string>();
        while (await reader.ReadAsync())
        {
            list.Add(ReadString(reader, "ProdutoId"));
        }
        promo.ProdutoIds = list;
    }

    private static PromocaoAD Map(SqlDataReader r) => new()
    {
        Id = ReadString(r, "Id"),
        CompanyId = ReadString(r, "CompanyId"),
        Nome = ReadString(r, "Nome"),
        Tipo = ReadString(r, "Tipo"),
        ValorDesconto = ReadNullableDecimal(r, "ValorDesconto"),
        PrecoFixo = ReadNullableDecimal(r, "PrecoFixo"),
        QuantidadeLeva = ReadNullableInt(r, "QuantidadeLeva"),
        QuantidadePaga = ReadNullableInt(r, "QuantidadePaga"),
        QuantidadeMinima = ReadNullableInt(r, "QuantidadeMinima"),
        InicioVigencia = r.GetDateTimeOffset(r.GetOrdinal("InicioVigencia")),
        FimVigencia = r.GetDateTimeOffset(r.GetOrdinal("FimVigencia")),
        Ativa = r.GetBoolean(r.GetOrdinal("Ativa")),
        CategoriaId = ReadNullableString(r, "CategoriaId"),
        CategoriaNome = ReadNullableString(r, "CategoriaNome"),
        CriadoPor = ReadString(r, "CriadoPor"),
        CriadoEm = r.GetDateTimeOffset(r.GetOrdinal("CriadoEm")),
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal).Trim();
    }

    private static string? ReadNullableString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal).Trim();
    }

    private static decimal ReadDecimal(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0m : reader.GetDecimal(ordinal);
    }

    private static decimal? ReadNullableDecimal(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDecimal(ordinal);
    }

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }

    private static int? ReadNullableInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : Convert.ToInt32(reader.GetValue(ordinal));
    }
}
