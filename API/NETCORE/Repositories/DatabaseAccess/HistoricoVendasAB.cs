/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/HistoricoVendasAB.cs
 * Objetivo: concentra comandos SQL e persistência de histórico de vendas e recibos.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 *
 * TotalAmount/UnitPrice/ItemTotal são DECIMAL nativo no banco (migração 01) — a formatação
 * pt-BR do contrato HTTP acontece aqui, na borda, via HorusMoneyFormat.
 */
using System.Text.Json;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class HistoricoVendasAB(Connection connection, FiadoAB fiadoAb, AuditLogAB auditLogAb, LoteAB loteAb)
{
    private static readonly JsonSerializerOptions JsonOptions = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    /// <param name="caixaSessaoId">Quando informado, lista só as vendas vinculadas a esse turno de caixa.</param>
    public async Task<List<VendaHistoricoAD>> ListarAsync(
        string companyId,
        string? saleNumber = null,
        DateTimeOffset? desde = null,
        string? caixaSessaoId = null)
    {
        const string sql = """
            SELECT v.SaleNumber, ISNULL(v.Status, 'finalizada') AS Status,
                   v.CustomerName, v.CustomerCpf, v.PaymentType,
                   v.TotalAmount, v.OperatorName, v.SaleDate, v.ClientSaleId, v.OfflineReference,
                   v.CanceladoEm, v.CanceladoPorOperadorNome, v.CanceladoPorSupervisorNome, v.CanceladoJustificativa,
                   ISNULL(i.ProductCode, '') AS ProductCode,
                   ISNULL(i.ProductName, 'Item') AS ProductName,
                   ISNULL(i.Quantity, 1) AS Quantity,
                   ISNULL(i.UnitPrice, v.TotalAmount) AS UnitPrice,
                   ISNULL(i.ItemTotal, v.TotalAmount) AS ItemTotal,
                   ISNULL(i.Desconto, 0) AS Desconto,
                   i.PromocaoId,
                   d.Id AS FiscalDocId, d.Modelo AS FiscalModelo, d.NumeroNf AS FiscalNumeroNf,
                   d.Serie AS FiscalSerie, d.Status AS FiscalStatus, d.ChaveAcesso AS FiscalChaveAcesso,
                   pay.Breakdown AS PaymentBreakdown
            FROM Vendas v
            LEFT JOIN VendaItens i ON v.Id = i.VendaId
            OUTER APPLY (
                SELECT TOP 1 doc.Id, doc.Modelo, doc.NumeroNf, doc.Serie, doc.Status, doc.ChaveAcesso
                FROM DocumentosFiscais doc
                WHERE doc.CompanyId = v.CompanyId AND doc.VendaId = v.Id
                ORDER BY doc.CriadoEm DESC
            ) d
            OUTER APPLY (
                -- Formas de pagamento da venda ("dinheiro=10.00;pix=5.00"): venda com mais de uma forma
                -- precisa do valor de cada uma para os totais por forma de pagamento.
                SELECT STUFF((
                    SELECT ';' + LOWER(pg.PaymentType) + '=' + CONVERT(VARCHAR(32), pg.Amount)
                    FROM VendaPagamentos pg
                    WHERE pg.CompanyId = v.CompanyId AND pg.VendaId = v.Id
                    ORDER BY pg.CreatedAt, pg.Id
                    FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 1, '') AS Breakdown
            ) pay
            WHERE v.CompanyId = @CompanyId
              AND (@SaleNumber IS NULL OR v.SaleNumber = @SaleNumber)
              AND (@Desde IS NULL OR v.SaleDate >= @Desde)
              AND (@CaixaSessaoId IS NULL OR v.CaixaSessaoId = @CaixaSessaoId)
            ORDER BY v.SaleDate DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@SaleNumber", string.IsNullOrWhiteSpace(saleNumber) ? DBNull.Value : saleNumber);
        command.Parameters.AddWithValue("@CaixaSessaoId", string.IsNullOrWhiteSpace(caixaSessaoId) ? DBNull.Value : caixaSessaoId.Trim());
        var pDesde = command.Parameters.Add("@Desde", System.Data.SqlDbType.DateTimeOffset);
        pDesde.Value = desde.HasValue ? desde.Value : DBNull.Value;
        await using var reader = await command.ExecuteReaderAsync();
        var rows = new List<VendaHistoricoAD>();
        while (await reader.ReadAsync())
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<VendaDetalheCompletoAD?> ObterDetalheCompletoAsync(string companyId, string saleNumber)
    {
        var rows = await ListarAsync(companyId, saleNumber);
        if (rows.Count == 0) return null;

        var first = rows[0];

        const string sqlVendaId = "SELECT Id FROM Vendas WHERE CompanyId = @CompanyId AND SaleNumber = @SaleNumber;";
        await using var db = await connection.OpenConnectionAsync();
        await using var cmdVenda = new SqlCommand(sqlVendaId, db);
        cmdVenda.Parameters.AddWithValue("@CompanyId", companyId);
        cmdVenda.Parameters.AddWithValue("@SaleNumber", saleNumber);
        var vendaIdObj = await cmdVenda.ExecuteScalarAsync();
        var vendaId = vendaIdObj?.ToString() ?? string.Empty;

        var payments = !string.IsNullOrEmpty(vendaId)
            ? await ObterPagamentosVendaAsync(companyId, vendaId)
            : [];

        return new VendaDetalheCompletoAD
        {
            VendaId = vendaId,
            SaleNumber = first.SaleNumber,
            Status = first.Status,
            CustomerName = first.CustomerName,
            CustomerCpf = first.CustomerCpf,
            PaymentType = first.PaymentType,
            TotalAmount = first.TotalAmount,
            OperatorName = first.OperatorName,
            SaleDate = first.SaleDate,
            ClientSaleId = first.ClientSaleId,
            OfflineReference = first.OfflineReference,
            CanceladoEm = first.CanceladoEm,
            CanceladoPorOperadorNome = first.CanceladoPorOperadorNome,
            CanceladoPorSupervisorNome = first.CanceladoPorSupervisorNome,
            CanceladoJustificativa = first.CanceladoJustificativa,
            Items = rows,
            Payments = payments
        };
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

    public async Task<VendaRegistroResultadoAD> RegistrarAsync(string companyId, VendaRequest request, string? caixaSessaoId = null)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        try
        {
            var eventId = request.EventId?.Trim();
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                const string checkEventSql = """
                    SELECT PayloadHash, ResponsePayload
                    FROM ProcessedEvents WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId AND EventId = @EventId;
                    """;

                await using var checkCmd = new SqlCommand(checkEventSql, db, transaction);
                checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
                checkCmd.Parameters.AddWithValue("@EventId", eventId);

                await using var checkReader = await checkCmd.ExecuteReaderAsync();
                if (await checkReader.ReadAsync())
                {
                    var storedHash = checkReader.GetString(0);
                    var responsePayloadJson = checkReader.GetString(1);
                    await checkReader.CloseAsync();

                    var currentHash = !string.IsNullOrWhiteSpace(request.PayloadHash)
                        ? request.PayloadHash.Trim().ToLowerInvariant()
                        : HorusPayloadHash.ComputeHash(request);

                    if (!string.Equals(storedHash, currentHash, StringComparison.OrdinalIgnoreCase))
                    {
                        throw new IdempotencyConflictException(
                            eventId,
                            $"Conflito de idempotência: o EventId '{eventId}' já foi processado anteriormente com um payload diferente.");
                    }

                    var cached = JsonSerializer.Deserialize<VendaRegistroResultadoAD>(responsePayloadJson, JsonOptions)
                        ?? throw new InvalidOperationException("Falha ao recuperar payload de resposta do evento processado.");

                    cached.IsReplay = true;
                    await transaction.CommitAsync();
                    return cached;
                }
                await checkReader.CloseAsync();
            }

            var customerName = string.IsNullOrWhiteSpace(request.CustomerName) ? "Consumidor" : request.CustomerName.Trim();
            var customerCpf = string.IsNullOrWhiteSpace(request.CustomerCpf) ? "-" : request.CustomerCpf.Trim();
            var paymentType = string.IsNullOrWhiteSpace(request.PaymentType) ? "-" : request.PaymentType.Trim();
            var totalAmount = HorusMoneyFormat.ParseDecimalStrict(request.TotalAmount, nameof(request.TotalAmount));
            if (totalAmount < 0)
            {
                throw new ArgumentException("O valor total da venda não pode ser negativo.");
            }
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

                var disponivel = Math.Max(0, limiteCredito - saldoDevedor);
                if ((saldoDevedor + fiadoPayment.Amount) > limiteCredito)
                {
                    throw new InvalidOperationException(
                        $"Limite de crédito insuficiente para {customerName}. Limite total: R$ {limiteCredito:N2}, Saldo devedor: R$ {saldoDevedor:N2}, Disponível: R$ {disponivel:N2}, Tentativa fiado: R$ {fiadoPayment.Amount:N2}.");
                }
            }

            var isOfflineSync = !string.IsNullOrWhiteSpace(request.EventId) 
                || !string.IsNullOrWhiteSpace(request.OfflineReference) 
                || request.OccurredAt.HasValue;

            // Preço diferente do cadastrado exige autorização de gerente (senha dada no caixa). A conferência
            // roda na transação da venda: a autorização é consumida junto com ela e some se a venda falhar.
            var autorizacoesPreco = await AutorizacaoPrecoAB.ValidarPrecosDaVendaAsync(
                db, transaction, companyId, request.Items, request.ReenvioOffline);

            // A venda e a baixa de estoque compartilham a mesma transação para evitar histórico sem estoque atualizado.
            var (saleItems, rupturas) = await BaixarEstoqueAsync(db, transaction, companyId, request.Items, isOfflineSync);

            var result = await InserirVendaAsync(
                db, transaction, companyId, customerName, customerCpf, paymentType, totalAmount, operatorName, saleItems, payments,
                request.ClientSaleId, request.OfflineReference, request.OccurredAt, caixaSessaoId);

            await AutorizacaoPrecoAB.VincularVendaAsync(db, transaction, companyId, autorizacoesPreco, result.VendaId);

            result.Warnings = rupturas;

            FiadoMovimentoAD? fiadoMov = null;
            if (fiadoPayment is not null && fiadoPayment.Amount > 0 && fiadoClienteId is not null)
            {
                fiadoMov = await fiadoAb.RegistrarDebitoAsync(db, transaction, companyId, fiadoClienteId, fiadoPayment.Amount, result.VendaId, operatorName);
                result.FiadoSaldoAtual = fiadoMov.SaldoAtual;
                result.FiadoClienteId = fiadoClienteId;
            }

            if (!string.IsNullOrWhiteSpace(eventId))
            {
                var payloadHash = !string.IsNullOrWhiteSpace(request.PayloadHash)
                    ? request.PayloadHash.Trim().ToLowerInvariant()
                    : HorusPayloadHash.ComputeHash(request);

                var responseJson = JsonSerializer.Serialize(result, JsonOptions);
                const string insertEventSql = """
                    INSERT INTO ProcessedEvents
                        (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, ProcessedAt, ResponsePayload)
                    VALUES
                        (@Id, @CompanyId, @EventId, @EventType, @ClientSaleId, @PayloadHash, @ProcessedAt, @ResponsePayload);
                    """;

                await using var eventCmd = new SqlCommand(insertEventSql, db, transaction);
                eventCmd.Parameters.AddWithValue("@Id", $"pe-{Guid.NewGuid():N}");
                eventCmd.Parameters.AddWithValue("@CompanyId", companyId);
                eventCmd.Parameters.AddWithValue("@EventId", eventId);
                eventCmd.Parameters.AddWithValue("@EventType", request.EventType?.Trim() ?? "SALE_CREATED");
                eventCmd.Parameters.AddWithValue("@ClientSaleId", (object?)request.ClientSaleId ?? DBNull.Value);
                eventCmd.Parameters.AddWithValue("@PayloadHash", payloadHash);
                eventCmd.Parameters.AddWithValue("@ProcessedAt", HorusDateTime.Now);
                eventCmd.Parameters.AddWithValue("@ResponsePayload", responseJson);
                await eventCmd.ExecuteNonQueryAsync();
            }

            await transaction.CommitAsync();

            // Baixa FEFO por lote (Fase 2): depois do commit e sem nunca derrubar a venda já gravada.
            await loteAb.ConsumirVendaSeguroAsync(
                companyId, result.VendaId, saleItems.Select(item => (item.ProductCode, item.Quantity)));

            if (rupturas.Count > 0)
            {
                foreach (var r in rupturas)
                {
                    _ = auditLogAb.RegistrarAsync(
                        companyId, "", operatorName,
                        AuditEventTypes.EstoqueRuptura,
                        $"Ruptura de estoque na venda offline {result.SaleNumber}: {r.ProductName} (Cód. {r.ProductCode}). Estoque anterior: {HorusMoneyFormat.FormatQuantity(r.EstoqueAnterior)}, Vendido: {HorusMoneyFormat.FormatQuantity(r.QuantidadeVendida)}, Saldo resultante: {HorusMoneyFormat.FormatQuantity(r.SaldoResultante)}.",
                        entityType: "Produto",
                        entityId: r.ProductCode);
                }
            }

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
        List<VendaPagamentoRequest>? payments = null,
        string? caixaSessaoId = null)
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

                var disponivel = Math.Max(0, limiteCredito - saldoDevedor);
                if ((saldoDevedor + fiadoPaymentFixo.Amount) > limiteCredito)
                {
                    throw new InvalidOperationException(
                        $"Limite de crédito insuficiente para {customerName}. Limite total: R$ {limiteCredito:N2}, Saldo devedor: R$ {saldoDevedor:N2}, Disponível: R$ {disponivel:N2}, Tentativa fiado: R$ {fiadoPaymentFixo.Amount:N2}.");
                }
            }

            var result = await InserirVendaAsync(
                db, transaction, companyId, customerName, customerCpf, paymentType, totalAmount, operatorName, saleItems, pagamentos,
                caixaSessaoId: caixaSessaoId);

            FiadoMovimentoAD? fiadoMovFixo = null;
            if (fiadoPaymentFixo is not null && fiadoPaymentFixo.Amount > 0 && fiadoClienteIdFixo is not null)
            {
                fiadoMovFixo = await fiadoAb.RegistrarDebitoAsync(db, transaction, companyId, fiadoClienteIdFixo, fiadoPaymentFixo.Amount, result.VendaId, operatorName);
                result.FiadoSaldoAtual = fiadoMovFixo.SaldoAtual;
                result.FiadoClienteId = fiadoClienteIdFixo;
            }

            await transaction.CommitAsync();

            // Baixa FEFO por lote (Fase 2): depois do commit e sem nunca derrubar a venda já gravada.
            await loteAb.ConsumirVendaSeguroAsync(
                companyId, result.VendaId, saleItems.Select(item => (item.ProductCode, item.Quantity)));

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
        List<VendaPagamentoRequest>? payments,
        string? clientSaleId = null,
        string? offlineReference = null,
        DateTimeOffset? occurredAt = null,
        string? caixaSessaoId = null)
    {
        var saleNumber = await NextSaleNumberAsync(db, transaction, companyId);
        var now = HorusDateTime.Now;
        var saleDate = occurredAt ?? now;
        var saleId = $"sale-{companyId}-{saleNumber}";

        await using (var saleCommand = new SqlCommand(
                         """
                         INSERT INTO Vendas
                             (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, TotalAmount, OperatorName, SaleDate, ClientSaleId, OfflineReference, SyncedAt, CaixaSessaoId)
                         VALUES
                             (@Id, @CompanyId, @SaleNumber, @CustomerName, @CustomerCpf, @PaymentType, @TotalAmount, @OperatorName, @SaleDate, @ClientSaleId, @OfflineReference, @SyncedAt, @CaixaSessaoId);
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
            saleCommand.Parameters.AddWithValue("@SaleDate", saleDate);
            saleCommand.Parameters.AddWithValue("@ClientSaleId", (object?)clientSaleId ?? DBNull.Value);
            saleCommand.Parameters.AddWithValue("@OfflineReference", (object?)offlineReference ?? DBNull.Value);
            saleCommand.Parameters.AddWithValue("@SyncedAt", now);
            saleCommand.Parameters.AddWithValue("@CaixaSessaoId", (object?)caixaSessaoId ?? DBNull.Value);
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
                SaleDate = HorusDateTime.Format(saleDate),
                ClientSaleId = clientSaleId,
                OfflineReference = offlineReference
            });
        }

        return new VendaRegistroResultadoAD
        {
            SaleNumber = saleNumber,
            VendaId = saleId,
            ClientSaleId = clientSaleId,
            IsReplay = false,
            Rows = rows,
            Payments = pagamentosAD
        };
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

    private static async Task<(List<VendaItemRecord> Items, List<EstoqueRupturaAvisoAD> Rupturas)> BaixarEstoqueAsync(
        SqlConnection db,
        SqlTransaction transaction,
        string companyId,
        IEnumerable<VendaItemRequest> items,
        bool allowNegativeStock = false)
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
        var rupturas = new List<EstoqueRupturaAvisoAD>();

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
                if (!allowNegativeStock)
                {
                    throw new InvalidOperationException(
                        $"Estoque insuficiente para {productName}. Disponível: {HorusMoneyFormat.FormatQuantity(currentStock)}.");
                }

                rupturas.Add(new EstoqueRupturaAvisoAD
                {
                    ProductCode = code,
                    ProductName = productName,
                    EstoqueAnterior = currentStock,
                    QuantidadeVendida = totalQty,
                    SaldoResultante = currentStock - totalQty
                });
            }

            var nextStock = currentStock - totalQty;
            await using var update = new SqlCommand(
                """
                UPDATE Produtos
                   SET ProductQnt = @ProductQnt,
                       TotalPriceOnProduct = @TotalPriceOnProduct
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction);
            update.Parameters.AddWithValue("@ProductQnt", nextStock);
            update.Parameters.AddWithValue("@TotalPriceOnProduct", unitCost * nextStock);
            update.Parameters.AddWithValue("@Id", productId);
            update.Parameters.AddWithValue("@CompanyId", companyId);
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

        return (saleItems, rupturas);
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
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """,
                db,
                transaction);
            update.Parameters.AddWithValue("@ProductQnt", nextStock);
            update.Parameters.AddWithValue("@TotalPriceOnProduct", costUnitPrice * nextStock);
            update.Parameters.AddWithValue("@Id", productId);
            update.Parameters.AddWithValue("@CompanyId", companyId);
            await update.ExecuteNonQueryAsync();
        }

        return groupedItems;
    }

    private static VendaHistoricoAD Map(SqlDataReader reader) => new()
    {
        SaleNumber = ReadString(reader, "SaleNumber"),
        Status = ReadString(reader, "Status", "finalizada"),
        CustomerName = ReadString(reader, "CustomerName"),
        CustomerCpf = ReadString(reader, "CustomerCpf"),
        PaymentType = ReadString(reader, "PaymentType"),
        TotalAmount = HorusMoneyFormat.Format(ReadDecimal(reader, "TotalAmount")),
        OperatorName = ReadString(reader, "OperatorName"),
        ProductCode = ReadString(reader, "ProductCode"),
        ProductName = ReadString(reader, "ProductName"),
        Quantity = ReadDecimal(reader, "Quantity"),
        UnitPrice = HorusMoneyFormat.Format(ReadDecimal(reader, "UnitPrice")),
        Desconto = ReadDecimal(reader, "Desconto"),
        PromocaoId = ReadNullableString(reader, "PromocaoId"),
        ItemTotal = HorusMoneyFormat.Format(ReadDecimal(reader, "ItemTotal")),
        SaleDate = ReadSaleDate(reader, "SaleDate"),
        ClientSaleId = ReadNullableString(reader, "ClientSaleId"),
        OfflineReference = ReadNullableString(reader, "OfflineReference"),
        FiscalDocId = ReadNullableString(reader, "FiscalDocId"),
        FiscalModelo = ReadNullableInt(reader, "FiscalModelo"),
        FiscalNumeroNf = ReadNullableInt(reader, "FiscalNumeroNf"),
        FiscalSerie = ReadNullableInt(reader, "FiscalSerie"),
        FiscalStatus = ReadNullableInt(reader, "FiscalStatus"),
        FiscalChaveAcesso = ReadNullableString(reader, "FiscalChaveAcesso"),
        PaymentBreakdown = ReadNullableString(reader, "PaymentBreakdown"),
        CanceladoEm = ReadNullableSaleDate(reader, "CanceladoEm"),
        CanceladoPorOperadorNome = ReadNullableString(reader, "CanceladoPorOperadorNome"),
        CanceladoPorSupervisorNome = ReadNullableString(reader, "CanceladoPorSupervisorNome"),
        CanceladoJustificativa = ReadNullableString(reader, "CanceladoJustificativa")
    };

    private static string ReadString(SqlDataReader reader, string name, string fallback = "")
    {
        try
        {
            var ordinal = reader.GetOrdinal(name);
            return reader.IsDBNull(ordinal) ? fallback : reader.GetString(ordinal);
        }
        catch (IndexOutOfRangeException)
        {
            return fallback;
        }
    }

    private static string? ReadNullableSaleDate(SqlDataReader reader, string name)
    {
        try
        {
            var ordinal = reader.GetOrdinal(name);
            if (reader.IsDBNull(ordinal)) return null;
            var val = reader.GetValue(ordinal);
            return val switch
            {
                DateTimeOffset dto => HorusDateTime.Format(dto),
                DateTime dt => HorusDateTime.Format(HorusDateTime.ToBrasilia(dt)),
                string s when DateTimeOffset.TryParse(s, out var parsedDto) => HorusDateTime.Format(parsedDto),
                _ => val.ToString()
            };
        }
        catch
        {
            return null;
        }
    }

    private static decimal ReadDecimal(SqlDataReader reader, string name)
    {
        try
        {
            var ordinal = reader.GetOrdinal(name);
            if (reader.IsDBNull(ordinal)) return 0m;
            var val = reader.GetValue(ordinal);
            return val switch
            {
                decimal d => d,
                double dbl => (decimal)dbl,
                float flt => (decimal)flt,
                int i => i,
                long l => l,
                string s when decimal.TryParse(s, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var parsed) => parsed,
                _ => Convert.ToDecimal(val)
            };
        }
        catch
        {
            return 0m;
        }
    }

    private static string ReadSaleDate(SqlDataReader reader, string name)
    {
        try
        {
            var ordinal = reader.GetOrdinal(name);
            if (reader.IsDBNull(ordinal)) return "-";
            var val = reader.GetValue(ordinal);
            return val switch
            {
                DateTimeOffset dto => HorusDateTime.Format(dto),
                DateTime dt => HorusDateTime.Format(HorusDateTime.ToBrasilia(dt)),
                string s when DateTimeOffset.TryParse(s, out var parsedDto) => HorusDateTime.Format(parsedDto),
                _ => val.ToString() ?? "-"
            };
        }
        catch
        {
            return "-";
        }
    }

    private static string? ReadNullableString(SqlDataReader reader, string name)
    {
        try
        {
            var ordinal = reader.GetOrdinal(name);
            return reader.IsDBNull(ordinal) ? null : reader.GetString(ordinal);
        }
        catch (IndexOutOfRangeException)
        {
            return null;
        }
    }

    private static int? ReadNullableInt(SqlDataReader reader, string name)
    {
        try
        {
            var ordinal = reader.GetOrdinal(name);
            if (reader.IsDBNull(ordinal)) return null;
            // Colunas fiscais (Modelo/Serie/Status) são SMALLINT/TINYINT no banco; GetInt32 lançaria
            // InvalidCastException. Lê o valor bruto e converte de qualquer tipo inteiro do provider.
            var val = reader.GetValue(ordinal);
            return val switch
            {
                int i => i,
                short s => s,
                byte b => b,
                long l => (int)l,
                decimal d => (int)d,
                string str when int.TryParse(str, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var parsed) => parsed,
                _ => Convert.ToInt32(val)
            };
        }
        catch
        {
            return null;
        }
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

    public async Task<(bool Sucesso, string Mensagem, int ItensEstornados, string? CanceladoEm)> CancelarVendaComSupervisorAsync(
        string companyId,
        string saleNumber,
        string supervisorId,
        string supervisorNome,
        string operadorId,
        string operadorNome,
        string justificativa)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync();

        string vendaId;
        string? clienteIdFiado = null;
        decimal valorFiado = 0m;
        var itensParaEstornar = new List<(string ProductCode, decimal Quantity)>();
        DateTimeOffset now = DateTimeOffset.UtcNow;
        decimal totalVenda = 0m;

        try
        {
            // 1. Localiza a venda com bloqueio exclusivo
            const string sqlVenda = """
                SELECT Id, ISNULL(Status, 'finalizada') AS Status, TotalAmount, PaymentType
                FROM Vendas WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = @CompanyId AND SaleNumber = @SaleNumber;
                """;
            await using (var cmdVenda = new SqlCommand(sqlVenda, db, transaction))
            {
                cmdVenda.Parameters.AddWithValue("@CompanyId", companyId);
                cmdVenda.Parameters.AddWithValue("@SaleNumber", saleNumber);
                await using var readerVenda = await cmdVenda.ExecuteReaderAsync();
                if (!await readerVenda.ReadAsync())
                {
                    await readerVenda.CloseAsync();
                    await transaction.RollbackAsync();
                    return (false, "Venda não encontrada.", 0, null);
                }

                vendaId = readerVenda.GetString(readerVenda.GetOrdinal("Id"));
                var statusAtual = readerVenda.GetString(readerVenda.GetOrdinal("Status"));
                totalVenda = readerVenda.GetDecimal(readerVenda.GetOrdinal("TotalAmount"));
                await readerVenda.CloseAsync();

                if (statusAtual.Equals("cancelada", StringComparison.OrdinalIgnoreCase))
                {
                    await transaction.RollbackAsync();
                    return (false, "Esta venda já está cancelada.", 0, null);
                }
            }

            // 2. Atualiza a venda para Status = 'cancelada' com dados de auditoria
            const string sqlUpdateVenda = """
                UPDATE Vendas
                SET Status = 'cancelada',
                    CanceladoEm = @CanceladoEm,
                    CanceladoPorOperadorId = @OperadorId,
                    CanceladoPorOperadorNome = @OperadorNome,
                    CanceladoPorSupervisorId = @SupervisorId,
                    CanceladoPorSupervisorNome = @SupervisorNome,
                    CanceladoJustificativa = @Justificativa
                WHERE Id = @VendaId AND CompanyId = @CompanyId;
                """;
            await using (var cmdUpdate = new SqlCommand(sqlUpdateVenda, db, transaction))
            {
                cmdUpdate.Parameters.AddWithValue("@CanceladoEm", now);
                cmdUpdate.Parameters.AddWithValue("@OperadorId", operadorId);
                cmdUpdate.Parameters.AddWithValue("@OperadorNome", operadorNome);
                cmdUpdate.Parameters.AddWithValue("@SupervisorId", supervisorId);
                cmdUpdate.Parameters.AddWithValue("@SupervisorNome", supervisorNome);
                cmdUpdate.Parameters.AddWithValue("@Justificativa", justificativa.Trim());
                cmdUpdate.Parameters.AddWithValue("@VendaId", vendaId);
                cmdUpdate.Parameters.AddWithValue("@CompanyId", companyId);
                await cmdUpdate.ExecuteNonQueryAsync();
            }

            // 3. Busca os itens da venda para estornar o estoque
            // VendaItens não tem coluna CompanyId: o isolamento por empresa vem da junção com Vendas.
            const string sqlItens = """
                SELECT vi.ProductCode, vi.Quantity
                FROM VendaItens vi
                INNER JOIN Vendas v ON v.Id = vi.VendaId
                WHERE vi.VendaId = @VendaId AND v.CompanyId = @CompanyId;
                """;
            await using (var cmdItens = new SqlCommand(sqlItens, db, transaction))
            {
                cmdItens.Parameters.AddWithValue("@VendaId", vendaId);
                cmdItens.Parameters.AddWithValue("@CompanyId", companyId);
                await using var readerItens = await cmdItens.ExecuteReaderAsync();
                while (await readerItens.ReadAsync())
                {
                    var code = readerItens.GetString(readerItens.GetOrdinal("ProductCode"));
                    var qty = readerItens.GetDecimal(readerItens.GetOrdinal("Quantity"));
                    if (!string.IsNullOrWhiteSpace(code) && qty > 0)
                    {
                        itensParaEstornar.Add((code.Trim(), qty));
                    }
                }
            }

            // 4. Estorna os produtos no estoque
            foreach (var (pCode, pQty) in itensParaEstornar)
            {
                const string sqlEstorno = """
                    UPDATE Produtos
                    SET ProductQnt = ProductQnt + @Quantity,
                        TotalPriceOnProduct = ProductUnitPrice * (ProductQnt + @Quantity)
                    WHERE CompanyId = @CompanyId AND ProductCode = @ProductCode;
                    """;
                await using var cmdEstorno = new SqlCommand(sqlEstorno, db, transaction);
                cmdEstorno.Parameters.AddWithValue("@Quantity", pQty);
                cmdEstorno.Parameters.AddWithValue("@CompanyId", companyId);
                cmdEstorno.Parameters.AddWithValue("@ProductCode", pCode);
                await cmdEstorno.ExecuteNonQueryAsync();
            }

            // 5. Verifica se há débito em FiadoMovimentos associado à venda
            const string sqlCheckFiado = """
                SELECT TOP 1 ClienteId, Valor
                FROM FiadoMovimentos
                WHERE VendaId = @VendaId AND CompanyId = @CompanyId AND Tipo = 1;
                """;
            await using (var cmdFiado = new SqlCommand(sqlCheckFiado, db, transaction))
            {
                cmdFiado.Parameters.AddWithValue("@VendaId", vendaId);
                cmdFiado.Parameters.AddWithValue("@CompanyId", companyId);
                await using var readerFiado = await cmdFiado.ExecuteReaderAsync();
                if (await readerFiado.ReadAsync())
                {
                    clienteIdFiado = readerFiado.GetString(readerFiado.GetOrdinal("ClienteId"));
                    valorFiado = readerFiado.GetDecimal(readerFiado.GetOrdinal("Valor"));
                }
            }

            // 6. Atualiza documentos fiscais associados para cancelado
            const string sqlDocFiscal = """
                UPDATE DocumentosFiscais
                SET Status = 3, -- Cancelado
                    CanceladoPorSupervisorId = @SupervisorId,
                    CanceladoPorSupervisorNome = @SupervisorNome,
                    CanceladoPorOperador = @OperadorNome,
                    CanceladoJustificativa = @Justificativa,
                    AtualizadoEm = @Now
                WHERE VendaId = @VendaId AND CompanyId = @CompanyId AND Status <> 3;
                """;
            await using (var cmdDocFiscal = new SqlCommand(sqlDocFiscal, db, transaction))
            {
                cmdDocFiscal.Parameters.AddWithValue("@SupervisorId", supervisorId);
                cmdDocFiscal.Parameters.AddWithValue("@SupervisorNome", supervisorNome);
                cmdDocFiscal.Parameters.AddWithValue("@OperadorNome", operadorNome);
                cmdDocFiscal.Parameters.AddWithValue("@Justificativa", justificativa.Trim());
                cmdDocFiscal.Parameters.AddWithValue("@Now", now);
                cmdDocFiscal.Parameters.AddWithValue("@VendaId", vendaId);
                cmdDocFiscal.Parameters.AddWithValue("@CompanyId", companyId);
                await cmdDocFiscal.ExecuteNonQueryAsync();
            }

            await transaction.CommitAsync();
        }
        catch (Exception ex)
        {
            await transaction.RollbackAsync();
            return (false, $"Erro ao cancelar venda: {ex.Message}", 0, null);
        }

        // Devolve aos lotes de origem o que a venda tirou deles (Fase 2). Seguro: nunca lança exceção.
        await loteAb.EstornarVendaSeguroAsync(companyId, vendaId);

        // 7. Se houve fiado, estorna o saldo devedor do cliente de forma consistente
        if (!string.IsNullOrEmpty(clienteIdFiado) && valorFiado > 0)
        {
            try
            {
                await fiadoAb.RegistrarCreditoAsync(
                    companyId,
                    clienteIdFiado,
                    valorFiado,
                    "Estorno",
                    $"Cancelamento da Venda #{saleNumber}. Motivo: {justificativa.Trim()} (Aut: {supervisorNome})",
                    operadorNome);
            }
            catch
            {
                // Não impede a conclusão do cancelamento caso o estorno financeiro secundário falhe
            }
        }

        // 8. Grava na trilha de auditoria
        try
        {
            await auditLogAb.RegistrarAsync(
                companyId,
                operadorId,
                operadorNome,
                AuditEventTypes.VendaCancelada,
                $"Venda #{saleNumber} cancelada. Total: R$ {HorusMoneyFormat.Format(totalVenda)}. Supervisor: {supervisorNome} ({supervisorId}). Motivo: {justificativa.Trim()}",
                "Vendas",
                vendaId);
        }
        catch
        {
            // Trilha de auditoria não deve lançar exceção ao usuário
        }

        var canceladoEmStr = HorusDateTime.Format(now);
        return (true, "Venda cancelada e mercadorias estornadas ao estoque com sucesso.", itensParaEstornar.Count, canceladoEmStr);
    }

    public async Task<List<VendaCanceladaResumoAD>> ListarCancelamentosAsync(
        string companyId,
        DateTimeOffset? de = null,
        DateTimeOffset? ate = null)
    {
        const string sql = """
            SELECT v.Id AS VendaId, v.SaleNumber, ISNULL(v.Status, 'finalizada') AS Status,
                   v.CustomerName, v.CustomerCpf, v.PaymentType, v.TotalAmount, v.OperatorName,
                   v.SaleDate, v.CanceladoEm, v.CanceladoPorOperadorNome, v.CanceladoPorSupervisorNome,
                   v.CanceladoJustificativa,
                   ISNULL(i.ProductCode, '') AS ProductCode,
                   ISNULL(i.ProductName, 'Item') AS ProductName,
                   ISNULL(i.Quantity, 1) AS Quantity,
                   ISNULL(i.UnitPrice, v.TotalAmount) AS UnitPrice,
                   ISNULL(i.ItemTotal, v.TotalAmount) AS ItemTotal
            FROM Vendas v
            LEFT JOIN VendaItens i ON v.Id = i.VendaId
            WHERE v.CompanyId = @CompanyId
              AND v.Status = 'cancelada'
              AND (@De IS NULL OR v.CanceladoEm >= @De OR (v.CanceladoEm IS NULL AND v.SaleDate >= @De))
              AND (@Ate IS NULL OR v.CanceladoEm <= @Ate OR (v.CanceladoEm IS NULL AND v.SaleDate <= @Ate))
            ORDER BY COALESCE(v.CanceladoEm, v.SaleDate) DESC;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        var pDe = command.Parameters.Add("@De", System.Data.SqlDbType.DateTimeOffset);
        pDe.Value = de.HasValue ? de.Value : DBNull.Value;
        var pAte = command.Parameters.Add("@Ate", System.Data.SqlDbType.DateTimeOffset);
        pAte.Value = ate.HasValue ? ate.Value : DBNull.Value;

        await using var reader = await command.ExecuteReaderAsync();
        var dict = new Dictionary<string, VendaCanceladaResumoAD>();

        while (await reader.ReadAsync())
        {
            var saleNumber = ReadString(reader, "SaleNumber");
            if (!dict.TryGetValue(saleNumber, out var resumo))
            {
                resumo = new VendaCanceladaResumoAD
                {
                    VendaId = ReadString(reader, "VendaId"),
                    SaleNumber = saleNumber,
                    CustomerName = ReadString(reader, "CustomerName"),
                    CustomerCpf = ReadString(reader, "CustomerCpf"),
                    PaymentType = ReadString(reader, "PaymentType"),
                    TotalAmount = HorusMoneyFormat.Format(ReadDecimal(reader, "TotalAmount")),
                    OperatorName = ReadString(reader, "OperatorName"),
                    SaleDate = ReadSaleDate(reader, "SaleDate"),
                    CanceladoEm = ReadNullableSaleDate(reader, "CanceladoEm") ?? "-",
                    CanceladoPorOperadorNome = ReadNullableString(reader, "CanceladoPorOperadorNome") ?? "-",
                    CanceladoPorSupervisorNome = ReadNullableString(reader, "CanceladoPorSupervisorNome") ?? "-",
                    CanceladoJustificativa = ReadNullableString(reader, "CanceladoJustificativa") ?? "-",
                    TotalItens = 0,
                    TotalQuantidadeItens = 0,
                    Items = []
                };
                dict[saleNumber] = resumo;
            }

            var productCode = ReadString(reader, "ProductCode");
            if (!string.IsNullOrEmpty(productCode))
            {
                var qty = ReadDecimal(reader, "Quantity");
                var item = new VendaHistoricoAD
                {
                    SaleNumber = saleNumber,
                    ProductCode = productCode,
                    ProductName = ReadString(reader, "ProductName"),
                    Quantity = qty,
                    UnitPrice = HorusMoneyFormat.Format(ReadDecimal(reader, "UnitPrice")),
                    ItemTotal = HorusMoneyFormat.Format(ReadDecimal(reader, "ItemTotal")),
                };
                resumo.Items.Add(item);
                resumo.TotalItens++;
                resumo.TotalQuantidadeItens += qty;
            }
        }

        foreach (var r in dict.Values)
        {
            r.ItensResumo = string.Join("; ", r.Items.Select(x => $"{x.Quantity}x {x.ProductName}"));
        }

        return dict.Values.ToList();
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
