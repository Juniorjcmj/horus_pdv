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

public class HistoricoVendasAB(Connection connection, FiadoAB fiadoAb, AuditLogAB auditLogAb)
{
    public async Task<List<VendaHistoricoAD>> ListarAsync(string companyId, string? saleNumber = null)
    {
        const string sql = """
            SELECT v.SaleNumber, v.CustomerName, v.CustomerCpf, v.PaymentType,
                   v.TotalAmount, v.OperatorName, v.SaleDate,
                   i.ProductCode, i.ProductName, i.Quantity, i.UnitPrice, i.ItemTotal,
                   i.Desconto, i.PromocaoId
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
                   i.ProductCode, i.ProductName, i.Quantity, i.UnitPrice, i.ItemTotal,
                   i.Desconto, i.PromocaoId
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
            var customerName = string.IsNullOrWhiteSpace(request.CustomerName) ? "Consumidor" : request.CustomerName.Trim();
            var customerCpf = string.IsNullOrWhiteSpace(request.CustomerCpf) ? "-" : request.CustomerCpf.Trim();
            var paymentType = string.IsNullOrWhiteSpace(request.PaymentType) ? "-" : request.PaymentType.Trim();
            var totalAmount = HorusMoneyFormat.ParseDecimal(request.TotalAmount);
            var operatorName = string.IsNullOrWhiteSpace(request.OperatorName) ? "Operador" : request.OperatorName.Trim();

            var payments = request.Payments ?? [];
            if (payments.Count > 0)
            {
                var sumPayments = payments.Sum(p => Math.Round(p.Amount, 2, MidpointRounding.AwayFromZero));
                if (Math.Abs(sumPayments - totalAmount) > 0.01m)
                {
                    throw new InvalidOperationException(
                        $"A soma dos pagamentos (R$ {sumPayments:N2}) não confere com o total da venda (R$ {totalAmount:N2}).");
                }
                paymentType = payments.Count == 1 ? payments[0].PaymentType : "Múltiplo";
            }

            var fiadoPayment = payments.FirstOrDefault(p => string.Equals(p.PaymentType?.Trim(), "fiado", StringComparison.OrdinalIgnoreCase))
                ?? (string.Equals(paymentType, "fiado", StringComparison.OrdinalIgnoreCase) ? new VendaPagamentoRequest { PaymentType = "fiado", Amount = totalAmount } : null);

            string? fiadoClienteId = null;
            if (fiadoPayment is not null && fiadoPayment.Amount > 0)
            {
                if (string.IsNullOrWhiteSpace(customerCpf) || customerCpf == "-")
                {
                    throw new InvalidOperationException("Para compras a prazo / fiado, é obrigatório vincular um cliente cadastrado.");
                }

                var digits = new string(customerCpf.Where(char.IsDigit).ToArray());
                const string findClienteSql = """
                    SELECT Id, LimiteCredito, SaldoDevedor, CustomerName
                    FROM Clientes WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId
                      AND REPLACE(REPLACE(REPLACE(Document, '.', ''), '-', ''), '/', '') = @Document;
                    """;

                await using var findCmd = new SqlCommand(findClienteSql, db, transaction);
                findCmd.Parameters.AddWithValue("@CompanyId", companyId);
                findCmd.Parameters.AddWithValue("@Document", digits);
                decimal limiteCredito = 0;
                decimal saldoDevedor = 0;

                await using (var r = await findCmd.ExecuteReaderAsync())
                {
                    if (!await r.ReadAsync())
                    {
                        throw new InvalidOperationException($"Cliente com CPF/CNPJ {customerCpf} não encontrado no cadastro.");
                    }

                    fiadoClienteId = r.GetString(r.GetOrdinal("Id"));
                    limiteCredito = r.GetDecimal(r.GetOrdinal("LimiteCredito"));
                    saldoDevedor = r.GetDecimal(r.GetOrdinal("SaldoDevedor"));
                    customerName = r.GetString(r.GetOrdinal("CustomerName"));
                }

                if (limiteCredito > 0 && (saldoDevedor + fiadoPayment.Amount) > limiteCredito)
                {
                    var disponivel = Math.Max(0, limiteCredito - saldoDevedor);
                    throw new InvalidOperationException(
                        $"Limite de crédito excedido para {customerName}. Limite: R$ {limiteCredito:N2}, Saldo devedor: R$ {saldoDevedor:N2}, Disponível: R$ {disponivel:N2}, Valor fiado: R$ {fiadoPayment.Amount:N2}.");
                }
            }

            // A venda e a baixa de estoque compartilham a mesma transação para evitar histórico sem estoque atualizado.
            var saleItems = await BaixarEstoqueAsync(db, transaction, companyId, request.Items);

            var result = await InserirVendaAsync(
                db, transaction, companyId, customerName, customerCpf, paymentType, totalAmount, operatorName, saleItems, payments);

            FiadoMovimentoAD? fiadoMov = null;
            if (fiadoPayment is not null && fiadoPayment.Amount > 0 && fiadoClienteId is not null)
            {
                fiadoMov = await fiadoAb.RegistrarDebitoAsync(db, transaction, companyId, fiadoClienteId, fiadoPayment.Amount, result.VendaId, operatorName);
            }

            await transaction.CommitAsync();

            if (fiadoMov is not null)
            {
                _ = auditLogAb.RegistrarAsync(
                    companyId, "", operatorName,
                    AuditEventTypes.FiadoDebito,
                    $"Venda fiado {HorusMoneyFormat.Format(fiadoMov.Valor)} para {fiadoMov.ClienteNome}. Saldo devedor: {HorusMoneyFormat.Format(fiadoMov.SaldoAtual)}.",
                    entityType: "Cliente",
                    entityId: fiadoMov.ClienteId);
            }

            return result;
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    /// <summary>
    /// Finaliza no caixa um pedido montado antes pelo vendedor (ver PedidoAB) — cobra
    /// exatamente o preço "congelado" no pedido, não o preço corrente do produto. O estoque só
    /// é checado e baixado agora, na finalização (o pedido em si não reserva nada).
    /// </summary>
    public async Task<VendaRegistroResultadoAD> RegistrarComPrecosFixosAsync(
        string companyId,
        string customerName,
        string customerCpf,
        string paymentType,
        string operatorName,
        List<PedidoItemAD> pedidoItens,
        List<VendaPagamentoRequest>? payments = null)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            var saleItems = await BaixarEstoqueComPrecoFixoAsync(db, transaction, companyId, pedidoItens);
            var totalAmount = saleItems.Sum(item => Math.Round(item.UnitPrice * item.Quantity, 2, MidpointRounding.AwayFromZero));

            var pagamentos = payments ?? [];
            if (pagamentos.Count > 0)
            {
                var sumPayments = pagamentos.Sum(p => Math.Round(p.Amount, 2, MidpointRounding.AwayFromZero));
                if (Math.Abs(sumPayments - totalAmount) > 0.01m)
                {
                    throw new InvalidOperationException(
                        $"A soma dos pagamentos (R$ {sumPayments:N2}) não confere com o total da venda (R$ {totalAmount:N2}).");
                }
                paymentType = pagamentos.Count == 1 ? pagamentos[0].PaymentType : "Múltiplo";
            }

            var fiadoPaymentFixo = pagamentos.FirstOrDefault(p => string.Equals(p.PaymentType?.Trim(), "fiado", StringComparison.OrdinalIgnoreCase))
                ?? (string.Equals(paymentType, "fiado", StringComparison.OrdinalIgnoreCase) ? new VendaPagamentoRequest { PaymentType = "fiado", Amount = totalAmount } : null);

            string? fiadoClienteIdFixo = null;
            if (fiadoPaymentFixo is not null && fiadoPaymentFixo.Amount > 0)
            {
                if (string.IsNullOrWhiteSpace(customerCpf) || customerCpf == "-")
                {
                    throw new InvalidOperationException("Para compras a prazo / fiado, é obrigatório vincular um cliente cadastrado.");
                }

                var digits = new string(customerCpf.Where(char.IsDigit).ToArray());
                const string findClienteSql = """
                    SELECT Id, LimiteCredito, SaldoDevedor, CustomerName
                    FROM Clientes WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId
                      AND REPLACE(REPLACE(REPLACE(Document, '.', ''), '-', ''), '/', '') = @Document;
                    """;

                await using var findCmd = new SqlCommand(findClienteSql, db, transaction);
                findCmd.Parameters.AddWithValue("@CompanyId", companyId);
                findCmd.Parameters.AddWithValue("@Document", digits);
                decimal limiteCredito = 0;
                decimal saldoDevedor = 0;

                await using (var r = await findCmd.ExecuteReaderAsync())
                {
                    if (!await r.ReadAsync())
                    {
                        throw new InvalidOperationException($"Cliente com CPF/CNPJ {customerCpf} não encontrado no cadastro.");
                    }

                    fiadoClienteIdFixo = r.GetString(r.GetOrdinal("Id"));
                    limiteCredito = r.GetDecimal(r.GetOrdinal("LimiteCredito"));
                    saldoDevedor = r.GetDecimal(r.GetOrdinal("SaldoDevedor"));
                    customerName = r.GetString(r.GetOrdinal("CustomerName"));
                }

                if (limiteCredito > 0 && (saldoDevedor + fiadoPaymentFixo.Amount) > limiteCredito)
                {
                    var disponivel = Math.Max(0, limiteCredito - saldoDevedor);
                    throw new InvalidOperationException(
                        $"Limite de crédito excedido para {customerName}. Limite: R$ {limiteCredito:N2}, Saldo devedor: R$ {saldoDevedor:N2}, Disponível: R$ {disponivel:N2}, Valor fiado: R$ {fiadoPaymentFixo.Amount:N2}.");
                }
            }

            var result = await InserirVendaAsync(
                db, transaction, companyId, customerName, customerCpf, paymentType, totalAmount, operatorName, saleItems, pagamentos);

            FiadoMovimentoAD? fiadoMovFixo = null;
            if (fiadoPaymentFixo is not null && fiadoPaymentFixo.Amount > 0 && fiadoClienteIdFixo is not null)
            {
                fiadoMovFixo = await fiadoAb.RegistrarDebitoAsync(db, transaction, companyId, fiadoClienteIdFixo, fiadoPaymentFixo.Amount, result.VendaId, operatorName);
            }

            await transaction.CommitAsync();

            if (fiadoMovFixo is not null)
            {
                _ = auditLogAb.RegistrarAsync(
                    companyId, "", operatorName,
                    AuditEventTypes.FiadoDebito,
                    $"Venda fiado {HorusMoneyFormat.Format(fiadoMovFixo.Valor)} para {fiadoMovFixo.ClienteNome}. Saldo devedor: {HorusMoneyFormat.Format(fiadoMovFixo.SaldoAtual)}.",
                    entityType: "Cliente",
                    entityId: fiadoMovFixo.ClienteId);
            }

            return result;
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    private static async Task<VendaRegistroResultadoAD> InserirVendaAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        string customerName,
        string customerCpf,
        string paymentType,
        decimal totalAmount,
        string operatorName,
        List<VendaItemRecord> saleItems,
        List<VendaPagamentoRequest>? payments)
    {
        var saleNumber = await NextSaleNumberAsync(db, transaction, companyId);
        var now = HorusDateTime.Now;
        var saleId = $"sale-{companyId}-{saleNumber}";

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

        var pagamentosAD = new List<VendaPagamentoAD>();
        if (payments is not null && payments.Count > 0)
        {
            for (var pIndex = 0; pIndex < payments.Count; pIndex++)
            {
                var p = payments[pIndex];
                var pagId = $"{saleId}-pag-{pIndex + 1:000}";
                var tipo = string.IsNullOrWhiteSpace(p.PaymentType) ? "dinheiro" : p.PaymentType.Trim();
                var cashGiven = p.CashGiven > 0 ? p.CashGiven : p.Amount;
                await using var pagCommand = new SqlCommand(
                    """
                    INSERT INTO VendaPagamentos
                        (Id, CompanyId, VendaId, PaymentType, Amount, CashGiven, ChangeAmount, CreatedAt)
                    VALUES
                        (@Id, @CompanyId, @VendaId, @PaymentType, @Amount, @CashGiven, @ChangeAmount, @CreatedAt);
                    """,
                    db,
                    transaction);
                pagCommand.Parameters.AddWithValue("@Id", pagId);
                pagCommand.Parameters.AddWithValue("@CompanyId", companyId);
                pagCommand.Parameters.AddWithValue("@VendaId", saleId);
                pagCommand.Parameters.AddWithValue("@PaymentType", tipo);
                pagCommand.Parameters.AddWithValue("@Amount", p.Amount);
                pagCommand.Parameters.AddWithValue("@CashGiven", cashGiven);
                pagCommand.Parameters.AddWithValue("@ChangeAmount", p.ChangeAmount);
                pagCommand.Parameters.AddWithValue("@CreatedAt", now);
                await pagCommand.ExecuteNonQueryAsync();

                pagamentosAD.Add(new VendaPagamentoAD
                {
                    Id = pagId,
                    CompanyId = companyId,
                    VendaId = saleId,
                    PaymentType = tipo,
                    Amount = p.Amount,
                    CashGiven = cashGiven,
                    ChangeAmount = p.ChangeAmount,
                    CreatedAt = now
                });
            }
        }
        else
        {
            var pagId = $"{saleId}-pag-001";
            var tipo = string.IsNullOrWhiteSpace(paymentType) || paymentType == "-" ? "dinheiro" : paymentType;
            await using var pagCommand = new SqlCommand(
                """
                INSERT INTO VendaPagamentos
                    (Id, CompanyId, VendaId, PaymentType, Amount, CashGiven, ChangeAmount, CreatedAt)
                VALUES
                    (@Id, @CompanyId, @VendaId, @PaymentType, @Amount, @CashGiven, @ChangeAmount, @CreatedAt);
                """,
                db,
                transaction);
            pagCommand.Parameters.AddWithValue("@Id", pagId);
            pagCommand.Parameters.AddWithValue("@CompanyId", companyId);
            pagCommand.Parameters.AddWithValue("@VendaId", saleId);
            pagCommand.Parameters.AddWithValue("@PaymentType", tipo);
            pagCommand.Parameters.AddWithValue("@Amount", totalAmount);
            pagCommand.Parameters.AddWithValue("@CashGiven", totalAmount);
            pagCommand.Parameters.AddWithValue("@ChangeAmount", 0m);
            pagCommand.Parameters.AddWithValue("@CreatedAt", now);
            await pagCommand.ExecuteNonQueryAsync();

            pagamentosAD.Add(new VendaPagamentoAD
            {
                Id = pagId,
                CompanyId = companyId,
                VendaId = saleId,
                PaymentType = tipo,
                Amount = totalAmount,
                CashGiven = totalAmount,
                ChangeAmount = 0m,
                CreatedAt = now
            });
        }

        var rows = new List<VendaHistoricoAD>();
        for (var index = 0; index < saleItems.Count; index += 1)
        {
            var item = saleItems[index];
            var itemId = $"{saleId}-item-{index + 1:000}";
            var itemTotal = Math.Round(item.UnitPrice * item.Quantity - item.Desconto, 2, MidpointRounding.AwayFromZero);
            if (itemTotal < 0) itemTotal = 0m;
            await using var itemCommand = new SqlCommand(
                """
                INSERT INTO VendaItens
                    (Id, VendaId, ProductCode, ProductName, Quantity, UnitPrice, Desconto, ItemTotal, PromocaoId)
                VALUES
                    (@Id, @VendaId, @ProductCode, @ProductName, @Quantity, @UnitPrice, @Desconto, @ItemTotal, @PromocaoId);
                """,
                db,
                transaction);
            itemCommand.Parameters.AddWithValue("@Id", itemId);
            itemCommand.Parameters.AddWithValue("@VendaId", saleId);
            itemCommand.Parameters.AddWithValue("@ProductCode", item.ProductCode);
            itemCommand.Parameters.AddWithValue("@ProductName", item.ProductName);
            itemCommand.Parameters.AddWithValue("@Quantity", item.Quantity);
            itemCommand.Parameters.AddWithValue("@UnitPrice", item.UnitPrice);
            itemCommand.Parameters.AddWithValue("@Desconto", item.Desconto);
            itemCommand.Parameters.AddWithValue("@ItemTotal", itemTotal);
            itemCommand.Parameters.AddWithValue("@PromocaoId", string.IsNullOrWhiteSpace(item.PromocaoId) ? DBNull.Value : item.PromocaoId);
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
                Desconto = item.Desconto,
                PromocaoId = item.PromocaoId,
                ItemTotal = HorusMoneyFormat.Format(itemTotal),
                SaleDate = HorusDateTime.Format(now)
            });
        }

        return new VendaRegistroResultadoAD { SaleNumber = saleNumber, VendaId = saleId, Rows = rows, Payments = pagamentosAD };
    }

    private static async Task<string> NextSaleNumberAsync(SqlConnection db, SqlTransaction transaction, string companyId)
    {
        var defaultBase = companyId == "empresa-principal" ? 15039 : 0;
        await using var command = new SqlCommand(
            """
            SELECT CONVERT(NVARCHAR(30), ISNULL(MAX(TRY_CONVERT(INT, SaleNumber)), @DefaultBase) + 1)
            FROM Vendas WITH (UPDLOCK, HOLDLOCK)
            WHERE CompanyId = @CompanyId;
            """,
            db,
            transaction);
        command.Parameters.AddWithValue("@DefaultBase", defaultBase);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        var next = Convert.ToString(await command.ExecuteScalarAsync());
        return string.IsNullOrWhiteSpace(next) ? (defaultBase + 1).ToString() : next;
    }

    private static async Task<List<VendaItemRecord>> BaixarEstoqueAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        IEnumerable<VendaItemRequest> items)
    {
        var itemList = items.ToList();
        if (itemList.Count == 0)
        {
            throw new InvalidOperationException("Venda deve conter ao menos um item.");
        }

        var stockByCode = itemList
            .GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, g => g.Sum(x => x.Quantity), StringComparer.OrdinalIgnoreCase);

        var pricesByCode = new Dictionary<string, (string Id, string ProductName, decimal SalePrice, decimal CostPrice)>(StringComparer.OrdinalIgnoreCase);

        foreach (var (code, totalQty) in stockByCode)
        {
            if (totalQty <= 0)
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
            select.Parameters.AddWithValue("@ProductCode", code);
            select.Parameters.AddWithValue("@CompanyId", companyId);
            await using var reader = await select.ExecuteReaderAsync();
            if (!await reader.ReadAsync())
            {
                throw new InvalidOperationException($"Produto {code} não encontrado.");
            }

            var productId = ReadString(reader, "Id");
            var productName = ReadString(reader, "ProductName");
            var currentStock = reader.GetDecimal(reader.GetOrdinal("ProductQnt"));
            var unitCost = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
            var salePrice = reader.GetDecimal(reader.GetOrdinal("ProductSalePrice"));
            var effectivePrice = salePrice > 0 ? salePrice : unitCost;
            await reader.CloseAsync();

            if (currentStock < totalQty)
            {
                throw new InvalidOperationException(
                    $"Estoque insuficiente para {productName}. Disponível: {HorusMoneyFormat.FormatQuantity(currentStock)}.");
            }

            var nextStock = currentStock - totalQty;
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
            update.Parameters.AddWithValue("@TotalPriceOnProduct", unitCost * nextStock);
            update.Parameters.AddWithValue("@Id", productId);
            await update.ExecuteNonQueryAsync();

            pricesByCode[code] = (productId, productName, effectivePrice, unitCost);
        }

        var saleItems = new List<VendaItemRecord>();
        foreach (var req in itemList)
        {
            var code = req.ProductCode.Trim();
            var info = pricesByCode[code];
            var unitPrice = req.UnitPrice > 0 ? req.UnitPrice : info.SalePrice;
            saleItems.Add(new VendaItemRecord
            {
                ProductCode = code,
                ProductName = string.IsNullOrWhiteSpace(req.ProductName) ? info.ProductName : req.ProductName.Trim(),
                UnitPrice = unitPrice,
                Quantity = req.Quantity,
                Desconto = req.Desconto,
                PromocaoId = req.PromocaoId
            });
        }

        return saleItems;
    }

    /// <summary>
    /// Mesma checagem/baixa de estoque de <see cref="BaixarEstoqueAsync"/>, mas usa o preço já
    /// congelado no pedido em vez de reconsultar ProductSalePrice/ProductUnitPrice — é assim
    /// que o caixa cobra exatamente o valor combinado com o vendedor.
    /// </summary>
    private static async Task<List<VendaItemRecord>> BaixarEstoqueComPrecoFixoAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        IEnumerable<PedidoItemAD> pedidoItens)
    {
        var groupedItems = pedidoItens
            .GroupBy(item => item.ProductCode.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(group => new VendaItemRecord
            {
                ProductCode = group.Key,
                ProductName = group.First().ProductName.Trim(),
                UnitPrice = group.First().UnitPrice,
                Quantity = group.Sum(item => item.Quantity)
            })
            .ToList();

        foreach (var item in groupedItems)
        {
            if (item.Quantity <= 0)
            {
                throw new InvalidOperationException("Quantidade do pedido deve ser maior que zero.");
            }

            await using var select = new SqlCommand(
                """
                SELECT Id, ProductName, ProductQnt, ProductUnitPrice
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
            var costUnitPrice = reader.GetDecimal(reader.GetOrdinal("ProductUnitPrice"));
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
            update.Parameters.AddWithValue("@TotalPriceOnProduct", costUnitPrice * nextStock);
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
        Desconto = reader.GetDecimal(reader.GetOrdinal("Desconto")),
        PromocaoId = reader.IsDBNull(reader.GetOrdinal("PromocaoId")) ? null : reader.GetString(reader.GetOrdinal("PromocaoId")),
        ItemTotal = HorusMoneyFormat.Format(reader.GetDecimal(reader.GetOrdinal("ItemTotal"))),
        SaleDate = HorusDateTime.Format(reader.GetDateTimeOffset(reader.GetOrdinal("SaleDate")))
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    public async Task<List<VendaPagamentoAD>> ObterPagamentosVendaAsync(string companyId, string vendaId)
    {
        const string sql = """
            SELECT Id, CompanyId, VendaId, PaymentType, Amount, CashGiven, ChangeAmount, CreatedAt
            FROM VendaPagamentos
            WHERE CompanyId = @CompanyId AND VendaId = @VendaId
            ORDER BY CreatedAt ASC, Id ASC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@VendaId", vendaId);
        await using var reader = await command.ExecuteReaderAsync();
        var list = new List<VendaPagamentoAD>();
        while (await reader.ReadAsync())
        {
            list.Add(new VendaPagamentoAD
            {
                Id = ReadString(reader, "Id"),
                CompanyId = ReadString(reader, "CompanyId"),
                VendaId = ReadString(reader, "VendaId"),
                PaymentType = ReadString(reader, "PaymentType"),
                Amount = reader.GetDecimal(reader.GetOrdinal("Amount")),
                CashGiven = reader.GetDecimal(reader.GetOrdinal("CashGiven")),
                ChangeAmount = reader.GetDecimal(reader.GetOrdinal("ChangeAmount")),
                CreatedAt = reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt"))
            });
        }

        return list;
    }

    private sealed class VendaItemRecord
    {
        public string ProductCode { get; set; } = string.Empty;
        public string ProductName { get; set; } = string.Empty;
        public decimal UnitPrice { get; set; }
        public decimal Quantity { get; set; }
        public decimal Desconto { get; set; }
        public string? PromocaoId { get; set; }
    }
}
