/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/ProdutoAB.cs
 * Objetivo: concentra comandos SQL e persistência de cadastro, estoque, manutenção e dados
 *           fiscais de produtos.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 */
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class ProdutoAB(Connection connection)
{
    private const string Columns = """
        p.Id, p.ProductImageUrl, p.ProductImageName, p.ProductName, p.ProductCode, p.ProductSupplier,
        p.ProductDescription, p.ProductQnt, p.EstoqueMinimo, p.ProductUnitPrice, p.ProductSalePrice, p.TotalPriceOnProduct,
        p.Lucro, p.MargemDesejadaPercentual, p.CategoriaId, c.Nome AS CategoriaNome,
        p.DataValidade, p.ControlaValidade, p.DiasAlertaValidade,
        p.UnidadeCompra, p.FatorConversao, p.QtdEmbalagem,
        p.Marca, p.Fabricante, p.ReferenciaFabricante,
        p.PesoLiquidoKg, p.PesoBrutoKg, p.LarguraCm, p.AlturaCm, p.ComprimentoCm,
        p.EstoqueMaximo, p.LocalizacaoEstoque,
        p.CustoMedio, p.CustoComImposto, p.CustoSemImposto,
        p.DescontoMaximoPercentual, p.ComissaoPercentual, p.MarkupCadastrado, p.MarkupPraticado,
        p.Ncm, p.Cest, p.Cfop, p.OrigemMercadoria, p.UnidadeComercial, p.UnidadeTributavel, p.Gtin,
        p.CsosnIcms, p.CstIcms, p.AliquotaIcms, p.CstPis, p.CstCofins, p.CstIbsCbs, p.CClassTrib
        """;

    public async Task<List<ProdutoAD>> ListarAsync(string companyId)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId
            ORDER BY p.ProductName;
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
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.Id = @Id AND p.CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", id);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    public async Task<int> ImportarCargaLegadoAsync(string companyId)
    {
        var scriptPath = Path.Combine(AppContext.BaseDirectory, "DataBase", "Migrations", "06_produtos_mercado_completo.sql");
        if (!File.Exists(scriptPath))
        {
            scriptPath = Path.Combine(Directory.GetCurrentDirectory(), "DataBase", "Migrations", "06_produtos_mercado_completo.sql");
        }
        if (!File.Exists(scriptPath))
        {
            throw new FileNotFoundException("Script de migração 06_produtos_mercado_completo.sql não encontrado.");
        }

        var script = await File.ReadAllTextAsync(scriptPath);
        var cleanScript = System.Text.RegularExpressions.Regex.Replace(
            script, @"^\s*GO\s*;?\s*$", "",
            System.Text.RegularExpressions.RegexOptions.IgnoreCase | System.Text.RegularExpressions.RegexOptions.Multiline);

        // Garante que a carga seja aplicada exclusivamente na empresa solicitante
        cleanScript = cleanScript.Replace("VALUES (N'empresa-principal');", "VALUES (@TargetCompanyId);");

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(cleanScript, db)
        {
            CommandTimeout = 300
        };
        command.Parameters.AddWithValue("@TargetCompanyId", companyId);
        return await command.ExecuteNonQueryAsync();
    }

    /// <summary>Usada pelo módulo fiscal (DocumentoFiscalAB) para montar o item da NFC-e.</summary>
    public async Task<ProdutoAD?> ObterPorCodigoAsync(string companyId, string productCode)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId AND p.ProductCode = @ProductCode;
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
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId AND p.Gtin = @Gtin;
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
                $"""
                SELECT {Columns}
                FROM Produtos p WITH (UPDLOCK, ROWLOCK)
                LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
                WHERE p.Id = @Id AND p.CompanyId = @CompanyId;
                """,
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

            // Custo mudou sem passar pelo formulário manual — se o produto tem uma margem
            // desejada configurada, o preço de venda é recalculado sozinho pra manter essa
            // margem em vez de ficar defasado em cima do custo antigo.
            var nextSalePrice = current.MargemDesejadaPercentual is { } margem
                ? custoUnitario * (1 + margem / 100m)
                : current.ProductSalePrice;

            await using var update = new SqlCommand(
                """
                UPDATE Produtos
                   SET ProductQnt = @ProductQnt,
                       ProductUnitPrice = @ProductUnitPrice,
                       ProductSalePrice = @ProductSalePrice,
                       TotalPriceOnProduct = @TotalPriceOnProduct,
                       Lucro = @Lucro
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction);
            var nextLucro = nextSalePrice - custoUnitario;
            update.Parameters.AddWithValue("@ProductQnt", nextQuantity);
            update.Parameters.AddWithValue("@ProductUnitPrice", custoUnitario);
            update.Parameters.AddWithValue("@ProductSalePrice", nextSalePrice);
            update.Parameters.AddWithValue("@TotalPriceOnProduct", nextTotal);
            update.Parameters.AddWithValue("@Lucro", nextLucro);
            update.Parameters.AddWithValue("@Id", productId);
            update.Parameters.AddWithValue("@CompanyId", companyId);
            await update.ExecuteNonQueryAsync();

            await transaction.CommitAsync();

            current.ProductQnt = nextQuantity;
            current.ProductUnitPrice = custoUnitario;
            current.ProductSalePrice = nextSalePrice;
            current.TotalPriceOnProduct = nextTotal;
            current.Lucro = nextLucro;
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
                       EstoqueMinimo = @EstoqueMinimo,
                       ProductUnitPrice = @ProductUnitPrice,
                       ProductSalePrice = @ProductSalePrice,
                       TotalPriceOnProduct = @TotalPriceOnProduct,
                       Lucro = @Lucro,
                       MargemDesejadaPercentual = @MargemDesejadaPercentual,
                       CategoriaId = @CategoriaId,
                       DataValidade = @DataValidade,
                       ControlaValidade = @ControlaValidade,
                       DiasAlertaValidade = @DiasAlertaValidade,
                       UnidadeCompra = @UnidadeCompra,
                       FatorConversao = @FatorConversao,
                       QtdEmbalagem = @QtdEmbalagem,
                       Marca = @Marca,
                       Fabricante = @Fabricante,
                       ReferenciaFabricante = @ReferenciaFabricante,
                       PesoLiquidoKg = @PesoLiquidoKg,
                       PesoBrutoKg = @PesoBrutoKg,
                       LarguraCm = @LarguraCm,
                       AlturaCm = @AlturaCm,
                       ComprimentoCm = @ComprimentoCm,
                       EstoqueMaximo = @EstoqueMaximo,
                       LocalizacaoEstoque = @LocalizacaoEstoque,
                       CustoMedio = @CustoMedio,
                       CustoComImposto = @CustoComImposto,
                       CustoSemImposto = @CustoSemImposto,
                       DescontoMaximoPercentual = @DescontoMaximoPercentual,
                       ComissaoPercentual = @ComissaoPercentual,
                       MarkupCadastrado = @MarkupCadastrado,
                       MarkupPraticado = @MarkupPraticado,
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
                     ProductDescription, ProductQnt, EstoqueMinimo, ProductUnitPrice, ProductSalePrice, TotalPriceOnProduct,
                     Lucro, MargemDesejadaPercentual, CategoriaId, DataValidade, ControlaValidade, DiasAlertaValidade,
                     UnidadeCompra, FatorConversao, QtdEmbalagem,
                     Marca, Fabricante, ReferenciaFabricante,
                     PesoLiquidoKg, PesoBrutoKg, LarguraCm, AlturaCm, ComprimentoCm,
                     EstoqueMaximo, LocalizacaoEstoque,
                     CustoMedio, CustoComImposto, CustoSemImposto,
                     DescontoMaximoPercentual, ComissaoPercentual, MarkupCadastrado, MarkupPraticado,
                     Ncm, Cest, Cfop, OrigemMercadoria, UnidadeComercial, UnidadeTributavel, Gtin,
                     CsosnIcms, CstIcms, AliquotaIcms, CstPis, CstCofins, CstIbsCbs, CClassTrib)
                VALUES
                    (@Id, @CompanyId, @ProductImageUrl, @ProductImageName, @ProductName, @ProductCode, @ProductSupplier, @SupplierId,
                     @ProductDescription, @ProductQnt, @EstoqueMinimo, @ProductUnitPrice, @ProductSalePrice, @TotalPriceOnProduct,
                     @Lucro, @MargemDesejadaPercentual, @CategoriaId, @DataValidade, @ControlaValidade, @DiasAlertaValidade,
                     @UnidadeCompra, @FatorConversao, @QtdEmbalagem,
                     @Marca, @Fabricante, @ReferenciaFabricante,
                     @PesoLiquidoKg, @PesoBrutoKg, @LarguraCm, @AlturaCm, @ComprimentoCm,
                     @EstoqueMaximo, @LocalizacaoEstoque,
                     @CustoMedio, @CustoComImposto, @CustoSemImposto,
                     @DescontoMaximoPercentual, @ComissaoPercentual, @MarkupCadastrado, @MarkupPraticado,
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

    public async Task<List<ProdutoAD>> ListarVencimentosAsync(string companyId, int dias)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId
              AND p.ControlaValidade = 1
              AND p.DataValidade IS NOT NULL
              AND p.DataValidade <= DATEADD(day, @Dias, CAST(GETDATE() AS DATE))
            ORDER BY p.DataValidade ASC, p.ProductName ASC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Dias", dias);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<ProdutoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<VencimentoResumoModel> ObterResumoVencimentosAsync(string companyId)
    {
        const string sql = """
            SELECT
                COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade < CAST(GETDATE() AS DATE) THEN 1 END) AS Vencidos,
                COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade >= CAST(GETDATE() AS DATE) AND DataValidade <= DATEADD(day, 7, CAST(GETDATE() AS DATE)) THEN 1 END) AS VenceEm7Dias,
                COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade >= CAST(GETDATE() AS DATE) AND DataValidade <= DATEADD(day, 15, CAST(GETDATE() AS DATE)) THEN 1 END) AS VenceEm15Dias,
                COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade >= CAST(GETDATE() AS DATE) AND DataValidade <= DATEADD(day, 30, CAST(GETDATE() AS DATE)) THEN 1 END) AS VenceEm30Dias,
                COUNT(CASE WHEN ControlaValidade = 1 THEN 1 END) AS TotalControlados,
                COUNT(CASE WHEN ControlaValidade = 1 AND DataValidade IS NULL THEN 1 END) AS SemDataInformada
            FROM Produtos
            WHERE CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        if (await reader.ReadAsync())
        {
            return new VencimentoResumoModel
            {
                Vencidos = ReadInt(reader, "Vencidos"),
                VenceEm7Dias = ReadInt(reader, "VenceEm7Dias"),
                VenceEm15Dias = ReadInt(reader, "VenceEm15Dias"),
                VenceEm30Dias = ReadInt(reader, "VenceEm30Dias"),
                TotalControlados = ReadInt(reader, "TotalControlados"),
                SemDataInformada = ReadInt(reader, "SemDataInformada")
            };
        }

        return new VencimentoResumoModel();
    }

    public async Task<bool> AtualizarValidadeAsync(string companyId, string produtoId, DateTime? dataValidade)
    {
        const string sql = """
            UPDATE Produtos
               SET DataValidade = @DataValidade
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", produtoId);
        command.Parameters.AddWithValue("@DataValidade", (object?)dataValidade?.Date ?? DBNull.Value);
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
        command.Parameters.AddWithValue("@EstoqueMinimo", product.EstoqueMinimo);
        command.Parameters.AddWithValue("@ProductUnitPrice", product.ProductUnitPrice);
        command.Parameters.AddWithValue("@ProductSalePrice", product.ProductSalePrice);
        command.Parameters.AddWithValue("@TotalPriceOnProduct", product.TotalPriceOnProduct);
        command.Parameters.AddWithValue("@Lucro", product.Lucro);
        command.Parameters.AddWithValue("@MargemDesejadaPercentual", (object?)product.MargemDesejadaPercentual ?? DBNull.Value);
        command.Parameters.AddWithValue("@CategoriaId", (object?)product.CategoriaId ?? DBNull.Value);
        command.Parameters.AddWithValue("@DataValidade", (object?)product.DataValidade?.Date ?? DBNull.Value);
        command.Parameters.AddWithValue("@ControlaValidade", product.ControlaValidade);
        command.Parameters.AddWithValue("@DiasAlertaValidade", product.DiasAlertaValidade);
        command.Parameters.AddWithValue("@Ncm", product.Ncm);
        command.Parameters.AddWithValue("@Cest", (object?)product.Cest ?? DBNull.Value);
        command.Parameters.AddWithValue("@Cfop", product.Cfop);
        command.Parameters.AddWithValue("@OrigemMercadoria", product.OrigemMercadoria);
        command.Parameters.AddWithValue("@UnidadeComercial", product.UnidadeComercial);
        command.Parameters.AddWithValue("@UnidadeTributavel", product.UnidadeTributavel);
        command.Parameters.AddWithValue("@Gtin", product.Gtin);
        command.Parameters.AddWithValue("@UnidadeCompra", product.UnidadeCompra);
        command.Parameters.AddWithValue("@FatorConversao", product.FatorConversao);
        command.Parameters.AddWithValue("@QtdEmbalagem", product.QtdEmbalagem);
        command.Parameters.AddWithValue("@Marca", (object?)product.Marca ?? DBNull.Value);
        command.Parameters.AddWithValue("@Fabricante", (object?)product.Fabricante ?? DBNull.Value);
        command.Parameters.AddWithValue("@ReferenciaFabricante", (object?)product.ReferenciaFabricante ?? DBNull.Value);
        command.Parameters.AddWithValue("@PesoLiquidoKg", product.PesoLiquidoKg);
        command.Parameters.AddWithValue("@PesoBrutoKg", product.PesoBrutoKg);
        command.Parameters.AddWithValue("@LarguraCm", product.LarguraCm);
        command.Parameters.AddWithValue("@AlturaCm", product.AlturaCm);
        command.Parameters.AddWithValue("@ComprimentoCm", product.ComprimentoCm);
        command.Parameters.AddWithValue("@EstoqueMaximo", product.EstoqueMaximo);
        command.Parameters.AddWithValue("@LocalizacaoEstoque", (object?)product.LocalizacaoEstoque ?? DBNull.Value);
        command.Parameters.AddWithValue("@CustoMedio", product.CustoMedio);
        command.Parameters.AddWithValue("@CustoComImposto", product.CustoComImposto);
        command.Parameters.AddWithValue("@CustoSemImposto", product.CustoSemImposto);
        command.Parameters.AddWithValue("@DescontoMaximoPercentual", product.DescontoMaximoPercentual);
        command.Parameters.AddWithValue("@ComissaoPercentual", product.ComissaoPercentual);
        command.Parameters.AddWithValue("@MarkupCadastrado", product.MarkupCadastrado);
        command.Parameters.AddWithValue("@MarkupPraticado", product.MarkupPraticado);
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
        EstoqueMinimo = ReadDecimal(source, "EstoqueMinimo"),
        ProductUnitPrice = ReadDecimal(source, "ProductUnitPrice"),
        ProductSalePrice = ReadDecimal(source, "ProductSalePrice"),
        TotalPriceOnProduct = ReadDecimal(source, "TotalPriceOnProduct"),
        Lucro = ReadDecimal(source, "Lucro"),
        MargemDesejadaPercentual = ReadNullableDecimal(source, "MargemDesejadaPercentual"),
        CategoriaId = ReadNullableString(source, "CategoriaId"),
        CategoriaNome = ReadNullableString(source, "CategoriaNome"),
        DataValidade = ReadNullableDateTime(source, "DataValidade"),
        ControlaValidade = ReadBool(source, "ControlaValidade"),
        DiasAlertaValidade = ReadInt(source, "DiasAlertaValidade"),
        UnidadeCompra = ReadString(source, "UnidadeCompra"),
        FatorConversao = ReadDecimal(source, "FatorConversao"),
        QtdEmbalagem = ReadDecimal(source, "QtdEmbalagem"),
        Marca = ReadNullableString(source, "Marca"),
        Fabricante = ReadNullableString(source, "Fabricante"),
        ReferenciaFabricante = ReadNullableString(source, "ReferenciaFabricante"),
        PesoLiquidoKg = ReadDecimal(source, "PesoLiquidoKg"),
        PesoBrutoKg = ReadDecimal(source, "PesoBrutoKg"),
        LarguraCm = ReadDecimal(source, "LarguraCm"),
        AlturaCm = ReadDecimal(source, "AlturaCm"),
        ComprimentoCm = ReadDecimal(source, "ComprimentoCm"),
        EstoqueMaximo = ReadDecimal(source, "EstoqueMaximo"),
        LocalizacaoEstoque = ReadNullableString(source, "LocalizacaoEstoque"),
        CustoMedio = ReadDecimal(source, "CustoMedio"),
        CustoComImposto = ReadDecimal(source, "CustoComImposto"),
        CustoSemImposto = ReadDecimal(source, "CustoSemImposto"),
        DescontoMaximoPercentual = ReadDecimal(source, "DescontoMaximoPercentual"),
        ComissaoPercentual = ReadDecimal(source, "ComissaoPercentual"),
        MarkupCadastrado = ReadDecimal(source, "MarkupCadastrado"),
        MarkupPraticado = ReadDecimal(source, "MarkupPraticado"),
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

    private static DateTime? ReadNullableDateTime(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDateTime(ordinal);
    }

    private static bool ReadBool(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return !reader.IsDBNull(ordinal) && reader.GetBoolean(ordinal);
    }

    /// <summary>
    /// Ajuste manual de estoque (entrada ou saída). Atualiza ProductQnt e TotalPriceOnProduct.
    /// </summary>
    public async Task<bool> AjustarEstoqueAsync(string companyId, string productId, string tipo, decimal quantidade)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            await using var select = new SqlCommand(
                """
                SELECT ProductQnt, ProductUnitPrice
                FROM Produtos WITH (UPDLOCK, ROWLOCK)
                WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction);
            select.Parameters.AddWithValue("@Id", productId);
            select.Parameters.AddWithValue("@CompanyId", companyId);

            decimal currentQty;
            decimal unitPrice;
            await using (var reader = await select.ExecuteReaderAsync())
            {
                if (!await reader.ReadAsync())
                    return false;

                currentQty = reader.GetDecimal(reader.GetOrdinal("ProductQnt"));
                unitPrice = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
            }

            var nextQty = string.Equals(tipo, "entrada", StringComparison.OrdinalIgnoreCase)
                ? currentQty + quantidade
                : currentQty - quantidade;

            if (nextQty < 0)
                throw new InvalidOperationException(
                    $"Estoque insuficiente. Disponível: {currentQty}, solicitado: {quantidade}.");

            await using var update = new SqlCommand(
                """
                UPDATE Produtos
                   SET ProductQnt = @ProductQnt,
                       TotalPriceOnProduct = @TotalPriceOnProduct
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction);
            update.Parameters.AddWithValue("@ProductQnt", nextQty);
            update.Parameters.AddWithValue("@TotalPriceOnProduct", unitPrice * nextQty);
            update.Parameters.AddWithValue("@Id", productId);
            update.Parameters.AddWithValue("@CompanyId", companyId);
            await update.ExecuteNonQueryAsync();

            await transaction.CommitAsync();
            return true;
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }
}
