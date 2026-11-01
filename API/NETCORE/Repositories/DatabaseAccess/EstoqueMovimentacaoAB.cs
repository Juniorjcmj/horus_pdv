using HORUSPDV_API.Models.Produtos;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

/// <summary>Origem e autor de uma movimentação de estoque (quem, por quê e de qual documento).</summary>
public sealed record MovimentoOrigem(
    string OrigemTipo,
    string? OrigemId,
    string Motivo,
    string UsuarioId = "sistema",
    string UsuarioNome = "Sistema")
{
    public static MovimentoOrigem Sistema(string origemTipo, string? origemId, string motivo)
        => new(origemTipo, origemId, motivo);
}

/// <summary>Tipos de movimentação aceitos (coluna EstoqueMovimentacoes.Tipo).</summary>
public static class EstoqueTipos
{
    public const string Entrada = "entrada";
    public const string Saida = "saida";
    public const string SaidaVenda = "saida_venda";
    public const string Devolucao = "devolucao";
    public const string UsoInterno = "uso_interno";
    public const string Perda = "perda";
    public const string Inventario = "inventario";
    public const string Transferencia = "transferencia";
}

public class EstoqueMovimentacaoAB(Connection connection)
{
    /// <summary>
    /// Registrador único de saldo (D2): soma <paramref name="delta"/> ao saldo do produto e grava a movimentação
    /// na MESMA transação. É o único caminho autorizado a alterar Produtos.ProductQnt. Anterior/novo vêm do
    /// OUTPUT do UPDATE (exatos sob concorrência). Delta zero não grava nada. Retorna null se o produto não existe.
    /// </summary>
    public static async Task<(decimal Anterior, decimal Novo)?> AplicarMovimentoAsync(
        SqlConnection db, SqlTransaction tx, string companyId, string produtoId,
        decimal delta, string tipo, decimal? custoUnitario, MovimentoOrigem origem,
        string depositoId = "loja")
    {
        await using var cmd = new SqlCommand(
            """
            DECLARE @r TABLE (Anterior DECIMAL(18,3), Novo DECIMAL(18,3), Custo DECIMAL(18,4));
            UPDATE Produtos
               SET ProductQnt = ProductQnt + @Delta,
                   TotalPriceOnProduct = ProductUnitPrice * (ProductQnt + @Delta)
            OUTPUT deleted.ProductQnt, inserted.ProductQnt, inserted.ProductUnitPrice INTO @r
             WHERE Id = @ProdutoId AND CompanyId = @CompanyId;
            SELECT Anterior, Novo, Custo FROM @r;
            """, db, tx);
        cmd.Parameters.AddWithValue("@Delta", delta);
        cmd.Parameters.AddWithValue("@ProdutoId", produtoId);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);

        decimal anterior, novo, custoProduto;
        await using (var reader = await cmd.ExecuteReaderAsync())
        {
            if (!await reader.ReadAsync()) return null;
            anterior = reader.GetDecimal(0);
            novo = reader.GetDecimal(1);
            custoProduto = reader.GetDecimal(2);
        }

        if (delta == 0m) return (anterior, novo);

