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
    public static string VersaoPreco(ProdutoAD product) => string.Join('|',
        product.ProductUnitPrice.ToString("0.####", System.Globalization.CultureInfo.InvariantCulture),
        product.ProductSalePrice.ToString("0.####", System.Globalization.CultureInfo.InvariantCulture));

    public async Task<(ProdutoAD? Product, bool Conflict)> AtualizarPrecoAsync(string companyId, string id, decimal sale, decimal expectedCost, decimal expectedSale)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        using var scope = connection.UseImportTransaction(tx);
        var sql = $"""
            SELECT {Columns} FROM Produtos p WITH (UPDLOCK, ROWLOCK)
            LEFT JOIN Categorias c ON c.Id=p.CategoriaId AND c.CompanyId=p.CompanyId
            WHERE p.Id=@Id AND p.CompanyId=@CompanyId;
            """;
        ProdutoAD? product;
        await using (var cmd = connection.CreateCommand(sql, db))
        {
            cmd.Parameters.AddWithValue("@Id", id); cmd.Parameters.AddWithValue("@CompanyId", companyId);
            await using var reader = await cmd.ExecuteReaderAsync();
            product = await reader.ReadAsync() ? Map(reader) : null;
        }
        if (product is null) return (null, false);
        if (product.ProductUnitPrice != expectedCost || (product.ProductSalePrice != expectedSale && product.ProductSalePrice != sale))
            return (product, true);
        var profit = sale - product.ProductUnitPrice;
        var markup = product.ProductUnitPrice > 0 ? decimal.Round(profit / product.ProductUnitPrice * 100, 4, MidpointRounding.AwayFromZero) : 0;
        if (markup > 99_999.9999m) throw new InvalidOperationException("O markup calculado excede o limite de 99.999,9999%. Confira o custo e o preço de venda.");
        await using (var cmd = connection.CreateCommand("UPDATE Produtos SET ProductSalePrice=@Sale, Lucro=@Profit, MarkupPraticado=@Markup WHERE Id=@Id AND CompanyId=@CompanyId", db))
        {
            cmd.Parameters.AddWithValue("@Sale", sale); cmd.Parameters.AddWithValue("@Profit", profit); cmd.Parameters.AddWithValue("@Markup", markup);
            cmd.Parameters.AddWithValue("@Id", id); cmd.Parameters.AddWithValue("@CompanyId", companyId);
            await cmd.ExecuteNonQueryAsync();
        }
        product.ProductSalePrice = sale; product.Lucro = profit; product.MarkupPraticado = markup;
        await tx.CommitAsync();
        return (product, false);
    }

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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(cleanScript, db);
        command.CommandTimeout = 300;
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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
    public async Task<ProdutoAD> EntradaEstoqueAsync(string companyId, string productId, decimal quantidadeRecebida, decimal custoUnitario,
        bool preservarPrecoVenda = false, decimal? precoVendaExplicito = null)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var ownedTransaction = connection.CurrentImportTransaction is null ? (SqlTransaction)await db.BeginTransactionAsync() : null;
        var transaction = connection.CurrentImportTransaction ?? ownedTransaction!;
        try
        {
            await using var select = connection.CreateCommand(
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
            // Margem nula OU zero = "sem margem cadastrada": o preço de venda atual é preservado.
            // (Margem 0 recalcularia o preço para igualar o custo, o que apagaria o lucro do produto.)
            var margemCadastrada = current.MargemDesejadaPercentual is > 0m ? current.MargemDesejadaPercentual : null;
            var nextSalePrice = precoVendaExplicito ?? (preservarPrecoVenda ? current.ProductSalePrice : margemCadastrada is { } margem
                ? custoUnitario * (1 + margem / 100m)
                : current.ProductSalePrice);

            await using var update = connection.CreateCommand(
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

            if (ownedTransaction is not null) await ownedTransaction.CommitAsync();

            current.ProductQnt = nextQuantity;
            current.ProductUnitPrice = custoUnitario;
            current.ProductSalePrice = nextSalePrice;
            current.TotalPriceOnProduct = nextTotal;
            current.Lucro = nextLucro;
            return current;
        }
        catch
        {
            if (ownedTransaction is not null) await ownedTransaction.RollbackAsync();
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        AddParameters(command, product, supplierId);
        await command.ExecuteNonQueryAsync();
        return product;
    }

    /// <summary>
    /// Estorna (subtrai) uma quantidade do estoque do produto. Permite estoque negativo para manter
    /// a integridade do estorno mesmo que parte do estoque já tenha sido vendida.
    /// </summary>
    public async Task EstornarEstoqueAsync(string companyId, string productId, decimal quantidade)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand("""
            UPDATE Produtos
               SET ProductQnt = ProductQnt - @Quantidade,
                   TotalPriceOnProduct = ProductUnitPrice * (ProductQnt - @Quantidade)
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """, db);
        cmd.Parameters.AddWithValue("@Quantidade", quantidade);
        cmd.Parameters.AddWithValue("@Id", productId);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await cmd.ExecuteNonQueryAsync();
    }

    public async Task<bool> ExcluirAsync(string companyId, string id)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand("DELETE FROM Produtos WHERE Id = @Id AND CompanyId = @CompanyId;", db);
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Id", produtoId);
        command.Parameters.AddWithValue("@DataValidade", (object?)dataValidade?.Date ?? DBNull.Value);
        return await command.ExecuteNonQueryAsync() > 0;
    }

    public async Task BaixarEstoqueAsync(string companyId, IEnumerable<(string ProductCode, decimal Quantity)> items)
    {
        var groupedItems = items
            .GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(group => new { ProductCode = group.Key, Quantity = group.Sum(item => item.Quantity) })
            .ToList();

        await using var db = await connection.OpenLeaseAsync();
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

                await using var select = connection.CreateCommand(
                    """
                    SELECT Id, ProductName, ProductQnt, ProductUnitPrice
                    FROM Produtos WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;
                    """,
                    db,
                    transaction);
                select.Parameters.AddWithValue("@CompanyId", companyId);
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
                await using var update = connection.CreateCommand(
                    """
                    UPDATE Produtos
                       SET ProductQnt = @ProductQnt,
                           TotalPriceOnProduct = @TotalPriceOnProduct
                     WHERE Id = @Id AND CompanyId = @CompanyId;
                    """,
                    db,
                    transaction);
                update.Parameters.AddWithValue("@ProductQnt", nextStock);
                update.Parameters.AddWithValue("@TotalPriceOnProduct", unitPrice * nextStock);
                update.Parameters.AddWithValue("@Id", productId);
                update.Parameters.AddWithValue("@CompanyId", companyId);
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
        if (string.IsNullOrWhiteSpace(supplierName)) return null;

        const string sql = """
            SELECT TOP 1 Id
            FROM Fornecedores
            WHERE CompanyId = @CompanyId AND (FantasyName = @Name OR CompanyName = @Name);
            """;

        await using var db = await connection.OpenLeaseAsync();
        await using var command = connection.CreateCommand(sql, db);
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

    // ---------------------------------------------------------------- Auditoria fiscal (MCP)

    private const string FiscalColumns = """
        p.Id, p.ProductCode, p.ProductName, p.Gtin, p.Marca, p.CategoriaId, c.Nome AS CategoriaNome,
        p.UnidadeComercial, p.UnidadeTributavel, p.Ncm, p.Cest, p.Cfop, p.OrigemMercadoria,
        p.CsosnIcms, p.CstIcms, p.AliquotaIcms, p.CstPis, p.CstCofins, p.CstIbsCbs, p.CClassTrib
        """;

    public sealed record ProdutoFiscalRow(string Id, string ProductCode, string ProductName, string Gtin,
        string? Marca, string? CategoriaId, string? CategoriaNome, string UnidadeComercial, string UnidadeTributavel,
        string Ncm, string? Cest, string Cfop, byte OrigemMercadoria,
        string? CsosnIcms, string? CstIcms, decimal AliquotaIcms,
        string CstPis, string CstCofins, string? CstIbsCbs, string? CClassTrib);

    public sealed record ProdutoFiscalPagina(List<ProdutoFiscalRow> Itens, int Total);

    public async Task<ProdutoFiscalPagina> ListarFiscalAsync(string companyId, string? busca, string? categoriaId,
        bool somenteSemNcm, bool somenteSemCest, int pagina, int tamanhoPagina)
    {
        var where = new System.Text.StringBuilder("p.CompanyId = @CompanyId");
        if (!string.IsNullOrWhiteSpace(busca))
            where.Append(" AND (p.ProductName LIKE @Busca ESCAPE '~' OR p.ProductCode LIKE @Busca ESCAPE '~' OR p.Gtin LIKE @Busca ESCAPE '~')");
        if (!string.IsNullOrWhiteSpace(categoriaId))
            where.Append(" AND p.CategoriaId = @CategoriaId");
        if (somenteSemNcm)
            where.Append(" AND (p.Ncm IS NULL OR p.Ncm = '' OR p.Ncm = '00000000')");
        if (somenteSemCest)
            where.Append(" AND (p.Cest IS NULL OR p.Cest = '')");

        var sql = $"""
            SELECT COUNT(*) FROM Produtos p WHERE {where};
            SELECT {FiscalColumns}
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE {where}
            ORDER BY p.ProductName
            OFFSET @Offset ROWS FETCH NEXT @Limit ROWS ONLY;
            """;

        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Offset", (pagina - 1) * tamanhoPagina);
        cmd.Parameters.AddWithValue("@Limit", tamanhoPagina);
        if (!string.IsNullOrWhiteSpace(busca))
        {
            var term = busca.Trim();
            if (term.Length > 100) term = term[..100];
            term = term.Replace("~", "~~").Replace("%", "~%").Replace("_", "~_").Replace("[", "~[");
            cmd.Parameters.AddWithValue("@Busca", "%" + term + "%");
        }
        if (!string.IsNullOrWhiteSpace(categoriaId))
            cmd.Parameters.AddWithValue("@CategoriaId", categoriaId.Trim());

        await using var reader = await cmd.ExecuteReaderAsync();
        await reader.ReadAsync();
        var total = reader.GetInt32(0);
        await reader.NextResultAsync();

        var rows = new List<ProdutoFiscalRow>();
        while (await reader.ReadAsync()) rows.Add(MapFiscal(reader));
        return new(rows, total);
    }

    public sealed record AuditoriaFiscalContadores(int Total, int SemNcm, int NcmFormatoInvalido,
        int SemGtin, int GtinFormatoInvalido, int SemCfop, int CfopFormatoInvalido,
        int SemIcms, int SemPis, int SemCofins, int CestFormatoInvalido,
        int OrigemInvalida, int UnidadeDivergente, int IbsIncompleto);

    public async Task<AuditoriaFiscalContadores> ContarPendenciasFiscaisAsync(string companyId)
    {
        const string sql = """
            SELECT
                COUNT(*) AS Total,
                COUNT(CASE WHEN Ncm IS NULL OR Ncm = '' OR Ncm = '00000000' THEN 1 END) AS SemNcm,
                COUNT(CASE WHEN Ncm IS NOT NULL AND Ncm != '' AND Ncm != '00000000'
                    AND (LEN(Ncm) != 8 OR Ncm LIKE '%[^0-9]%') THEN 1 END) AS NcmFormatoInvalido,
                COUNT(CASE WHEN Gtin IS NULL OR Gtin = '' OR Gtin = 'SEM GTIN' THEN 1 END) AS SemGtin,
                COUNT(CASE WHEN Gtin IS NOT NULL AND Gtin != '' AND Gtin != 'SEM GTIN'
                    AND (LEN(Gtin) NOT IN (8,12,13,14) OR Gtin LIKE '%[^0-9]%') THEN 1 END) AS GtinFormatoInvalido,
                COUNT(CASE WHEN Cfop IS NULL OR Cfop = '' THEN 1 END) AS SemCfop,
                COUNT(CASE WHEN Cfop IS NOT NULL AND Cfop != ''
                    AND (LEN(Cfop) != 4 OR Cfop LIKE '%[^0-9]%' OR LEFT(Cfop,1) != '5') THEN 1 END) AS CfopFormatoInvalido,
                COUNT(CASE WHEN (CsosnIcms IS NULL OR CsosnIcms = '') AND (CstIcms IS NULL OR CstIcms = '') THEN 1 END) AS SemIcms,
                COUNT(CASE WHEN CstPis IS NULL OR CstPis = '' THEN 1 END) AS SemPis,
                COUNT(CASE WHEN CstCofins IS NULL OR CstCofins = '' THEN 1 END) AS SemCofins,
                COUNT(CASE WHEN Cest IS NOT NULL AND Cest != ''
                    AND (LEN(Cest) != 7 OR Cest LIKE '%[^0-9]%') THEN 1 END) AS CestFormatoInvalido,
                COUNT(CASE WHEN OrigemMercadoria > 8 THEN 1 END) AS OrigemInvalida,
                COUNT(CASE WHEN UnidadeComercial != UnidadeTributavel THEN 1 END) AS UnidadeDivergente,
                COUNT(CASE WHEN (CstIbsCbs IS NOT NULL AND CstIbsCbs != '' AND (CClassTrib IS NULL OR CClassTrib = ''))
                    OR (CClassTrib IS NOT NULL AND CClassTrib != '' AND (CstIbsCbs IS NULL OR CstIbsCbs = '')) THEN 1 END) AS IbsIncompleto
            FROM Produtos
            WHERE CompanyId = @CompanyId;
            """;

        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await cmd.ExecuteReaderAsync();
        await reader.ReadAsync();
        return new(
            ReadInt(reader, "Total"), ReadInt(reader, "SemNcm"), ReadInt(reader, "NcmFormatoInvalido"),
            ReadInt(reader, "SemGtin"), ReadInt(reader, "GtinFormatoInvalido"),
            ReadInt(reader, "SemCfop"), ReadInt(reader, "CfopFormatoInvalido"),
            ReadInt(reader, "SemIcms"), ReadInt(reader, "SemPis"), ReadInt(reader, "SemCofins"),
            ReadInt(reader, "CestFormatoInvalido"), ReadInt(reader, "OrigemInvalida"),
            ReadInt(reader, "UnidadeDivergente"), ReadInt(reader, "IbsIncompleto"));
    }

    public async Task<ProdutoFiscalPagina> ListarPendenciasAsync(string companyId, string? tipoPendencia,
        string? categoriaId, int pagina, int tamanhoPagina)
    {
        var where = new System.Text.StringBuilder("p.CompanyId = @CompanyId");
        if (!string.IsNullOrWhiteSpace(categoriaId))
            where.Append(" AND p.CategoriaId = @CategoriaId");

        var pendenciaFilter = (tipoPendencia ?? "").Trim().ToLowerInvariant() switch
        {
            "ncm_ausente" => " AND (p.Ncm IS NULL OR p.Ncm = '' OR p.Ncm = '00000000')",
            "ncm_formato" => " AND p.Ncm IS NOT NULL AND p.Ncm != '' AND p.Ncm != '00000000' AND (LEN(p.Ncm) != 8 OR p.Ncm LIKE '%[^0-9]%')",
            "gtin_ausente" => " AND (p.Gtin IS NULL OR p.Gtin = '' OR p.Gtin = 'SEM GTIN')",
            "gtin_formato" => " AND p.Gtin IS NOT NULL AND p.Gtin != '' AND p.Gtin != 'SEM GTIN' AND (LEN(p.Gtin) NOT IN (8,12,13,14) OR p.Gtin LIKE '%[^0-9]%')",
            "cfop_ausente" => " AND (p.Cfop IS NULL OR p.Cfop = '')",
            "cfop_formato" => " AND p.Cfop IS NOT NULL AND p.Cfop != '' AND (LEN(p.Cfop) != 4 OR p.Cfop LIKE '%[^0-9]%' OR LEFT(p.Cfop,1) != '5')",
            "icms_ausente" => " AND (p.CsosnIcms IS NULL OR p.CsosnIcms = '') AND (p.CstIcms IS NULL OR p.CstIcms = '')",
            "pis_ausente" => " AND (p.CstPis IS NULL OR p.CstPis = '')",
            "cofins_ausente" => " AND (p.CstCofins IS NULL OR p.CstCofins = '')",
            "cest_formato" => " AND p.Cest IS NOT NULL AND p.Cest != '' AND (LEN(p.Cest) != 7 OR p.Cest LIKE '%[^0-9]%')",
            "origem_invalida" => " AND p.OrigemMercadoria > 8",
            "unidade_divergente" => " AND p.UnidadeComercial != p.UnidadeTributavel",
            "ibs_incompleto" => " AND ((p.CstIbsCbs IS NOT NULL AND p.CstIbsCbs != '' AND (p.CClassTrib IS NULL OR p.CClassTrib = '')) OR (p.CClassTrib IS NOT NULL AND p.CClassTrib != '' AND (p.CstIbsCbs IS NULL OR p.CstIbsCbs = '')))",
            "" or "todas" => @" AND (
                (p.Ncm IS NULL OR p.Ncm = '' OR p.Ncm = '00000000')
                OR (p.Ncm IS NOT NULL AND p.Ncm != '' AND p.Ncm != '00000000' AND (LEN(p.Ncm) != 8 OR p.Ncm LIKE '%[^0-9]%'))
                OR (p.Cfop IS NULL OR p.Cfop = '')
                OR (p.Cfop IS NOT NULL AND p.Cfop != '' AND (LEN(p.Cfop) != 4 OR p.Cfop LIKE '%[^0-9]%' OR LEFT(p.Cfop,1) != '5'))
                OR ((p.CsosnIcms IS NULL OR p.CsosnIcms = '') AND (p.CstIcms IS NULL OR p.CstIcms = ''))
                OR (p.CstPis IS NULL OR p.CstPis = '')
                OR (p.CstCofins IS NULL OR p.CstCofins = '')
                OR (p.Cest IS NOT NULL AND p.Cest != '' AND (LEN(p.Cest) != 7 OR p.Cest LIKE '%[^0-9]%'))
                OR p.OrigemMercadoria > 8
                OR ((p.CstIbsCbs IS NOT NULL AND p.CstIbsCbs != '' AND (p.CClassTrib IS NULL OR p.CClassTrib = ''))
                    OR (p.CClassTrib IS NOT NULL AND p.CClassTrib != '' AND (p.CstIbsCbs IS NULL OR p.CstIbsCbs = '')))
            )",
            _ => null
        };

        if (pendenciaFilter is null) return new([], 0);
        where.Append(pendenciaFilter);

        var sql = $"""
            SELECT COUNT(*) FROM Produtos p WHERE {where};
            SELECT {FiscalColumns}
            FROM Produtos p
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            WHERE {where}
            ORDER BY p.ProductName
            OFFSET @Offset ROWS FETCH NEXT @Limit ROWS ONLY;
            """;

        await using var db = await connection.OpenLeaseAsync();
        await using var cmd = connection.CreateCommand(sql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@Offset", (pagina - 1) * tamanhoPagina);
        cmd.Parameters.AddWithValue("@Limit", tamanhoPagina);
        if (!string.IsNullOrWhiteSpace(categoriaId))
            cmd.Parameters.AddWithValue("@CategoriaId", categoriaId.Trim());

        await using var reader = await cmd.ExecuteReaderAsync();
        await reader.ReadAsync();
        var total = reader.GetInt32(0);
        await reader.NextResultAsync();

        var rows = new List<ProdutoFiscalRow>();
        while (await reader.ReadAsync()) rows.Add(MapFiscal(reader));
        return new(rows, total);
    }

    private static ProdutoFiscalRow MapFiscal(SqlDataReader r) => new(
        ReadString(r, "Id"), ReadString(r, "ProductCode"), ReadString(r, "ProductName"),
        ReadString(r, "Gtin"), ReadNullableString(r, "Marca"),
        ReadNullableString(r, "CategoriaId"), ReadNullableString(r, "CategoriaNome"),
        ReadString(r, "UnidadeComercial"), ReadString(r, "UnidadeTributavel"),
        ReadString(r, "Ncm"), ReadNullableString(r, "Cest"), ReadString(r, "Cfop"),
        (byte)ReadInt(r, "OrigemMercadoria"),
        ReadNullableString(r, "CsosnIcms"), ReadNullableString(r, "CstIcms"), ReadDecimal(r, "AliquotaIcms"),
        ReadString(r, "CstPis"), ReadString(r, "CstCofins"),
        ReadNullableString(r, "CstIbsCbs"), ReadNullableString(r, "CClassTrib"));

    /// <summary>
    /// Ajuste manual de estoque (entrada ou saída). Atualiza ProductQnt e TotalPriceOnProduct.
    /// Retorna (estoqueAnterior, estoqueNovo) ou null se o produto não existe.
    /// </summary>
    public async Task<(decimal anterior, decimal novo)?> AjustarEstoqueAsync(string companyId, string productId, string tipo, decimal quantidade)
    {
        await using var db = await connection.OpenLeaseAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();
        try
        {
            await using var select = connection.CreateCommand(
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
                    return null;

                currentQty = reader.GetDecimal(reader.GetOrdinal("ProductQnt"));
                unitPrice = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
            }

            var nextQty = string.Equals(tipo, "entrada", StringComparison.OrdinalIgnoreCase)
                ? currentQty + quantidade
                : currentQty - quantidade;

            if (nextQty < 0)
                throw new InvalidOperationException(
                    $"Estoque insuficiente. Disponível: {currentQty}, solicitado: {quantidade}.");

            await using var update = connection.CreateCommand(
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
            return (currentQty, nextQty);
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }
}
