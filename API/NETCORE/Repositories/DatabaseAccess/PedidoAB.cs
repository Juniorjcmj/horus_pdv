/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/PedidoAB.cs
 * Objetivo: concentra comandos SQL e persistência de pedidos — o vendedor monta o pedido
 *           (preço congelado na hora), o caixa localiza pelo número e finaliza como venda.
 * Entradas esperadas: recebe conexão configurada e os itens já resolvidos pelo vendedor.
 *
 * O pedido NÃO reserva estoque — é só uma cotação com preço fixo. A baixa de estoque
 * acontece de fato na finalização (ver HistoricoVendasAB.RegistrarComPrecosFixosAsync,
 * orquestrado pelo PedidoController), então dois pedidos podem "disputar" a mesma unidade
 * até um deles ser pago primeiro — decisão consciente, mais simples que reserva com expiração.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class PedidoAB(Connection connection)
{
    public async Task<PedidoAD> CriarAsync(
        string companyId, string sellerId, string sellerName, string customerName, string customerCpf,
        IEnumerable<VendaItemRequest> items)
    {
        var itemList = items.ToList();
        if (itemList.Count == 0)
        {
            throw new InvalidOperationException("Pedido sem itens.");
        }

        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            var pedidoItens = new List<PedidoItemAD>();
            foreach (var group in itemList.GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase))
            {
                var quantity = group.Sum(item => item.Quantity);
                if (quantity <= 0)
                {
                    throw new InvalidOperationException("Quantidade do item deve ser maior que zero.");
                }

                await using var select = new SqlCommand(
                    """
                    SELECT ProductName, ProductUnitPrice, ProductSalePrice
                    FROM Produtos
                    WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;
                    """,
                    db,
                    transaction);
                select.Parameters.AddWithValue("@CompanyId", companyId);
                select.Parameters.AddWithValue("@ProductCode", group.Key);
                await using var reader = await select.ExecuteReaderAsync();
                if (!await reader.ReadAsync())
                {
                    throw new InvalidOperationException($"Produto {group.Key} não encontrado.");
                }

                var productName = ReadString(reader, "ProductName");
                var unitPrice = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
                var salePrice = reader.GetDecimal(reader.GetOrdinal("ProductSalePrice"));
                await reader.CloseAsync();

                var price = salePrice > 0 ? salePrice : unitPrice;
                pedidoItens.Add(new PedidoItemAD
                {
                    ProductCode = group.Key,
                    ProductName = productName,
                    Quantity = quantity,
                    UnitPrice = price,
                    ItemTotal = Math.Round(price * quantity, 2, MidpointRounding.AwayFromZero)
                });
            }

            var orderNumber = await NextOrderNumberAsync(db, transaction, companyId);
            var id = $"ped-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
            var now = DateTimeOffset.Now;

            await using (var insert = new SqlCommand(
                             """
                             INSERT INTO Pedidos
                                 (Id, CompanyId, OrderNumber, CustomerName, CustomerCpf, SellerId, SellerName, Status, CreatedAt)
                             VALUES
                                 (@Id, @CompanyId, @OrderNumber, @CustomerName, @CustomerCpf, @SellerId, @SellerName, 0, @CreatedAt);
                             """,
                             db,
                             transaction))
            {
                insert.Parameters.AddWithValue("@Id", id);
                insert.Parameters.AddWithValue("@CompanyId", companyId);
                insert.Parameters.AddWithValue("@OrderNumber", orderNumber);
                insert.Parameters.AddWithValue("@CustomerName", string.IsNullOrWhiteSpace(customerName) ? "Consumidor" : customerName.Trim());
                insert.Parameters.AddWithValue("@CustomerCpf", string.IsNullOrWhiteSpace(customerCpf) ? "-" : customerCpf.Trim());
                insert.Parameters.AddWithValue("@SellerId", sellerId);
                insert.Parameters.AddWithValue("@SellerName", sellerName);
                insert.Parameters.AddWithValue("@CreatedAt", now);
                await insert.ExecuteNonQueryAsync();
            }

            for (var index = 0; index < pedidoItens.Count; index += 1)
            {
                var item = pedidoItens[index];
                await using var itemCommand = new SqlCommand(
                    """
                    INSERT INTO PedidoItens (Id, PedidoId, ProductCode, ProductName, Quantity, UnitPrice, ItemTotal)
                    VALUES (@Id, @PedidoId, @ProductCode, @ProductName, @Quantity, @UnitPrice, @ItemTotal);
                    """,
                    db,
                    transaction);
                itemCommand.Parameters.AddWithValue("@Id", $"{id}-item-{index + 1:000}");
                itemCommand.Parameters.AddWithValue("@PedidoId", id);
                itemCommand.Parameters.AddWithValue("@ProductCode", item.ProductCode);
                itemCommand.Parameters.AddWithValue("@ProductName", item.ProductName);
                itemCommand.Parameters.AddWithValue("@Quantity", item.Quantity);
                itemCommand.Parameters.AddWithValue("@UnitPrice", item.UnitPrice);
                itemCommand.Parameters.AddWithValue("@ItemTotal", item.ItemTotal);
                await itemCommand.ExecuteNonQueryAsync();
            }

            await transaction.CommitAsync();

            return new PedidoAD
            {
                Id = id,
                CompanyId = companyId,
                OrderNumber = orderNumber,
                CustomerName = string.IsNullOrWhiteSpace(customerName) ? "Consumidor" : customerName.Trim(),
                CustomerCpf = string.IsNullOrWhiteSpace(customerCpf) ? "-" : customerCpf.Trim(),
                SellerId = sellerId,
                SellerName = sellerName,
                Status = PedidoStatus.Aberto,
                CreatedAt = now,
                Itens = pedidoItens
            };
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    public async Task<PedidoAD?> ObterPorNumeroAsync(string companyId, string orderNumber)
    {
        await using var db = await connection.OpenConnectionAsync();
        var pedido = await ObterCabecalhoAsync(db, null, companyId, orderNumber);
        if (pedido is null) return null;

        pedido.Itens = await ObterItensAsync(db, null, pedido.Id);
        return pedido;
    }

    public async Task<List<PedidoAD>> ListarAbertosAsync(string companyId)
    {
        const string sql = """
            SELECT Id, CompanyId, OrderNumber, CustomerName, CustomerCpf, SellerId, SellerName,
                   Status, VendaId, CreatedAt, FinalizedAt, Note
            FROM Pedidos
            WHERE CompanyId = @CompanyId AND Status = 0
            ORDER BY CreatedAt DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<PedidoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(MapCabecalho(reader));
        }

        return rows;
    }

    /// <summary>Marca o pedido como finalizado e vincula à venda gerada. Só afeta pedidos ainda abertos.</summary>
    public async Task<bool> MarcarFinalizadoAsync(string companyId, string pedidoId, string vendaId)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            UPDATE Pedidos
               SET Status = 1,
                   VendaId = @VendaId,
                   FinalizedAt = SYSDATETIMEOFFSET()
             WHERE Id = @Id AND CompanyId = @CompanyId AND Status = 0;
            """,
            db);
        command.Parameters.AddWithValue("@Id", pedidoId);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@VendaId", vendaId);
        return await command.ExecuteNonQueryAsync() > 0;
    }

    public async Task<bool> CancelarAsync(string companyId, string orderNumber)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            UPDATE Pedidos
               SET Status = 2,
                   CanceledAt = SYSDATETIMEOFFSET()
             WHERE CompanyId = @CompanyId AND OrderNumber = @OrderNumber AND Status = 0;
            """,
            db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@OrderNumber", orderNumber);
        return await command.ExecuteNonQueryAsync() > 0;
    }

    private static async Task<string> NextOrderNumberAsync(SqlConnection db, SqlTransaction transaction, string companyId)
    {
        await using var command = new SqlCommand(
            """
            SELECT CONVERT(NVARCHAR(20), ISNULL(MAX(TRY_CONVERT(INT, OrderNumber)), 0) + 1)
              FROM Pedidos WITH (UPDLOCK, HOLDLOCK)
             WHERE CompanyId = @CompanyId;
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        return Convert.ToString(await command.ExecuteScalarAsync()) ?? "1";
    }

    private static async Task<PedidoAD?> ObterCabecalhoAsync(
        SqlConnection db, SqlTransaction? transaction, string companyId, string orderNumber)
    {
        const string sql = """
            SELECT Id, CompanyId, OrderNumber, CustomerName, CustomerCpf, SellerId, SellerName,
                   Status, VendaId, CreatedAt, FinalizedAt, Note
            FROM Pedidos
            WHERE CompanyId = @CompanyId AND OrderNumber = @OrderNumber;
            """;

        await using var command = new SqlCommand(sql, db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@OrderNumber", orderNumber);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? MapCabecalho(reader) : null;
    }

    private static async Task<List<PedidoItemAD>> ObterItensAsync(SqlConnection db, SqlTransaction? transaction, string pedidoId)
    {
        const string sql = """
            SELECT ProductCode, ProductName, Quantity, UnitPrice, ItemTotal
            FROM PedidoItens
            WHERE PedidoId = @PedidoId
            ORDER BY Id;
            """;

        await using var command = new SqlCommand(sql, db, transaction);
        command.Parameters.AddWithValue("@PedidoId", pedidoId);
        await using var reader = await command.ExecuteReaderAsync();
        var itens = new List<PedidoItemAD>();
        while (await reader.ReadAsync())
        {
            itens.Add(new PedidoItemAD
            {
                ProductCode = ReadString(reader, "ProductCode"),
                ProductName = ReadString(reader, "ProductName"),
                Quantity = reader.GetDecimal(reader.GetOrdinal("Quantity")),
                UnitPrice = reader.GetDecimal(reader.GetOrdinal("UnitPrice")),
                ItemTotal = reader.GetDecimal(reader.GetOrdinal("ItemTotal"))
            });
        }

        return itens;
    }

    private static PedidoAD MapCabecalho(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        CompanyId = ReadString(reader, "CompanyId"),
        OrderNumber = ReadString(reader, "OrderNumber"),
        CustomerName = ReadString(reader, "CustomerName"),
        CustomerCpf = ReadString(reader, "CustomerCpf"),
        SellerId = ReadString(reader, "SellerId"),
        SellerName = ReadString(reader, "SellerName"),
        Status = (PedidoStatus)Convert.ToInt32(reader.GetValue(reader.GetOrdinal("Status"))),
        VendaId = ReadNullableString(reader, "VendaId"),
        CreatedAt = reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt")),
        FinalizedAt = reader.IsDBNull(reader.GetOrdinal("FinalizedAt")) ? null : reader.GetDateTimeOffset(reader.GetOrdinal("FinalizedAt")),
        Note = ReadString(reader, "Note")
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    private static string? ReadNullableString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);
    }
}
