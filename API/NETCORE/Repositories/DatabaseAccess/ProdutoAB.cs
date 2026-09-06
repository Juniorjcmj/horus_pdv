/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/ProdutoAB.cs
 * Objetivo: concentra comandos SQL e persistência de cadastro, estoque, manutenção e dados
 *           fiscais de produtos.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class ProdutoAB(Connection connection)
{
    private const string Columns = """
        Id, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier,
        ProductDescription, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct,
        Ncm, Cest, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin,
        CsosnIcms, CstIcms, AliquotaIcms, CstPis, CstCofins, CstIbsCbs, CClassTrib
        """;

    public async Task<List<ProdutoAD>> ListarAsync(string companyId)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Produtos
            WHERE CompanyId = @CompanyId
            ORDER BY ProductName;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<ProdutoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<ProdutoAD?> ObterAsync(string companyId, string id)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Produtos
            WHERE Id = @Id AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    /// <summary>Usada pelo módulo fiscal (DocumentoFiscalAB) para montar o item da NFC-e.</summary>
    public async Task<ProdutoAD?> ObterPorCodigoAsync(string companyId, string productCode)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Produtos
            WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ProductCode", productCode);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    /// <summary>Usada pela importação de XML de NF-e para casar item da nota com produto já cadastrado.</summary>
    public async Task<ProdutoAD?> ObterPorGtinAsync(string companyId, string gtin)
    {
        if (string.IsNullOrWhiteSpace(gtin) || string.Equals(gtin, "SEM GTIN", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var sql = $"""
            SELECT TOP 1 {Columns}
            FROM Produtos
            WHERE CompanyId = @CompanyId AND Gtin = @Gtin;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Gtin", gtin.Trim());
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    /// <summary>
    /// Entrada de estoque por importação de XML de NF-e: soma a quantidade recebida ao estoque
    /// já existente e atualiza o preço de custo com o valor da nota (preço de venda não é mexido
    /// aqui — permanece o que já estava cadastrado, a menos que o operador o edite à parte).
    /// </summary>
    public async Task<ProdutoAD> EntradaEstoqueAsync(string companyId, string productId, decimal quantidadeRecebida, decimal custoUnitario)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            await using var select = new SqlCommand(
                $"SELECT {Columns} FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE Id = @Id AND CompanyId = @CompanyId;",
                db,
                transaction);
            select.Parameters.AddWithValue("@Id", productId);
            select.Parameters.AddWithValue("@CompanyId", companyId);
            ProdutoAD current;
            await using (var reader = await select.ExecuteReaderAsync())
            {
                if (!await reader.ReadAsync())
                {
                    throw new InvalidOperationException($"Produto {productId} não encontrado.");
                }

                current = Map(reader);
            }

            var nextQuantity = current.ProductQnt + quantidadeRecebida;
            var nextTotal = custoUnitario * nextQuantity;

            await using var update = new SqlCommand(
                """
                UPDATE Produtos
                   SET ProductQnt = @ProductQnt,
                       ProductUnitPrice = @ProductUnitPrice,
                       TotalPriceOnProduct = @TotalPriceOnProduct
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction);
            update.Parameters.AddWithValue("@ProductQnt", nextQuantity);
            update.Parameters.AddWithValue("@ProductUnitPrice", custoUnitario);
            update.Parameters.AddWithValue("@TotalPriceOnProduct", nextTotal);
            update.Parameters.AddWithValue("@Id", productId);
            update.Parameters.AddWithValue("@CompanyId", companyId);
            await update.ExecuteNonQueryAsync();

            await transaction.CommitAsync();

            current.ProductQnt = nextQuantity;
            current.ProductUnitPrice = custoUnitario;
            current.TotalPriceOnProduct = nextTotal;
            return current;
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    public async Task<ProdutoAD> SalvarAsync(string companyId, ProdutoAD product)
    {
        var supplierId = await ResolveSupplierIdAsync(companyId, product.ProductSupplier);
        const string sql = """
            IF EXISTS (SELECT 1 FROM Produtos WHERE Id = @Id AND CompanyId = @CompanyId)
            BEGIN
                UPDATE Produtos
                   SET ProductImageUrl = @ProductImageUrl,
                       ProductImageName = @ProductImageName,
                       ProductName = @ProductName,
                       ProductCode = @ProductCode,
                       ProductSupplier = @ProductSupplier,
                       SupplierId = @SupplierId,
                       ProductDescription = @ProductDescription,
                       ProductQnt = @ProductQnt,
                       ProductUnitPrice = @ProductUnitPrice,
                       ProductSalePrice = @ProductSalePrice,
                       TotalPriceOnProduct = @TotalPriceOnProduct,
                       Ncm = @Ncm,
                       Cest = @Cest,
                       Cfop = @Cfop,
                       OrigemMercadoria = @OrigemMercadoria,
                       UnidadeComercial = @UnidadeComercial,
                       UnidadeTributavel = @UnidadeTributavel,
                       Gtin = @Gtin,
                       CsosnIcms = @CsosnIcms,
                       CstIcms = @CstIcms,
                       AliquotaIcms = @AliquotaIcms,
                       CstPis = @CstPis,
                       CstCofins = @CstCofins,
                       CstIbsCbs = @CstIbsCbs,
                       CClassTrib = @CClassTrib
                 WHERE Id = @Id AND CompanyId = @CompanyId;
            END
            ELSE
            BEGIN
                INSERT INTO Produtos
                    (Id, CompanyId, ProductImageUrl, ProductImageName, ProductName, ProductCode, ProductSupplier, SupplierId,
                     ProductDescription, ProductQnt, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct,
                     Ncm, Cest, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin,
                     CsosnIcms, CstIcms, AliquotaIcms, CstPis, CstCofins, CstIbsCbs, CClassTrib)
                VALUES
                    (@Id, @CompanyId, @ProductImageUrl, @ProductImageName, @ProductName, @ProductCode, @ProductSupplier, @SupplierId,
                     @ProductDescription, @ProductQnt, @ProductUnitPrice, @ProductSalePrice, @TotalPriceOnProduct,
                     @Ncm, @Cest, @Cfop, @OrigemMercadoria, @UnidadeComercial, @UnidadeTributavel, @Gtin,
                     @CsosnIcms, @CstIcms, @AliquotaIcms, @CstPis, @CstCofins, @CstIbsCbs, @CClassTrib);
            END;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        AddParameters(command, product, supplierId);
        await command.ExecuteNonQueryAsync();
        return product;
    }

    public async Task<bool> ExcluirAsync(string companyId, string id)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand("DELETE FROM Produtos WHERE Id = @Id AND CompanyId = @CompanyId;", db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        return await command.ExecuteNonQueryAsync() > 0;
    }

    public async Task BaixarEstoqueAsync(IEnumerable<(string ProductCode, decimal Quantity)> items)
    {
        var groupedItems = items
            .GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(group => new { ProductCode = group.Key, Quantity = group.Sum(item => item.Quantity) })
            .ToList();

        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            // Agrupa itens repetidos antes do bloqueio pessimista para baixar estoque uma única vez por produto.
            foreach (var item in groupedItems)
            {
                if (item.Quantity <= 0)
                {
                    throw new InvalidOperationException("Quantidade da venda deve ser maior que zero.");
                }

                await using var select = new SqlCommand(
                    """
                    SELECT Id, ProductName, ProductQnt, ProductUnitPrice
                    FROM Produtos WITH (UPDLOCK, ROWLOCK)
                    WHERE ProductCode = @ProductCode;
                    """,
                    db,
                    transaction);
                select.Parameters.AddWithValue("@ProductCode", item.ProductCode);
                await using var reader = await select.ExecuteReaderAsync();
                if (!await reader.ReadAsync())
                {
                    throw new InvalidOperationException($"Produto {item.ProductCode} não encontrado.");
                }

                var productId = ReadString(reader, "Id");
                var productName = ReadString(reader, "ProductName");
                var currentStock = reader.GetDecimal(reader.GetOrdinal("ProductQnt"));
                var unitPrice = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
                await reader.CloseAsync();

                if (currentStock < item.Quantity)
                {
                    throw new InvalidOperationException(
                        $"Estoque insuficiente para {productName}. Disponível: {currentStock}.");
                }

                var nextStock = currentStock - item.Quantity;
                await using var update = new SqlCommand(
                    """
                    UPDATE Produtos
                       SET ProductQnt = @ProductQnt,
                           TotalPriceOnProduct = @TotalPriceOnProduct
                     WHERE Id = @Id;
                    """,
                    db,
                    transaction);
                update.Parameters.AddWithValue("@ProductQnt", nextStock);
                update.Parameters.AddWithValue("@TotalPriceOnProduct", unitPrice * nextStock);
                update.Parameters.AddWithValue("@Id", productId);
                await update.ExecuteNonQueryAsync();
            }

            await transaction.CommitAsync();
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    private async Task<string?> ResolveSupplierIdAsync(string companyId, string supplierName)
    {
        const string sql = """
            SELECT TOP 1 Id
            FROM Fornecedores
            WHERE CompanyId = @CompanyId AND (FantasyName = @Name OR CompanyName = @Name);
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Name", supplierName);
        var result = await command.ExecuteScalarAsync();
        return result as string;
    }

    private static void AddParameters(SqlCommand command, ProdutoAD product, string? supplierId)
    {
        command.Parameters.AddWithValue("@Id", product.Id);
        command.Parameters.AddWithValue("@ProductImageUrl", product.ProductImageUrl);
        command.Parameters.AddWithValue("@ProductImageName", product.ProductImageName);
        command.Parameters.AddWithValue("@ProductName", product.ProductName);
        command.Parameters.AddWithValue("@ProductCode", product.ProductCode);
        command.Parameters.AddWithValue("@ProductSupplier", product.ProductSupplier);
        command.Parameters.AddWithValue("@SupplierId", supplierId is null ? DBNull.Value : supplierId);
        command.Parameters.AddWithValue("@ProductDescription", product.ProductDescription);
        command.Parameters.AddWithValue("@ProductQnt", product.ProductQnt);
        command.Parameters.AddWithValue("@ProductUnitPrice", product.ProductUnitPrice);
        command.Parameters.AddWithValue("@ProductSalePrice", product.ProductSalePrice);
        command.Parameters.AddWithValue("@TotalPriceOnProduct", product.TotalPriceOnProduct);
        command.Parameters.AddWithValue("@Ncm", product.Ncm);
        command.Parameters.AddWithValue("@Cest", (object?)product.Cest ?? DBNull.Value);
        command.Parameters.AddWithValue("@Cfop", product.Cfop);
        command.Parameters.AddWithValue("@OrigemMercadoria", product.OrigemMercadoria);
        command.Parameters.AddWithValue("@UnidadeComercial", product.UnidadeComercial);
        command.Parameters.AddWithValue("@UnidadeTributavel", product.UnidadeTributavel);
        command.Parameters.AddWithValue("@Gtin", product.Gtin);
        command.Parameters.AddWithValue("@CsosnIcms", (object?)product.CsosnIcms ?? DBNull.Value);
        command.Parameters.AddWithValue("@CstIcms", (object?)product.CstIcms ?? DBNull.Value);
        command.Parameters.AddWithValue("@AliquotaIcms", product.AliquotaIcms);
        command.Parameters.AddWithValue("@CstPis", product.CstPis);
        command.Parameters.AddWithValue("@CstCofins", product.CstCofins);
        command.Parameters.AddWithValue("@CstIbsCbs", (object?)product.CstIbsCbs ?? DBNull.Value);
        command.Parameters.AddWithValue("@CClassTrib", (object?)product.CClassTrib ?? DBNull.Value);
    }

    private static ProdutoAD Map(SqlDataReader source) => new()
    {
        Id = ReadString(source, "Id"),
        ProductImageUrl = ReadString(source, "ProductImageUrl"),
        ProductImageName = ReadString(source, "ProductImageName"),
        ProductName = ReadString(source, "ProductName"),
        ProductCode = ReadString(source, "ProductCode"),
        ProductSupplier = ReadString(source, "ProductSupplier"),
        ProductDescription = ReadString(source, "ProductDescription"),
        ProductQnt = ReadDecimal(source, "ProductQnt"),
        ProductUnitPrice = ReadDecimal(source, "ProductUnitPrice"),
        ProductSalePrice = ReadDecimal(source, "ProductSalePrice"),
        TotalPriceOnProduct = ReadDecimal(source, "TotalPriceOnProduct"),
        Ncm = ReadString(source, "Ncm"),
        Cest = ReadNullableString(source, "Cest"),
        Cfop = ReadString(source, "Cfop"),
        OrigemMercadoria = (byte)ReadInt(source, "OrigemMercadoria"),
        UnidadeComercial = ReadString(source, "UnidadeComercial"),
        UnidadeTributavel = ReadString(source, "UnidadeTributavel"),
        Gtin = ReadString(source, "Gtin"),
        CsosnIcms = ReadNullableString(source, "CsosnIcms"),
        CstIcms = ReadNullableString(source, "CstIcms"),
        AliquotaIcms = ReadDecimal(source, "AliquotaIcms"),
        CstPis = ReadString(source, "CstPis"),
        CstCofins = ReadString(source, "CstCofins"),
        CstIbsCbs = ReadNullableString(source, "CstIbsCbs"),
        CClassTrib = ReadNullableString(source, "CClassTrib")
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

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }
}
