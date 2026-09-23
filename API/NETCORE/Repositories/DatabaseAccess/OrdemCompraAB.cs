/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/OrdemCompraAB.cs
 * Objetivo: persistência de ordens de compra — criação, listagem, recebimento (com
 *           entrada de estoque via ProdutoAB.EntradaEstoqueAsync) e cancelamento.
 */
using HORUSPDV_API.Repositories.DataAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class OrdemCompraAB(Connection connection, ProdutoAB produtoAb)
{
    // -----------------------------------------------------------------------
    // Criar
    // -----------------------------------------------------------------------

    public async Task<OrdemCompraAD> CriarAsync(
        string companyId,
        string supplierId,
        string supplierName,
        string supplierCnpj,
        string createdBy,
        string createdByName,
        string? note,
        List<(string ProductCode, string ProductName, decimal Quantity, decimal UnitCost)> items)
    {
        if (items.Count == 0)
            throw new InvalidOperationException("Ordem de compra sem itens.");

        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            var orderNumber = await NextOrderNumberAsync(db, transaction, companyId);
            var id = $"oc-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
            var now = DateTimeOffset.Now;

            var ocItens = new List<OrdemCompraItemAD>();
            decimal totalEstimado = 0;

            foreach (var item in items)
            {
                var itemTotal = Math.Round(item.UnitCost * item.Quantity, 2, MidpointRounding.AwayFromZero);
                totalEstimado += itemTotal;
                ocItens.Add(new OrdemCompraItemAD
                {
                    ProductCode = item.ProductCode,
                    ProductName = item.ProductName,
                    Quantity = item.Quantity,
                    UnitCost = item.UnitCost,
                    ItemTotal = itemTotal,
                    QuantityReceived = 0
                });
            }

            await using (var insert = new SqlCommand(
                """
                INSERT INTO OrdemCompra
                    (Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj,
                     Status, CreatedBy, CreatedByName, CreatedAt, Note, TotalEstimado)
                VALUES
                    (@Id, @CompanyId, @OrderNumber, @SupplierId, @SupplierName, @SupplierCnpj,
                     0, @CreatedBy, @CreatedByName, @CreatedAt, @Note, @TotalEstimado);
                """,
                db, transaction))
            {
                insert.Parameters.AddWithValue("@Id", id);
                insert.Parameters.AddWithValue("@CompanyId", companyId);
                insert.Parameters.AddWithValue("@OrderNumber", orderNumber);
                insert.Parameters.AddWithValue("@SupplierId", string.IsNullOrWhiteSpace(supplierId) ? DBNull.Value : supplierId);
                insert.Parameters.AddWithValue("@SupplierName", supplierName);
                insert.Parameters.AddWithValue("@SupplierCnpj", string.IsNullOrWhiteSpace(supplierCnpj) ? DBNull.Value : supplierCnpj);
                insert.Parameters.AddWithValue("@CreatedBy", string.IsNullOrWhiteSpace(createdBy) ? DBNull.Value : createdBy);
                insert.Parameters.AddWithValue("@CreatedByName", createdByName);
                insert.Parameters.AddWithValue("@CreatedAt", now);
                insert.Parameters.AddWithValue("@Note", string.IsNullOrWhiteSpace(note) ? DBNull.Value : note);
                insert.Parameters.AddWithValue("@TotalEstimado", totalEstimado);
                await insert.ExecuteNonQueryAsync();
            }

            for (var i = 0; i < ocItens.Count; i++)
            {
                var item = ocItens[i];
                await using var cmd = new SqlCommand(
                    """
                    INSERT INTO OrdemCompraItens
                        (Id, OrdemCompraId, ProductCode, ProductName, Quantity, UnitCost, ItemTotal, QuantityReceived)
                    VALUES
                        (@Id, @OrdemCompraId, @ProductCode, @ProductName, @Quantity, @UnitCost, @ItemTotal, 0);
                    """,
                    db, transaction);
                cmd.Parameters.AddWithValue("@Id", $"{id}-item-{i + 1:000}");
                cmd.Parameters.AddWithValue("@OrdemCompraId", id);
                cmd.Parameters.AddWithValue("@ProductCode", item.ProductCode);
                cmd.Parameters.AddWithValue("@ProductName", item.ProductName);
                cmd.Parameters.AddWithValue("@Quantity", item.Quantity);
                cmd.Parameters.AddWithValue("@UnitCost", item.UnitCost);
                cmd.Parameters.AddWithValue("@ItemTotal", item.ItemTotal);
                await cmd.ExecuteNonQueryAsync();
            }

            await transaction.CommitAsync();

            return new OrdemCompraAD
            {
                Id = id,
                CompanyId = companyId,
                OrderNumber = orderNumber,
                SupplierId = supplierId,
                SupplierName = supplierName,
                SupplierCnpj = supplierCnpj,
                Status = OrdemCompraStatus.Pendente,
                CreatedBy = createdBy,
                CreatedByName = createdByName,
                CreatedAt = now,
                Note = note ?? string.Empty,
                TotalEstimado = totalEstimado,
                Itens = ocItens
            };
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    // -----------------------------------------------------------------------
    // Listar
    // -----------------------------------------------------------------------

    public async Task<List<OrdemCompraAD>> ListarAsync(string companyId, int? status = null)
    {
        var sql = """
            SELECT Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj,
                   Status, CreatedBy, CreatedByName, CreatedAt, ReceivedAt, CanceledAt, Note, TotalEstimado
            FROM OrdemCompra
            WHERE CompanyId = @CompanyId
            """;
        if (status.HasValue)
            sql += " AND Status = @Status";
        sql += " ORDER BY CreatedAt DESC;";

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        if (status.HasValue)
            command.Parameters.AddWithValue("@Status", status.Value);

        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<OrdemCompraAD>();
        while (await reader.ReadAsync())
            rows.Add(MapCabecalho(reader));
        return rows;
    }

    // -----------------------------------------------------------------------
    // Obter por número
    // -----------------------------------------------------------------------

    public async Task<OrdemCompraAD?> ObterPorNumeroAsync(string companyId, string orderNumber)
    {
        const string sql = """
            SELECT Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj,
                   Status, CreatedBy, CreatedByName, CreatedAt, ReceivedAt, CanceledAt, Note, TotalEstimado
            FROM OrdemCompra
            WHERE CompanyId = @CompanyId AND OrderNumber = @OrderNumber;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@OrderNumber", orderNumber);
        await using var reader = await command.ExecuteReaderAsync();
        if (!await reader.ReadAsync()) return null;

        var oc = MapCabecalho(reader);
        await reader.CloseAsync();
        oc.Itens = await ObterItensAsync(db, oc.Id);
        return oc;
    }

    // -----------------------------------------------------------------------
    // Receber (entrada de estoque)
    // -----------------------------------------------------------------------

    public async Task<OrdemCompraStatus> ReceberAsync(
        string companyId,
        string orderNumber,
        List<(string ProductCode, decimal QuantityReceived)> itensRecebidos)
    {
        await using var db = await connection.OpenConnectionAsync();

        // Busca cabeçalho
        OrdemCompraAD? oc;
        await using (var selOc = new SqlCommand(
            """
            SELECT Id, CompanyId, OrderNumber, SupplierId, SupplierName, SupplierCnpj,
                   Status, CreatedBy, CreatedByName, CreatedAt, ReceivedAt, CanceledAt, Note, TotalEstimado
            FROM OrdemCompra
            WHERE CompanyId = @CompanyId AND OrderNumber = @OrderNumber AND Status IN (0, 3);
            """, db))
        {
            selOc.Parameters.AddWithValue("@CompanyId", companyId);
            selOc.Parameters.AddWithValue("@OrderNumber", orderNumber);
            await using var rdr = await selOc.ExecuteReaderAsync();
            oc = await rdr.ReadAsync() ? MapCabecalho(rdr) : null;
        }

        if (oc is null)
            throw new InvalidOperationException("Ordem de compra não encontrada ou já finalizada/cancelada.");

        var itensOc = await ObterItensAsync(db, oc.Id);
        var lookup = itensRecebidos.ToDictionary(i => i.ProductCode, i => i.QuantityReceived, StringComparer.OrdinalIgnoreCase);

        // Processa cada item recebido — entrada de estoque
        foreach (var item in itensOc)
        {
            if (!lookup.TryGetValue(item.ProductCode, out var qtyRecv) || qtyRecv <= 0) continue;

            // Busca o produto pelo código para obter o Id
            var produto = await produtoAb.ObterPorCodigoAsync(companyId, item.ProductCode);
            if (produto is null) continue;

            // Entrada de estoque (atualiza custo e recalcula preço de venda se margem configurada)
            await produtoAb.EntradaEstoqueAsync(companyId, produto.Id, qtyRecv, item.UnitCost);

            // Atualiza QuantityReceived no item da OC
            await using var updItem = new SqlCommand(
                """
                UPDATE OrdemCompraItens
                   SET QuantityReceived = QuantityReceived + @QtyRecv
                 WHERE OrdemCompraId = @OcId AND ProductCode = @ProductCode;
                """, db);
            updItem.Parameters.AddWithValue("@QtyRecv", qtyRecv);
            updItem.Parameters.AddWithValue("@OcId", oc.Id);
            updItem.Parameters.AddWithValue("@ProductCode", item.ProductCode);
            await updItem.ExecuteNonQueryAsync();
        }

        // Verifica se todos os itens foram totalmente recebidos
        var itensAtualizados = await ObterItensAsync(db, oc.Id);
        var todosRecebidos = itensAtualizados.TrueForAll(i => i.QuantityReceived >= i.Quantity);
        var algumRecebido = itensAtualizados.Exists(i => i.QuantityReceived > 0);

        var novoStatus = todosRecebidos
            ? OrdemCompraStatus.Recebido
            : algumRecebido
                ? OrdemCompraStatus.RecebidoParcial
                : OrdemCompraStatus.Pendente;

        await using var updOc = new SqlCommand(
            """
            UPDATE OrdemCompra
               SET Status = @Status,
                   ReceivedAt = CASE WHEN @Status = 1 THEN SYSDATETIMEOFFSET() ELSE ReceivedAt END
             WHERE Id = @Id;
            """, db);
        updOc.Parameters.AddWithValue("@Status", (int)novoStatus);
        updOc.Parameters.AddWithValue("@Id", oc.Id);
        await updOc.ExecuteNonQueryAsync();

        return novoStatus;
    }

    // -----------------------------------------------------------------------
    // Cancelar
    // -----------------------------------------------------------------------

    public async Task<bool> CancelarAsync(string companyId, string orderNumber)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            UPDATE OrdemCompra
               SET Status = 2,
                   CanceledAt = SYSDATETIMEOFFSET()
             WHERE CompanyId = @CompanyId AND OrderNumber = @OrderNumber AND Status = 0;
            """, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@OrderNumber", orderNumber);
        return await command.ExecuteNonQueryAsync() > 0;
    }

    // -----------------------------------------------------------------------
    // Sugestões de reposição
    // -----------------------------------------------------------------------

    public async Task<List<SugestaoReposicaoAD>> SugerirReposicaoAsync(string companyId)
    {
        const string sql = """
            SELECT p.ProductCode, p.ProductName,
                   CAST(REPLACE(REPLACE(p.ProductQnt, '.', ''), ',', '.') AS DECIMAL(15,4)) AS CurrentStock,
                   p.EstoqueMinimo AS MinStock,
                   ISNULL(p.EstoqueMaximo, 0) AS MaxStock,
                   p.SupplierId,
                   ISNULL(f.FantasyName, ISNULL(f.CompanyName, p.ProductSupplier)) AS SupplierName,
                   ISNULL(CAST(REPLACE(REPLACE(p.ProductUnitPrice, '.', ''), ',', '.') AS DECIMAL(15,4)), 0) AS UnitCost
            FROM Produtos p
            LEFT JOIN Fornecedores f ON f.Id = p.SupplierId AND f.CompanyId = p.CompanyId
            WHERE p.CompanyId = @CompanyId
              AND p.EstoqueMinimo > 0
              AND CAST(REPLACE(REPLACE(p.ProductQnt, '.', ''), ',', '.') AS DECIMAL(15,4)) < p.EstoqueMinimo
            ORDER BY SupplierName, p.ProductName;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<SugestaoReposicaoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(new SugestaoReposicaoAD
            {
                ProductCode = ReadString(reader, "ProductCode"),
                ProductName = ReadString(reader, "ProductName"),
                CurrentStock = reader.GetDecimal(reader.GetOrdinal("CurrentStock")),
                MinStock = reader.GetDecimal(reader.GetOrdinal("MinStock")),
                MaxStock = reader.GetDecimal(reader.GetOrdinal("MaxStock")),
                SupplierId = ReadNullableString(reader, "SupplierId"),
                SupplierName = ReadString(reader, "SupplierName"),
                UnitCost = reader.GetDecimal(reader.GetOrdinal("UnitCost"))
            });
        }

        return rows;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static async Task<string> NextOrderNumberAsync(SqlConnection db, SqlTransaction transaction, string companyId)
    {
        await using var command = new SqlCommand(
            """
            SELECT CONVERT(NVARCHAR(20), ISNULL(MAX(TRY_CONVERT(INT, OrderNumber)), 0) + 1)
              FROM OrdemCompra WITH (UPDLOCK, HOLDLOCK)
             WHERE CompanyId = @CompanyId;
            """,
            db, transaction);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        return Convert.ToString(await command.ExecuteScalarAsync()) ?? "1";
    }

    private static async Task<List<OrdemCompraItemAD>> ObterItensAsync(SqlConnection db, string ordemCompraId)
    {
        const string sql = """
            SELECT ProductCode, ProductName, Quantity, UnitCost, ItemTotal, QuantityReceived
            FROM OrdemCompraItens
            WHERE OrdemCompraId = @OrdemCompraId
            ORDER BY Id;
            """;

        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@OrdemCompraId", ordemCompraId);
        await using var reader = await command.ExecuteReaderAsync();
        var itens = new List<OrdemCompraItemAD>();
        while (await reader.ReadAsync())
        {
            itens.Add(new OrdemCompraItemAD
            {
                ProductCode = ReadString(reader, "ProductCode"),
                ProductName = ReadString(reader, "ProductName"),
                Quantity = reader.GetDecimal(reader.GetOrdinal("Quantity")),
                UnitCost = reader.GetDecimal(reader.GetOrdinal("UnitCost")),
                ItemTotal = reader.GetDecimal(reader.GetOrdinal("ItemTotal")),
                QuantityReceived = reader.GetDecimal(reader.GetOrdinal("QuantityReceived"))
            });
        }

        return itens;
    }

    private static OrdemCompraAD MapCabecalho(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        CompanyId = ReadString(reader, "CompanyId"),
        OrderNumber = ReadString(reader, "OrderNumber"),
        SupplierId = ReadNullableString(reader, "SupplierId"),
        SupplierName = ReadString(reader, "SupplierName"),
        SupplierCnpj = ReadString(reader, "SupplierCnpj"),
        Status = (OrdemCompraStatus)Convert.ToInt32(reader.GetValue(reader.GetOrdinal("Status"))),
        CreatedBy = ReadNullableString(reader, "CreatedBy"),
        CreatedByName = ReadString(reader, "CreatedByName"),
        CreatedAt = reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt")),
        ReceivedAt = reader.IsDBNull(reader.GetOrdinal("ReceivedAt")) ? null : reader.GetDateTimeOffset(reader.GetOrdinal("ReceivedAt")),
        CanceledAt = reader.IsDBNull(reader.GetOrdinal("CanceledAt")) ? null : reader.GetDateTimeOffset(reader.GetOrdinal("CanceledAt")),
        Note = ReadString(reader, "Note"),
        TotalEstimado = reader.GetDecimal(reader.GetOrdinal("TotalEstimado"))
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
