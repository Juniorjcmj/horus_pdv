/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/HistoricoVendasAB.cs
 * Objetivo: concentra comandos SQL e persistência de histórico de vendas e recibos.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 *
 * TotalAmount/UnitPrice/ItemTotal são DECIMAL nativo no banco (migração 01) — a formatação
 * pt-BR do contrato HTTP acontece aqui, na borda, via HorusMoneyFormat.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class HistoricoVendasAB(Connection connection)
{
    public async Task<List<VendaHistoricoAD>> ListarAsync(string companyId, string? saleNumber = null)
    {
        const string sql = """
            SELECT v.SaleNumber, v.CustomerName, v.CustomerCpf, v.PaymentType,
                   v.TotalAmount, v.OperatorName, v.SaleDate,
                   i.ProductCode, i.ProductName, i.Quantity, i.UnitPrice, i.ItemTotal
            FROM VendaItens i
            INNER JOIN Vendas v ON v.Id = i.VendaId
            WHERE v.CompanyId = @CompanyId
              AND (@SaleNumber IS NULL OR v.SaleNumber = @SaleNumber)
            ORDER BY v.SaleDate DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@SaleNumber", string.IsNullOrWhiteSpace(saleNumber) ? DBNull.Value : saleNumber);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<VendaHistoricoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    /// <summary>Usada pelo módulo fiscal (DocumentoFiscalAB) para montar o item da NFC-e a partir do VendaId.</summary>
    public async Task<List<VendaHistoricoAD>> ObterPorIdAsync(string companyId, string vendaId)
    {
        const string sql = """
            SELECT v.SaleNumber, v.CustomerName, v.CustomerCpf, v.PaymentType,
                   v.TotalAmount, v.OperatorName, v.SaleDate,
                   i.ProductCode, i.ProductName, i.Quantity, i.UnitPrice, i.ItemTotal
            FROM VendaItens i
            INNER JOIN Vendas v ON v.Id = i.VendaId
            WHERE v.CompanyId = @CompanyId AND v.Id = @VendaId
            ORDER BY i.Id;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@VendaId", vendaId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<VendaHistoricoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<VendaRegistroResultadoAD> RegistrarAsync(string companyId, VendaRequest request)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            var saleNumber = await NextSaleNumberAsync(db, transaction);
            var now = DateTimeOffset.Now;
            var saleId = $"sale-{saleNumber}";
            var customerName = string.IsNullOrWhiteSpace(request.CustomerName) ? "Consumidor" : request.CustomerName.Trim();
            var customerCpf = string.IsNullOrWhiteSpace(request.CustomerCpf) ? "-" : request.CustomerCpf.Trim();
            var paymentType = string.IsNullOrWhiteSpace(request.PaymentType) ? "-" : request.PaymentType.Trim();
            var totalAmount = HorusMoneyFormat.ParseDecimal(request.TotalAmount);
            var operatorName = string.IsNullOrWhiteSpace(request.OperatorName) ? "Operador" : request.OperatorName.Trim();
            // A venda e a baixa de estoque compartilham a mesma transação para evitar histórico sem estoque atualizado.
            var saleItems = await BaixarEstoqueAsync(db, transaction, companyId, request.Items);

            await using (var saleCommand = new SqlCommand(
                             """
                             INSERT INTO Vendas
                                 (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, TotalAmount, OperatorName, SaleDate)
                             VALUES
                                 (@Id, @CompanyId, @SaleNumber, @CustomerName, @CustomerCpf, @PaymentType, @TotalAmount, @OperatorName, @SaleDate);
                             """,
                             db,
                             transaction))
            {
                saleCommand.Parameters.AddWithValue("@Id", saleId);
                saleCommand.Parameters.AddWithValue("@CompanyId", companyId);
                saleCommand.Parameters.AddWithValue("@SaleNumber", saleNumber);
                saleCommand.Parameters.AddWithValue("@CustomerName", customerName);
                saleCommand.Parameters.AddWithValue("@CustomerCpf", customerCpf);
                saleCommand.Parameters.AddWithValue("@PaymentType", paymentType);
                saleCommand.Parameters.AddWithValue("@TotalAmount", totalAmount);
                saleCommand.Parameters.AddWithValue("@OperatorName", operatorName);
                saleCommand.Parameters.AddWithValue("@SaleDate", now);
                await saleCommand.ExecuteNonQueryAsync();
            }

            var rows = new List<VendaHistoricoAD>();
            for (var index = 0; index < saleItems.Count; index += 1)
            {
                var item = saleItems[index];
                var itemId = $"{saleId}-item-{index + 1:000}";
                var itemTotal = Math.Round(item.UnitPrice * item.Quantity, 2, MidpointRounding.AwayFromZero);
                await using var itemCommand = new SqlCommand(
                    """
                    INSERT INTO VendaItens
                        (Id, VendaId, ProductCode, ProductName, Quantity, UnitPrice, ItemTotal)
                    VALUES
                        (@Id, @VendaId, @ProductCode, @ProductName, @Quantity, @UnitPrice, @ItemTotal);
                    """,
                    db,
                    transaction);
                itemCommand.Parameters.AddWithValue("@Id", itemId);
                itemCommand.Parameters.AddWithValue("@VendaId", saleId);
                itemCommand.Parameters.AddWithValue("@ProductCode", item.ProductCode);
                itemCommand.Parameters.AddWithValue("@ProductName", item.ProductName);
                itemCommand.Parameters.AddWithValue("@Quantity", item.Quantity);
                itemCommand.Parameters.AddWithValue("@UnitPrice", item.UnitPrice);
                itemCommand.Parameters.AddWithValue("@ItemTotal", itemTotal);
                await itemCommand.ExecuteNonQueryAsync();

                rows.Add(new VendaHistoricoAD
                {
                    SaleNumber = saleNumber,
                    CustomerName = customerName,
                    CustomerCpf = customerCpf,
                    PaymentType = paymentType,
                    TotalAmount = HorusMoneyFormat.Format(totalAmount),
                    OperatorName = operatorName,
                    ProductCode = item.ProductCode,
                    ProductName = item.ProductName,
                    Quantity = item.Quantity,
                    UnitPrice = HorusMoneyFormat.Format(item.UnitPrice),
                    ItemTotal = HorusMoneyFormat.Format(itemTotal),
                    SaleDate = now.LocalDateTime.ToString("dd/MM/yyyy HH:mm:ss")
                });
            }

            await transaction.CommitAsync();
            return new VendaRegistroResultadoAD { SaleNumber = saleNumber, VendaId = saleId, Rows = rows };
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    private static async Task<string> NextSaleNumberAsync(SqlConnection db, SqlTransaction transaction)
    {
        await using var command = new SqlCommand(
            "SELECT CONVERT(NVARCHAR(30), ISNULL(MAX(TRY_CONVERT(INT, SaleNumber)), 15039) + 1) FROM Vendas WITH (UPDLOCK, HOLDLOCK);",
            db,
            transaction);
        return Convert.ToString(await command.ExecuteScalarAsync()) ?? "15040";
    }

    private static async Task<List<VendaItemRecord>> BaixarEstoqueAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        IEnumerable<VendaItemRequest> items)
    {
        var groupedItems = items
            .GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(group => new VendaItemRecord
            {
                ProductCode = group.Key,
                ProductName = group.First().ProductName.Trim(),
                Quantity = group.Sum(item => item.Quantity)
            })
            .ToList();

        foreach (var item in groupedItems)
        {
            if (item.Quantity <= 0)
            {
                throw new InvalidOperationException("Quantidade da venda deve ser maior que zero.");
            }

            await using var select = new SqlCommand(
                """
                SELECT Id, ProductName, ProductQnt, ProductUnitPrice, ProductSalePrice
                FROM Produtos WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;
                """,
                db,
                transaction);
            select.Parameters.AddWithValue("@ProductCode", item.ProductCode);
            select.Parameters.AddWithValue("@CompanyId", companyId);
            await using var reader = await select.ExecuteReaderAsync();
            if (!await reader.ReadAsync())
            {
                throw new InvalidOperationException($"Produto {item.ProductCode} não encontrado.");
            }

            var productId = ReadString(reader, "Id");
            item.ProductName = ReadString(reader, "ProductName");
            var currentStock = reader.GetDecimal(reader.GetOrdinal("ProductQnt"));
            var unitPrice = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
            var salePrice = reader.GetDecimal(reader.GetOrdinal("ProductSalePrice"));
            item.UnitPrice = salePrice > 0 ? salePrice : unitPrice;
            await reader.CloseAsync();

            if (currentStock < item.Quantity)
            {
                throw new InvalidOperationException(
                    $"Estoque insuficiente para {item.ProductName}. Disponível: {HorusMoneyFormat.FormatQuantity(currentStock)}.");
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

        return groupedItems;
    }

    private static VendaHistoricoAD Map(SqlDataReader reader) => new()
    {
        SaleNumber = ReadString(reader, "SaleNumber"),
        CustomerName = ReadString(reader, "CustomerName"),
        CustomerCpf = ReadString(reader, "CustomerCpf"),
        PaymentType = ReadString(reader, "PaymentType"),
        TotalAmount = HorusMoneyFormat.Format(reader.GetDecimal(reader.GetOrdinal("TotalAmount"))),
        OperatorName = ReadString(reader, "OperatorName"),
        ProductCode = ReadString(reader, "ProductCode"),
        ProductName = ReadString(reader, "ProductName"),
        Quantity = reader.GetDecimal(reader.GetOrdinal("Quantity")),
        UnitPrice = HorusMoneyFormat.Format(reader.GetDecimal(reader.GetOrdinal("UnitPrice"))),
        ItemTotal = HorusMoneyFormat.Format(reader.GetDecimal(reader.GetOrdinal("ItemTotal"))),
        SaleDate = reader.GetDateTimeOffset(reader.GetOrdinal("SaleDate")).LocalDateTime.ToString("dd/MM/yyyy HH:mm:ss")
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    private sealed class VendaItemRecord
    {
        public string ProductCode { get; set; } = string.Empty;
        public string ProductName { get; set; } = string.Empty;
        public decimal UnitPrice { get; set; }
        public decimal Quantity { get; set; }
    }
}