        await using var ins = new SqlCommand(
            """
            INSERT INTO EstoqueMovimentacoes
                (Id, CompanyId, ProdutoId, Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, Motivo, UsuarioId, UsuarioNome,
                 CustoUnitario, OrigemTipo, OrigemId, DepositoId)
            VALUES
                (@Id, @CompanyId, @ProdutoId, @Tipo, @Quantidade, @Anterior, @Novo, @Motivo, @UsuarioId, @UsuarioNome,
                 @Custo, @OrigemTipo, @OrigemId, @DepositoId);
            """, db, tx);
        ins.Parameters.AddWithValue("@Id", $"mov-{Guid.NewGuid():N}");
        ins.Parameters.AddWithValue("@CompanyId", companyId);
        ins.Parameters.AddWithValue("@ProdutoId", produtoId);
        ins.Parameters.AddWithValue("@Tipo", tipo);
        ins.Parameters.AddWithValue("@Quantidade", Math.Abs(delta));
        ins.Parameters.AddWithValue("@Anterior", anterior);
        ins.Parameters.AddWithValue("@Novo", novo);
        ins.Parameters.AddWithValue("@Motivo", Truncate(origem.Motivo, 500));
        ins.Parameters.AddWithValue("@UsuarioId", Truncate(string.IsNullOrWhiteSpace(origem.UsuarioId) ? "sistema" : origem.UsuarioId, 36));
        ins.Parameters.AddWithValue("@UsuarioNome", Truncate(string.IsNullOrWhiteSpace(origem.UsuarioNome) ? "Sistema" : origem.UsuarioNome, 200));
        ins.Parameters.AddWithValue("@Custo", custoUnitario ?? custoProduto);
        ins.Parameters.AddWithValue("@OrigemTipo", (object?)Truncate(origem.OrigemTipo, 20) ?? DBNull.Value);
        ins.Parameters.AddWithValue("@OrigemId", (object?)Truncate(origem.OrigemId, 80) ?? DBNull.Value);
        ins.Parameters.AddWithValue("@DepositoId", depositoId);
        await ins.ExecuteNonQueryAsync();
        return (anterior, novo);
    }

    /// <summary>Igual a <see cref="AplicarMovimentoAsync"/>, resolvendo o produto pelo codigo (ProductCode) da empresa.</summary>
    public static async Task<(decimal Anterior, decimal Novo)?> AplicarMovimentoPorCodigoAsync(
        SqlConnection db, SqlTransaction tx, string companyId, string productCode,
        decimal delta, string tipo, decimal? custoUnitario, MovimentoOrigem origem)
    {
        await using var find = new SqlCommand(
            "SELECT Id FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;", db, tx);
        find.Parameters.AddWithValue("@CompanyId", companyId);
        find.Parameters.AddWithValue("@ProductCode", productCode);
        if (await find.ExecuteScalarAsync() is not string id) return null;
        return await AplicarMovimentoAsync(db, tx, companyId, id, delta, tipo, custoUnitario, origem);
    }

    /// <summary>Troca o identificador provisorio das movimentacoes de uma venda pelo numero definitivo (mesma transacao).</summary>
    public static async Task VincularOrigemAsync(
        SqlConnection db, SqlTransaction tx, string companyId, string origemProvisoria, string origemDefinitiva, string motivo)
    {
        await using var cmd = new SqlCommand(
            "UPDATE EstoqueMovimentacoes SET OrigemId = @Novo, Motivo = @Motivo WHERE CompanyId = @CompanyId AND OrigemId = @Antigo;", db, tx);
        cmd.Parameters.AddWithValue("@Novo", origemDefinitiva);
        cmd.Parameters.AddWithValue("@Motivo", motivo);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Antigo", origemProvisoria);
        await cmd.ExecuteNonQueryAsync();
    }

    /// <summary>
    /// Devolve ao estoque todos os itens de uma venda (movimentacao "devolucao", origem = numero da venda).
    /// Usado nos cancelamentos/devolucoes fiscais. Retorna a quantidade de itens devolvidos.
    /// </summary>
    public static async Task<int> DevolverItensDaVendaAsync(
        SqlConnection db, SqlTransaction tx, string companyId, string vendaId,
        string origemTipo, string motivo, string usuarioId, string usuarioNome, CancellationToken ct = default)
    {
        string saleNumber = vendaId;
        await using (var num = new SqlCommand("SELECT SaleNumber FROM Vendas WHERE Id = @V AND CompanyId = @C;", db, tx))
        {
            num.Parameters.AddWithValue("@V", vendaId);
            num.Parameters.AddWithValue("@C", companyId);
            if (await num.ExecuteScalarAsync(ct) is string n && !string.IsNullOrWhiteSpace(n)) saleNumber = n;
        }

        var itens = new List<(string Code, decimal Qty)>();
        await using (var sel = new SqlCommand(
            "SELECT ProductCode, SUM(Quantity) FROM VendaItens WHERE VendaId = @V GROUP BY ProductCode;", db, tx))
        {
            sel.Parameters.AddWithValue("@V", vendaId);
            await using var r = await sel.ExecuteReaderAsync(ct);
            while (await r.ReadAsync(ct)) itens.Add((r.GetString(0), r.GetDecimal(1)));
        }

        var count = 0;
        foreach (var (code, qty) in itens)
        {
            if (qty <= 0) continue;
            var ok = await AplicarMovimentoPorCodigoAsync(db, tx, companyId, code, qty, EstoqueTipos.Devolucao, null,
                new MovimentoOrigem(origemTipo, saleNumber, motivo.Replace("{venda}", saleNumber), usuarioId, usuarioNome));
            if (ok is not null) count++;
        }
        return count;
    }

    public static string NovaOrigemProvisoria() => "pend-" + Guid.NewGuid().ToString("N");

    private static string? Truncate(string? value, int max)
        => value is null ? null : value.Length <= max ? value : value[..max];

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
