using HORUSPDV_API.Models.Produtos;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class EstoqueMovimentacaoAB(Connection connection)
{
    public async Task RegistrarAsync(
        string companyId, string produtoId, string tipo,
        decimal quantidade, decimal estoqueAnterior, decimal estoqueNovo,
        string motivo, string usuarioId, string usuarioNome)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand(
            """
            INSERT INTO EstoqueMovimentacoes
                (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome)
            VALUES
                (@Id, @CompanyId, @ProdutoId, @Tipo, @Quantidade, @EstoqueAnterior, @EstoqueNovo, @Motivo, @UsuarioId, @UsuarioNome);
            """, db);
        cmd.Parameters.AddWithValue("@Id", $"mov-{Guid.NewGuid():N}");
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@ProdutoId", produtoId);
        cmd.Parameters.AddWithValue("@Tipo", tipo);
        cmd.Parameters.AddWithValue("@Quantidade", quantidade);
        cmd.Parameters.AddWithValue("@EstoqueAnterior", estoqueAnterior);
        cmd.Parameters.AddWithValue("@EstoqueNovo", estoqueNovo);
        cmd.Parameters.AddWithValue("@Motivo", motivo);
        cmd.Parameters.AddWithValue("@UsuarioId", usuarioId);
        cmd.Parameters.AddWithValue("@UsuarioNome", usuarioNome);
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task<List<EstoqueMovimentacaoModel>> ListarPorProdutoAsync(string companyId, string produtoId)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand(
            """
            SELECT m.Id, m.ProdutoId, p.ProductDescription AS ProdutoNome, p.ProductCode AS ProdutoCodigo,
                   m.Tipo, m.Quantidade, m.EstoqueAnterior, m.EstoqueNovo, m.Motivo,
                   m.UsuarioId, m.UsuarioNome, m.CriadoEm
            FROM EstoqueMovimentacoes m
            INNER JOIN Produtos p ON p.Id = m.ProdutoId
            WHERE m.CompanyId = @CompanyId AND m.ProdutoId = @ProdutoId
            ORDER BY m.CriadoEm DESC;
            """, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@ProdutoId", produtoId);

        var list = new List<EstoqueMovimentacaoModel>();
        await using var reader = await cmd.ExecuteReaderAsync();
        while (await reader.ReadAsync())
            list.Add(ReadModel(reader));
        return list;
    }

    public async Task<List<EstoqueMovimentacaoModel>> ListarAsync(string companyId, string? tipo = null, int limit = 200)
    {
        var where = "m.CompanyId = @CompanyId";
        if (!string.IsNullOrWhiteSpace(tipo))
            where += " AND m.Tipo = @Tipo";

        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand(
            $"""
            SELECT TOP(@Limit)
                   m.Id, m.ProdutoId, p.ProductDescription AS ProdutoNome, p.ProductCode AS ProdutoCodigo,
                   m.Tipo, m.Quantidade, m.EstoqueAnterior, m.EstoqueNovo, m.Motivo,
                   m.UsuarioId, m.UsuarioNome, m.CriadoEm
            FROM EstoqueMovimentacoes m
            INNER JOIN Produtos p ON p.Id = m.ProdutoId
            WHERE {where}
            ORDER BY m.CriadoEm DESC;
            """, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Limit", limit);
        if (!string.IsNullOrWhiteSpace(tipo))
            cmd.Parameters.AddWithValue("@Tipo", tipo);

        var list = new List<EstoqueMovimentacaoModel>();
        await using var reader = await cmd.ExecuteReaderAsync();
        while (await reader.ReadAsync())
            list.Add(ReadModel(reader));
        return list;
    }

    private static EstoqueMovimentacaoModel ReadModel(SqlDataReader r) => new()
    {
        Id = r.GetString(r.GetOrdinal("Id")),
        ProdutoId = r.GetString(r.GetOrdinal("ProdutoId")),
        ProdutoNome = r.GetString(r.GetOrdinal("ProdutoNome")),
        ProdutoCodigo = r.GetString(r.GetOrdinal("ProdutoCodigo")),
        Tipo = r.GetString(r.GetOrdinal("Tipo")),
        Quantidade = r.GetDecimal(r.GetOrdinal("Quantidade")),
        EstoqueAnterior = r.GetDecimal(r.GetOrdinal("EstoqueAnterior")),
        EstoqueNovo = r.GetDecimal(r.GetOrdinal("EstoqueNovo")),
        Motivo = r.GetString(r.GetOrdinal("Motivo")),
        UsuarioId = r.GetString(r.GetOrdinal("UsuarioId")),
        UsuarioNome = r.GetString(r.GetOrdinal("UsuarioNome")),
        CriadoEm = r.GetDateTime(r.GetOrdinal("CriadoEm")).ToString("yyyy-MM-dd HH:mm:ss"),
    };
}
