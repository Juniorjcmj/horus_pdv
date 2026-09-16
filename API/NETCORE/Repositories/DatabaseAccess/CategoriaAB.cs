/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/CategoriaAB.cs
 * Objetivo: persistência e consultas SQL da hierarquia de categorias e departamentos
 *           de produtos com tenant isolado (CompanyId).
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class CategoriaAB(Connection connection)
{
    private const string BaseSelect = """
        SELECT c.Id, c.CompanyId, c.Nome, c.CategoriaPaiId, pai.Nome AS CategoriaPaiNome, c.Ordem, c.Ativa,
               COUNT(p.Id) AS QuantidadeProdutos
        FROM Categorias c
        LEFT JOIN Categorias pai ON pai.Id = c.CategoriaPaiId AND pai.CompanyId = c.CompanyId
        LEFT JOIN Produtos p ON p.CategoriaId = c.Id AND p.CompanyId = c.CompanyId
        """;

    public async Task<List<CategoriaAD>> ListarArvoreAsync(string companyId, bool apenasAtivas = false)
    {
        var sql = $"""
            {BaseSelect}
            WHERE c.CompanyId = @CompanyId
              AND (@ApenasAtivas = 0 OR c.Ativa = 1)
            GROUP BY c.Id, c.CompanyId, c.Nome, c.CategoriaPaiId, pai.Nome, c.Ordem, c.Ativa
            ORDER BY c.Ordem, c.Nome;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ApenasAtivas", apenasAtivas ? 1 : 0);

        await using var reader = await command.ExecuteReaderAsync();
        var all = new List<CategoriaAD>();
        while (await reader.ReadAsync())
        {
            all.Add(Map(reader));
        }

        var lookup = all.ToLookup(c => c.CategoriaPaiId ?? "");

        void AttachChildren(CategoriaAD parent)
        {
            var children = lookup[parent.Id]
                .OrderBy(c => c.Ordem)
                .ThenBy(c => c.Nome)
                .ToList();

            foreach (var child in children)
            {
                AttachChildren(child);
                child.QuantidadeProdutos += child.Subcategorias.Sum(gc => gc.QuantidadeProdutos);
            }

            parent.Subcategorias = children;
        }

        var roots = lookup[""].OrderBy(c => c.Ordem).ThenBy(c => c.Nome).ToList();
        foreach (var root in roots)
        {
            AttachChildren(root);
            root.QuantidadeProdutos += root.Subcategorias.Sum(c => c.QuantidadeProdutos);
        }

        return roots;
    }

    public async Task<List<CategoriaAD>> ListarTodasAsync(string companyId, bool apenasAtivas = false)
    {
        var sql = $"""
            {BaseSelect}
            WHERE c.CompanyId = @CompanyId
              AND (@ApenasAtivas = 0 OR c.Ativa = 1)
            GROUP BY c.Id, c.CompanyId, c.Nome, c.CategoriaPaiId, pai.Nome, c.Ordem, c.Ativa
            ORDER BY c.Ordem, c.Nome;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ApenasAtivas", apenasAtivas ? 1 : 0);

        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<CategoriaAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<CategoriaAD?> ObterPorIdAsync(string companyId, string id)
    {
        var sql = $"""
            {BaseSelect}
            WHERE c.Id = @Id AND c.CompanyId = @CompanyId
            GROUP BY c.Id, c.CompanyId, c.Nome, c.CategoriaPaiId, pai.Nome, c.Ordem, c.Ativa;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);

        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    public async Task<CategoriaAD> SalvarAsync(string companyId, CategoriaAD categoria)
    {
        if (string.IsNullOrWhiteSpace(categoria.Id))
        {
            categoria.Id = Guid.NewGuid().ToString();
        }
        categoria.CompanyId = companyId;

        const string sql = """
            IF EXISTS (SELECT 1 FROM Categorias WHERE Id = @Id AND CompanyId = @CompanyId)
            BEGIN
                UPDATE Categorias
                   SET Nome = @Nome,
                       CategoriaPaiId = @CategoriaPaiId,
                       Ordem = @Ordem,
                       Ativa = @Ativa
                 WHERE Id = @Id AND CompanyId = @CompanyId;
            END
            ELSE
            BEGIN
                INSERT INTO Categorias (Id, CompanyId, Nome, CategoriaPaiId, Ordem, Ativa)
                VALUES (@Id, @CompanyId, @Nome, @CategoriaPaiId, @Ordem, @Ativa);
            END;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", categoria.Id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Nome", categoria.Nome.Trim());
        command.Parameters.AddWithValue("@CategoriaPaiId", (object?)categoria.CategoriaPaiId ?? DBNull.Value);
        command.Parameters.AddWithValue("@Ordem", categoria.Ordem);
        command.Parameters.AddWithValue("@Ativa", categoria.Ativa);

        await command.ExecuteNonQueryAsync();
        return categoria;
    }

    public async Task<bool> AtivarDesativarAsync(string companyId, string id, bool ativa)
    {
        const string sql = """
            UPDATE Categorias
               SET Ativa = @Ativa
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Ativa", ativa);

        return await command.ExecuteNonQueryAsync() > 0;
    }

    public async Task<bool> ExcluirAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();

        // 1. Verifica se existem subcategorias
        const string sqlCheckChildren = """
            SELECT COUNT(1) FROM Categorias WHERE CategoriaPaiId = @Id AND CompanyId = @CompanyId;
            """;
        await using (var checkCmd = new SqlCommand(sqlCheckChildren, db))
        {
            checkCmd.Parameters.AddWithValue("@Id", id);
            checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
            var childCount = Convert.ToInt32(await checkCmd.ExecuteScalarAsync());
            if (childCount > 0)
            {
                throw new InvalidOperationException("Não é possível excluir uma categoria que possui subcategorias vinculadas.");
            }
        }

        // 2. Verifica se existem produtos vinculados
        const string sqlCheckProducts = """
            SELECT COUNT(1) FROM Produtos WHERE CategoriaId = @Id AND CompanyId = @CompanyId;
            """;
        await using (var checkCmd = new SqlCommand(sqlCheckProducts, db))
        {
            checkCmd.Parameters.AddWithValue("@Id", id);
            checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
            var productCount = Convert.ToInt32(await checkCmd.ExecuteScalarAsync());
            if (productCount > 0)
            {
                throw new InvalidOperationException("Não é possível excluir uma categoria que possui produtos vinculados. Remova ou altere a categoria dos produtos primeiro.");
            }
        }

        // 3. Exclui
        const string sqlDelete = """
            DELETE FROM Categorias WHERE Id = @Id AND CompanyId = @CompanyId;
            """;
        await using var delCmd = new SqlCommand(sqlDelete, db);
        delCmd.Parameters.AddWithValue("@Id", id);
        delCmd.Parameters.AddWithValue("@CompanyId", companyId);

        return await delCmd.ExecuteNonQueryAsync() > 0;
    }

    private static CategoriaAD Map(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        CompanyId = ReadString(reader, "CompanyId"),
        Nome = ReadString(reader, "Nome"),
        CategoriaPaiId = ReadNullableString(reader, "CategoriaPaiId"),
        CategoriaPaiNome = ReadNullableString(reader, "CategoriaPaiNome"),
        Ordem = ReadInt(reader, "Ordem"),
        Ativa = ReadBool(reader, "Ativa"),
        QuantidadeProdutos = ReadInt(reader, "QuantidadeProdutos")
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

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }

    private static bool ReadBool(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return !reader.IsDBNull(ordinal) && reader.GetBoolean(ordinal);
    }
}
