/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/CaixaAB.cs
 * Objetivo: concentra comandos SQL e persistência de abertura, fechamento e status de caixa.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 */
using System.Text.Json;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Caixa;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class CaixaAB(Connection connection)
{
    public async Task<List<CaixaSessionAD>> ListarSessoesAsync(string companyId, CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(
            """
            SELECT Id, OpenedAt, ClosedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note,
                   ExpectedCashAmount, DifferenceAmount, DifferenceReason
            FROM CaixaSessoes
            WHERE CompanyId = @CompanyId
            ORDER BY OpenedAt DESC;
            """,
            db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var rows = new List<CaixaSessionAD>();
        while (await reader.ReadAsync(cancellationToken))
        {
            rows.Add(Map(reader));
        }

        return rows;
    }

    public async Task<CaixaSessionAD?> ObterSessaoAbertaAsync(string companyId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT TOP 1 Id, OpenedAt, ClosedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note,
                   ExpectedCashAmount, DifferenceAmount, DifferenceReason
            FROM CaixaSessoes
            WHERE CompanyId = @CompanyId AND ClosedAt IS NULL
            ORDER BY OpenedAt DESC;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (await reader.ReadAsync(cancellationToken))
        {
            return Map(reader);
        }

        return null;
    }

    public async Task AbrirAsync(
        string id,
        string companyId,
        DateTimeOffset openedAt,
        decimal openingAmount,
        string operatorId,
        string operatorName,
        CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(
            """
            INSERT INTO CaixaSessoes
                (Id, CompanyId, OpenedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note)
            VALUES
                (@Id, @CompanyId, @OpenedAt, @OpeningAmount, 0, @OperatorId, @OperatorName, N'', N'', N'');
            """,
            db);
        command.Parameters.AddWithValue("@Id", id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@OpenedAt", openedAt);
        command.Parameters.AddWithValue("@OpeningAmount", openingAmount);
        command.Parameters.AddWithValue("@OperatorId", operatorId);
        command.Parameters.AddWithValue("@OperatorName", operatorName);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<CaixaStatusDto> AbrirIdempotenteAsync(
        string id,
        string companyId,
        DateTimeOffset openedAt,
        decimal openingAmount,
        string operatorId,
        string operatorName,
        string? eventId,
        string? payloadHash,
        Func<DateTimeOffset, Task<CaixaStatusDto>> statusBuilderAsync,
        CancellationToken cancellationToken = default)
    {
        var now = openedAt;
        eventId = eventId?.Trim();
        var currentHash = !string.IsNullOrWhiteSpace(payloadHash)
            ? payloadHash.Trim().ToLowerInvariant()
            : (!string.IsNullOrWhiteSpace(eventId) ? HorusPayloadHash.ComputeHash(new { openingAmount = HorusMoneyFormat.Format(openingAmount) }) : string.Empty);

        if (!string.IsNullOrWhiteSpace(eventId))
        {
            var existing = await ObterEventoProcessadoAsync(companyId, eventId, currentHash, cancellationToken);
            if (existing is not null)
            {
                return existing;
            }
        }

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(cancellationToken);

        try
        {
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                const string checkSql = """
                    SELECT PayloadHash, ResponsePayload
                    FROM ProcessedEvents WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId AND EventId = @EventId;
                    """;

                await using var checkCmd = new SqlCommand(checkSql, db, transaction);
                checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
                checkCmd.Parameters.AddWithValue("@EventId", eventId);

                await using (var reader = await checkCmd.ExecuteReaderAsync(cancellationToken))
                {
                    if (await reader.ReadAsync(cancellationToken))
                    {
                        var storedHash = reader.GetString(0);
                        var responseJson = reader.GetString(1);
                        await reader.CloseAsync();

                        if (!string.Equals(storedHash, currentHash, StringComparison.OrdinalIgnoreCase))
                        {
                            throw new IdempotencyConflictException(
                                eventId,
                                $"Conflito de idempotência: o EventId '{eventId}' já foi processado anteriormente com um payload diferente.");
                        }

                        var cached = JsonSerializer.Deserialize<CaixaStatusDto>(responseJson, JsonOptions)
                            ?? throw new InvalidOperationException("Falha ao recuperar payload de resposta do evento processado.");

                        cached.IsReplay = true;
                        await transaction.CommitAsync(cancellationToken);
                        return cached;
                    }
                }
            }

            const string checkOpenSql = """
                SELECT TOP 1 Id
                FROM CaixaSessoes WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = @CompanyId AND ClosedAt IS NULL;
                """;
            await using (var checkOpenCmd = new SqlCommand(checkOpenSql, db, transaction))
            {
                checkOpenCmd.Parameters.AddWithValue("@CompanyId", companyId);
                var existingOpen = await checkOpenCmd.ExecuteScalarAsync(cancellationToken);
                if (existingOpen is not null)
                {
                    throw new InvalidOperationException("Já existe um caixa aberto para venda.");
                }
            }

            const string insertSessionSql = """
                INSERT INTO CaixaSessoes
                    (Id, CompanyId, OpenedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note)
                VALUES
                    (@Id, @CompanyId, @OpenedAt, @OpeningAmount, 0, @OperatorId, @OperatorName, N'', N'', N'');
                """;
            await using (var cmd = new SqlCommand(insertSessionSql, db, transaction))
            {
                cmd.Parameters.AddWithValue("@Id", id);
                cmd.Parameters.AddWithValue("@CompanyId", companyId);
                cmd.Parameters.AddWithValue("@OpenedAt", openedAt);
                cmd.Parameters.AddWithValue("@OpeningAmount", openingAmount);
                cmd.Parameters.AddWithValue("@OperatorId", operatorId);
                cmd.Parameters.AddWithValue("@OperatorName", operatorName);
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }

            var processedEventId = (string?)null;
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                processedEventId = $"pe-{Guid.NewGuid():N}";
                const string insertEventSql = """
                    INSERT INTO ProcessedEvents
                        (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, ProcessedAt, ResponsePayload)
                    VALUES
                        (@Id, @CompanyId, @EventId, @EventType, @ClientSaleId, @PayloadHash, @ProcessedAt, @ResponsePayload);
                    """;

                await using (var eventCmd = new SqlCommand(insertEventSql, db, transaction))
                {
                    eventCmd.Parameters.AddWithValue("@Id", processedEventId);
                    eventCmd.Parameters.AddWithValue("@CompanyId", companyId);
                    eventCmd.Parameters.AddWithValue("@EventId", eventId);
                    eventCmd.Parameters.AddWithValue("@EventType", "CASH_OPEN");
                    eventCmd.Parameters.AddWithValue("@ClientSaleId", DBNull.Value);
                    eventCmd.Parameters.AddWithValue("@PayloadHash", currentHash);
                    eventCmd.Parameters.AddWithValue("@ProcessedAt", now);
                    eventCmd.Parameters.AddWithValue("@ResponsePayload", "{}");
                    await eventCmd.ExecuteNonQueryAsync(cancellationToken);
                }
            }

            await transaction.CommitAsync(cancellationToken);

            var updatedStatus = await statusBuilderAsync(now);
            updatedStatus.IsReplay = false;

            if (processedEventId is not null)
            {
                try
                {
                    var responseJson = JsonSerializer.Serialize(updatedStatus, JsonOptions);
                    const string updateSql = "UPDATE ProcessedEvents SET ResponsePayload = @Payload WHERE Id = @Id;";
                    await using var db2 = await connection.OpenConnectionAsync(cancellationToken);
                    await using var updateCmd = new SqlCommand(updateSql, db2);
                    updateCmd.Parameters.AddWithValue("@Id", processedEventId);
                    updateCmd.Parameters.AddWithValue("@Payload", responseJson);
                    await updateCmd.ExecuteNonQueryAsync(cancellationToken);
                }
                catch
                {
                }
            }

            return updatedStatus;
        }
        catch (SqlException ex) when (ex.Number is 2627 or 2601)
        {
            await transaction.RollbackAsync(cancellationToken);
            var replay = await ObterEventoProcessadoAsync(companyId, eventId!, currentHash, cancellationToken);
            if (replay is not null) return replay;
            throw;
        }
        catch
        {
            await transaction.RollbackAsync(cancellationToken);
            throw;
        }
    }

    public async Task FecharAsync(
        string id,
        string companyId,
        DateTimeOffset closedAt,
        decimal closingAmount,
        string closedById,
        string closedByName,
        string note,
        decimal expectedCashAmount,
        decimal differenceAmount,
        string? differenceReason,
        CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(
            """
            UPDATE CaixaSessoes
               SET ClosedAt = @ClosedAt,
                   ClosingAmount = @ClosingAmount,
                   ClosedById = @ClosedById,
                   ClosedByName = @ClosedByName,
                   Note = @Note,
                   ExpectedCashAmount = @ExpectedCashAmount,
                   DifferenceAmount = @DifferenceAmount,
                   DifferenceReason = @DifferenceReason
             WHERE Id = @Id AND CompanyId = @CompanyId;
            """,
            db);
        command.Parameters.AddWithValue("@ClosedAt", closedAt);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@ClosingAmount", closingAmount);
        command.Parameters.AddWithValue("@ClosedById", closedById);
        command.Parameters.AddWithValue("@ClosedByName", closedByName);
        command.Parameters.AddWithValue("@Note", note);
        command.Parameters.AddWithValue("@ExpectedCashAmount", expectedCashAmount);
        command.Parameters.AddWithValue("@DifferenceAmount", differenceAmount);
        command.Parameters.AddWithValue("@DifferenceReason", (object?)differenceReason ?? DBNull.Value);
        command.Parameters.AddWithValue("@Id", id);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<CaixaStatusDto> FecharIdempotenteAsync(
        string id,
        string companyId,
        DateTimeOffset closedAt,
        decimal closingAmount,
        string closedById,
        string closedByName,
        string note,
        decimal expectedCashAmount,
        decimal differenceAmount,
        string? differenceReason,
        string? eventId,
        string? payloadHash,
        Func<DateTimeOffset, Task<CaixaStatusDto>> statusBuilderAsync,
        CancellationToken cancellationToken = default)
    {
        var now = closedAt;
        eventId = eventId?.Trim();
        var currentHash = !string.IsNullOrWhiteSpace(payloadHash)
            ? payloadHash.Trim().ToLowerInvariant()
            : (!string.IsNullOrWhiteSpace(eventId) ? HorusPayloadHash.ComputeHash(new { closingAmount = HorusMoneyFormat.Format(closingAmount), note, differenceReason }) : string.Empty);

        if (!string.IsNullOrWhiteSpace(eventId))
        {
            var existing = await ObterEventoProcessadoAsync(companyId, eventId, currentHash, cancellationToken);
            if (existing is not null)
            {
                return existing;
            }
        }

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(cancellationToken);

        try
        {
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                const string checkSql = """
                    SELECT PayloadHash, ResponsePayload
                    FROM ProcessedEvents WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId AND EventId = @EventId;
                    """;

                await using var checkCmd = new SqlCommand(checkSql, db, transaction);
                checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
                checkCmd.Parameters.AddWithValue("@EventId", eventId);

                await using (var reader = await checkCmd.ExecuteReaderAsync(cancellationToken))
                {
                    if (await reader.ReadAsync(cancellationToken))
                    {
                        var storedHash = reader.GetString(0);
                        var responseJson = reader.GetString(1);
                        await reader.CloseAsync();

                        if (!string.Equals(storedHash, currentHash, StringComparison.OrdinalIgnoreCase))
                        {
                            throw new IdempotencyConflictException(
                                eventId,
                                $"Conflito de idempotência: o EventId '{eventId}' já foi processado anteriormente com um payload diferente.");
                        }

                        var cached = JsonSerializer.Deserialize<CaixaStatusDto>(responseJson, JsonOptions)
                            ?? throw new InvalidOperationException("Falha ao recuperar payload de resposta do evento processado.");

                        cached.IsReplay = true;
                        await transaction.CommitAsync(cancellationToken);
                        return cached;
                    }
                }
            }

            const string checkSessionSql = """
                SELECT ClosedAt
                FROM CaixaSessoes WITH (UPDLOCK, ROWLOCK)
                WHERE Id = @Id AND CompanyId = @CompanyId;
                """;
            await using (var checkSessionCmd = new SqlCommand(checkSessionSql, db, transaction))
            {
                checkSessionCmd.Parameters.AddWithValue("@Id", id);
                checkSessionCmd.Parameters.AddWithValue("@CompanyId", companyId);
                var closedVal = await checkSessionCmd.ExecuteScalarAsync(cancellationToken);
                if (closedVal == null)
                {
                    throw new InvalidOperationException("Sessão de caixa não encontrada.");
                }
                if (closedVal != DBNull.Value && closedVal is not null)
                {
                    throw new InvalidOperationException("O caixa atual já foi fechado.");
                }
            }

            const string updateSessionSql = """
                UPDATE CaixaSessoes
                   SET ClosedAt = @ClosedAt,
                       ClosingAmount = @ClosingAmount,
                       ClosedById = @ClosedById,
                       ClosedByName = @ClosedByName,
                       Note = @Note,
                       ExpectedCashAmount = @ExpectedCashAmount,
                       DifferenceAmount = @DifferenceAmount,
                       DifferenceReason = @DifferenceReason
                 WHERE Id = @Id AND CompanyId = @CompanyId;
                """;
            await using (var cmd = new SqlCommand(updateSessionSql, db, transaction))
            {
                cmd.Parameters.AddWithValue("@ClosedAt", closedAt);
                cmd.Parameters.AddWithValue("@CompanyId", companyId);
                cmd.Parameters.AddWithValue("@ClosingAmount", closingAmount);
                cmd.Parameters.AddWithValue("@ClosedById", closedById);
                cmd.Parameters.AddWithValue("@ClosedByName", closedByName);
                cmd.Parameters.AddWithValue("@Note", note);
                cmd.Parameters.AddWithValue("@ExpectedCashAmount", expectedCashAmount);
                cmd.Parameters.AddWithValue("@DifferenceAmount", differenceAmount);
                cmd.Parameters.AddWithValue("@DifferenceReason", (object?)differenceReason ?? DBNull.Value);
                cmd.Parameters.AddWithValue("@Id", id);
                await cmd.ExecuteNonQueryAsync(cancellationToken);
            }

            var processedEventId = (string?)null;
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                processedEventId = $"pe-{Guid.NewGuid():N}";
                const string insertEventSql = """
                    INSERT INTO ProcessedEvents
                        (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, ProcessedAt, ResponsePayload)
                    VALUES
                        (@Id, @CompanyId, @EventId, @EventType, @ClientSaleId, @PayloadHash, @ProcessedAt, @ResponsePayload);
                    """;

                await using (var eventCmd = new SqlCommand(insertEventSql, db, transaction))
                {
                    eventCmd.Parameters.AddWithValue("@Id", processedEventId);
                    eventCmd.Parameters.AddWithValue("@CompanyId", companyId);
                    eventCmd.Parameters.AddWithValue("@EventId", eventId);
                    eventCmd.Parameters.AddWithValue("@EventType", "CASH_CLOSE");
                    eventCmd.Parameters.AddWithValue("@ClientSaleId", DBNull.Value);
                    eventCmd.Parameters.AddWithValue("@PayloadHash", currentHash);
                    eventCmd.Parameters.AddWithValue("@ProcessedAt", now);
                    eventCmd.Parameters.AddWithValue("@ResponsePayload", "{}");
                    await eventCmd.ExecuteNonQueryAsync(cancellationToken);
                }
            }

            await transaction.CommitAsync(cancellationToken);

            var updatedStatus = await statusBuilderAsync(now);
            updatedStatus.IsReplay = false;

            if (processedEventId is not null)
            {
                try
                {
                    var responseJson = JsonSerializer.Serialize(updatedStatus, JsonOptions);
                    const string updateSql = "UPDATE ProcessedEvents SET ResponsePayload = @Payload WHERE Id = @Id;";
                    await using var db2 = await connection.OpenConnectionAsync(cancellationToken);
                    await using var updateCmd = new SqlCommand(updateSql, db2);
                    updateCmd.Parameters.AddWithValue("@Id", processedEventId);
                    updateCmd.Parameters.AddWithValue("@Payload", responseJson);
                    await updateCmd.ExecuteNonQueryAsync(cancellationToken);
                }
                catch
                {
                }
            }

            return updatedStatus;
        }
        catch (SqlException ex) when (ex.Number is 2627 or 2601)
        {
            await transaction.RollbackAsync(cancellationToken);
            var replay = await ObterEventoProcessadoAsync(companyId, eventId!, currentHash, cancellationToken);
            if (replay is not null) return replay;
            throw;
        }
        catch
        {
            await transaction.RollbackAsync(cancellationToken);
            throw;
        }
    }

    /// <summary>Soma as vendas da empresa por forma de pagamento entre duas datas — usado na conferência do fechamento de caixa.</summary>
    public async Task<Dictionary<string, decimal>> ObterTotaisPorFormaPagamentoAsync(
        string companyId, DateTimeOffset desde, DateTimeOffset ate, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT Combined.PaymentType, SUM(Combined.Amount) AS Total
            FROM (
                SELECT vp.PaymentType, vp.Amount
                FROM VendaPagamentos vp
                INNER JOIN Vendas v ON v.Id = vp.VendaId AND v.CompanyId = vp.CompanyId
                WHERE vp.CompanyId = @CompanyId AND v.SaleDate >= @Desde AND v.SaleDate <= @Ate

                UNION ALL

                SELECT v.PaymentType, v.TotalAmount AS Amount
                FROM Vendas v
                WHERE v.CompanyId = @CompanyId AND v.SaleDate >= @Desde AND v.SaleDate <= @Ate
                  AND NOT EXISTS (SELECT 1 FROM VendaPagamentos vp WHERE vp.VendaId = v.Id)
            ) Combined
            GROUP BY Combined.PaymentType;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@Desde", desde);
        command.Parameters.AddWithValue("@Ate", ate);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var totals = new Dictionary<string, decimal>(StringComparer.OrdinalIgnoreCase);
        while (await reader.ReadAsync(cancellationToken))
        {
            var paymentType = ReadString(reader, "PaymentType");
            var total = reader.IsDBNull(reader.GetOrdinal("Total")) ? 0m : reader.GetDecimal(reader.GetOrdinal("Total"));
            totals[paymentType] = total;
        }

        return totals;
    }

    public async Task RegistrarMovimentoAsync(string companyId, CaixaMovimentoAD movimento)
    {
        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(
            """
            INSERT INTO CaixaMovimentos (Id, CompanyId, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName)
            VALUES (@Id, @CompanyId, @CaixaSessaoId, @Tipo, @Valor, @Motivo, @CreatedAt, @OperatorId, @OperatorName);
            """,
            db);
        command.Parameters.AddWithValue("@Id", movimento.Id);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@CaixaSessaoId", movimento.CaixaSessaoId);
        command.Parameters.AddWithValue("@Tipo", (byte)movimento.Tipo);
        command.Parameters.AddWithValue("@Valor", movimento.Valor);
        command.Parameters.AddWithValue("@Motivo", movimento.Motivo);
        command.Parameters.AddWithValue("@CreatedAt", movimento.CreatedAt);
        command.Parameters.AddWithValue("@OperatorId", movimento.OperatorId);
        command.Parameters.AddWithValue("@OperatorName", movimento.OperatorName);
        await command.ExecuteNonQueryAsync();
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    public async Task<CaixaStatusDto?> ObterEventoProcessadoAsync(string companyId, string eventId, string? currentHash = null, CancellationToken cancellationToken = default)
    {
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        const string checkSql = """
            SELECT PayloadHash, ResponsePayload
            FROM ProcessedEvents
            WHERE CompanyId = @CompanyId AND EventId = @EventId;
            """;

        await using var cmd = new SqlCommand(checkSql, db);
        cmd.Parameters.AddWithValue("@CompanyId", companyId);
        cmd.Parameters.AddWithValue("@EventId", eventId);

        await using var reader = await cmd.ExecuteReaderAsync(cancellationToken);
        if (await reader.ReadAsync(cancellationToken))
        {
            var storedHash = reader.GetString(0);
            var responseJson = reader.GetString(1);
            await reader.CloseAsync();

            if (!string.IsNullOrWhiteSpace(currentHash) && !string.Equals(storedHash, currentHash, StringComparison.OrdinalIgnoreCase))
            {
                throw new IdempotencyConflictException(
                    eventId,
                    $"Conflito de idempotência: o EventId '{eventId}' já foi processado anteriormente com um payload diferente.");
            }

            var cached = JsonSerializer.Deserialize<CaixaStatusDto>(responseJson, JsonOptions)
                ?? throw new InvalidOperationException("Falha ao recuperar payload de resposta do evento processado.");

            cached.IsReplay = true;
            return cached;
        }

        return null;
    }

    public async Task<CaixaStatusDto> RegistrarMovimentoIdempotenteAsync(
        string companyId,
        RegistrarMovimentoCaixaRequest request,
        AuthenticatedUser currentUser,
        Func<DateTimeOffset, CaixaStatusDto> statusBuilder,
        Func<string, CaixaSessionAD, DateTimeOffset, decimal> computeExpectedCash,
        Action<CaixaSessionAD, AuthenticatedUser> ensureResponsavel,
        string? ip = null,
        CancellationToken cancellationToken = default)
    {
        var now = HorusDateTime.Now;
        var eventId = request.EventId?.Trim();
        var currentHash = !string.IsNullOrWhiteSpace(request.PayloadHash)
            ? request.PayloadHash.Trim().ToLowerInvariant()
            : HorusPayloadHash.ComputeHash(request);

        // 1. Verificação preliminar de replay se EventId foi fornecido
        if (!string.IsNullOrWhiteSpace(eventId))
        {
            var existing = await ObterEventoProcessadoAsync(companyId, eventId, currentHash, cancellationToken);
            if (existing is not null)
            {
                return existing;
            }
        }

        // 2. Validações de regra de negócio antes de abrir a transação
        var openSession = await ObterSessaoAbertaAsync(companyId, cancellationToken);
        if (openSession is null)
        {
            throw new InvalidOperationException("Não existe caixa aberto para lançar movimento.");
        }

        ensureResponsavel(openSession, currentUser);

        if (!Enum.TryParse<TipoMovimentoCaixa>(request.Tipo, ignoreCase: true, out var tipo))
        {
            throw new InvalidOperationException("Tipo de movimento inválido — use \"Reforco\" ou \"Sangria\".");
        }

        var valor = HorusMoneyFormat.ParseDecimal(request.Valor);
        if (valor <= 0)
        {
            throw new InvalidOperationException("Valor do movimento deve ser maior que zero.");
        }

        if (string.IsNullOrWhiteSpace(request.Motivo) || request.Motivo.Trim().Length < 3)
        {
            throw new InvalidOperationException("Informe o motivo do movimento (mínimo 3 caracteres).");
        }

        if (tipo == TipoMovimentoCaixa.Sangria)
        {
            var caixaAtual = computeExpectedCash(companyId, openSession, now);
            if (valor > caixaAtual)
            {
                throw new InvalidOperationException(
                    $"Sangria maior que o dinheiro em caixa (disponível: {HorusMoneyFormat.Format(caixaAtual)}).");
            }
        }

        // 3. Bloco transacional com proteção de concorrência e integridade referencial
        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var transaction = (SqlTransaction)await db.BeginTransactionAsync(cancellationToken);

        try
        {
            // Checagem com lock exclusivo caso outra transação esteja em andamento para a mesma chave
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                const string checkSql = """
                    SELECT PayloadHash, ResponsePayload
                    FROM ProcessedEvents WITH (UPDLOCK, ROWLOCK)
                    WHERE CompanyId = @CompanyId AND EventId = @EventId;
                    """;

                await using var checkCmd = new SqlCommand(checkSql, db, transaction);
                checkCmd.Parameters.AddWithValue("@CompanyId", companyId);
                checkCmd.Parameters.AddWithValue("@EventId", eventId);

                await using (var reader = await checkCmd.ExecuteReaderAsync(cancellationToken))
                {
                    if (await reader.ReadAsync(cancellationToken))
                    {
                        var storedHash = reader.GetString(0);
                        var responseJson = reader.GetString(1);
                        await reader.CloseAsync();

                        if (!string.Equals(storedHash, currentHash, StringComparison.OrdinalIgnoreCase))
                        {
                            throw new IdempotencyConflictException(
                                eventId,
                                $"Conflito de idempotência: o EventId '{eventId}' já foi processado anteriormente com um payload diferente.");
                        }

                        var cached = JsonSerializer.Deserialize<CaixaStatusDto>(responseJson, JsonOptions)
                            ?? throw new InvalidOperationException("Falha ao recuperar payload de resposta do evento processado.");

                        cached.IsReplay = true;
                        await transaction.CommitAsync();
                        return cached;
                    }
                }
            }

            var movId = $"cxm-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
            const string insertMovSql = """
                INSERT INTO CaixaMovimentos (Id, CompanyId, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName)
                VALUES (@Id, @CompanyId, @CaixaSessaoId, @Tipo, @Valor, @Motivo, @CreatedAt, @OperatorId, @OperatorName);
                """;

            await using (var movCmd = new SqlCommand(insertMovSql, db, transaction))
            {
                movCmd.Parameters.AddWithValue("@Id", movId);
                movCmd.Parameters.AddWithValue("@CompanyId", companyId);
                movCmd.Parameters.AddWithValue("@CaixaSessaoId", openSession.Id);
                movCmd.Parameters.AddWithValue("@Tipo", (byte)tipo);
                movCmd.Parameters.AddWithValue("@Valor", valor);
                movCmd.Parameters.AddWithValue("@Motivo", request.Motivo.Trim());
                movCmd.Parameters.AddWithValue("@CreatedAt", now);
                movCmd.Parameters.AddWithValue("@OperatorId", currentUser.Id);
                movCmd.Parameters.AddWithValue("@OperatorName", currentUser.Name);
                await movCmd.ExecuteNonQueryAsync();
            }

            var eventType = tipo == TipoMovimentoCaixa.Reforco ? AuditEventTypes.CaixaReforco : AuditEventTypes.CaixaSangria;
            var acao = tipo == TipoMovimentoCaixa.Reforco ? "Reforço" : "Sangria";
            const string insertAuditSql = """
                INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
                VALUES (@CompanyId, @UserId, @UserName, @EventType, @EntityType, @EntityId, @Description, @Ip);
                """;

            await using (var auditCmd = new SqlCommand(insertAuditSql, db, transaction))
            {
                auditCmd.Parameters.AddWithValue("@CompanyId", companyId);
                auditCmd.Parameters.AddWithValue("@UserId", currentUser.Id);
                auditCmd.Parameters.AddWithValue("@UserName", currentUser.Name);
                auditCmd.Parameters.AddWithValue("@EventType", eventType);
                auditCmd.Parameters.AddWithValue("@EntityType", "CaixaSessao");
                auditCmd.Parameters.AddWithValue("@EntityId", openSession.Id);
                auditCmd.Parameters.AddWithValue("@Description", $"{acao} de {HorusMoneyFormat.Format(valor)} — {request.Motivo.Trim()}");
                auditCmd.Parameters.AddWithValue("@Ip", (object?)ip ?? DBNull.Value);
                await auditCmd.ExecuteNonQueryAsync();
            }

            // Insere placeholder no ProcessedEvents DENTRO da transação para proteção de
            // concorrência (UPDLOCK acima + unique constraint). O ResponsePayload é atualizado
            // depois do commit, pois statusBuilder abre nova conexão que bloquearia nas linhas
            // ainda travadas por esta transação (deadlock de callback).
            var processedEventId = (string?)null;
            if (!string.IsNullOrWhiteSpace(eventId))
            {
                processedEventId = $"pe-{Guid.NewGuid():N}";
                const string insertEventSql = """
                    INSERT INTO ProcessedEvents
                        (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, ProcessedAt, ResponsePayload)
                    VALUES
                        (@Id, @CompanyId, @EventId, @EventType, @ClientSaleId, @PayloadHash, @ProcessedAt, @ResponsePayload);
                    """;

                await using (var eventCmd = new SqlCommand(insertEventSql, db, transaction))
                {
                    eventCmd.Parameters.AddWithValue("@Id", processedEventId);
                    eventCmd.Parameters.AddWithValue("@CompanyId", companyId);
                    eventCmd.Parameters.AddWithValue("@EventId", eventId);
                    eventCmd.Parameters.AddWithValue("@EventType", "CASH_MOVEMENT");
                    eventCmd.Parameters.AddWithValue("@ClientSaleId", DBNull.Value);
                    eventCmd.Parameters.AddWithValue("@PayloadHash", currentHash);
                    eventCmd.Parameters.AddWithValue("@ProcessedAt", now);
                    eventCmd.Parameters.AddWithValue("@ResponsePayload", "{}"); // placeholder
                    await eventCmd.ExecuteNonQueryAsync();
                }
            }

            await transaction.CommitAsync();

            // Computa o status completo APÓS o commit (statusBuilder abre nova conexão)
            var updatedStatus = statusBuilder(now);
            updatedStatus.IsReplay = false;

            // Atualiza o ResponsePayload com o status real para replays futuros
            if (processedEventId is not null)
            {
                try
                {
                    var responseJson = JsonSerializer.Serialize(updatedStatus, JsonOptions);
                    const string updateSql = "UPDATE ProcessedEvents SET ResponsePayload = @Payload WHERE Id = @Id;";
                    await using var db2 = await connection.OpenConnectionAsync();
                    await using var updateCmd = new SqlCommand(updateSql, db2);
                    updateCmd.Parameters.AddWithValue("@Id", processedEventId);
                    updateCmd.Parameters.AddWithValue("@Payload", responseJson);
                    await updateCmd.ExecuteNonQueryAsync();
                }
                catch
                {
                    // Falha ao atualizar payload não é crítica — o movimento já foi comitado
                    // e o replay retornará o status ao vivo na próxima tentativa.
                }
            }

            return updatedStatus;
        }
        catch (SqlException ex) when (ex.Number is 2627 or 2601)
        {
            // Concorrência: outra requisição com o mesmo (CompanyId, EventId) comitou no mesmo instante
            await transaction.RollbackAsync();

            var replay = await ObterEventoProcessadoAsync(companyId, eventId!, currentHash);
            if (replay is not null)
            {
                return replay;
            }

            throw;
        }
        catch
        {
            await transaction.RollbackAsync();
            throw;
        }
    }

    public async Task<List<CaixaMovimentoAD>> ListarMovimentosAsync(string companyId, string caixaSessaoId, CancellationToken cancellationToken = default)
    {
        const string sql = """
            SELECT Id, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName
            FROM CaixaMovimentos
            WHERE CompanyId = @CompanyId AND CaixaSessaoId = @CaixaSessaoId
            ORDER BY CreatedAt ASC;
            """;

        await using var db = await connection.OpenConnectionAsync(cancellationToken);
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@CompanyId", companyId);
        command.Parameters.AddWithValue("@CaixaSessaoId", caixaSessaoId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var rows = new List<CaixaMovimentoAD>();
        while (await reader.ReadAsync(cancellationToken))
        {
            rows.Add(MapMovimento(reader));
        }

        return rows;
    }

    private static CaixaMovimentoAD MapMovimento(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        CaixaSessaoId = ReadString(reader, "CaixaSessaoId"),
        Tipo = (TipoMovimentoCaixa)reader.GetByte(reader.GetOrdinal("Tipo")),
        Valor = reader.GetDecimal(reader.GetOrdinal("Valor")),
        Motivo = ReadString(reader, "Motivo"),
        CreatedAt = reader.GetDateTimeOffset(reader.GetOrdinal("CreatedAt")),
        OperatorId = ReadString(reader, "OperatorId"),
        OperatorName = ReadString(reader, "OperatorName"),
    };

    private static CaixaSessionAD Map(SqlDataReader reader) => new()
    {
        Id = ReadString(reader, "Id"),
        OpenedAt = reader.GetDateTimeOffset(reader.GetOrdinal("OpenedAt")),
        ClosedAt = reader.IsDBNull(reader.GetOrdinal("ClosedAt"))
            ? null
            : reader.GetDateTimeOffset(reader.GetOrdinal("ClosedAt")),
        OpeningAmount = reader.GetDecimal(reader.GetOrdinal("OpeningAmount")),
        ClosingAmount = reader.GetDecimal(reader.GetOrdinal("ClosingAmount")),
        OperatorId = ReadString(reader, "OperatorId"),
        OperatorName = ReadString(reader, "OperatorName"),
        ClosedById = ReadString(reader, "ClosedById"),
        ClosedByName = ReadString(reader, "ClosedByName"),
        Note = ReadString(reader, "Note"),
        ExpectedCashAmount = ReadNullableDecimal(reader, "ExpectedCashAmount"),
        DifferenceAmount = ReadNullableDecimal(reader, "DifferenceAmount"),
        DifferenceReason = ReadNullableString(reader, "DifferenceReason"),
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

    private static decimal? ReadNullableDecimal(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDecimal(ordinal);
    }
}
