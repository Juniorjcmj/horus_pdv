/*
 * Arquivo: Services/SqliteOrderStore.cs
 * Objetivo: projeção de pedidos em SQLite, derivada dos eventos ORDER_*.
 *           Aplica a máquina de estados (transições válidas), versiona cada mudança e nunca altera
 *           estado sem um evento correspondente. Isolada por CompanyId.
 */
using System.Text.Json;
using HorusGateway.Data;
using HorusGateway.Models;
using Microsoft.Data.Sqlite;

namespace HorusGateway.Services;

public sealed class SqliteOrderStore : IOrderStore
{
    private readonly GatewayDatabase _database;
    private readonly IClock _clock;
    private readonly ILogger<SqliteOrderStore> _logger;

    public SqliteOrderStore(GatewayDatabase database, IClock clock, ILogger<SqliteOrderStore> logger)
    {
        _database = database;
        _clock = clock;
        _logger = logger;
    }

    public Task<string?> GetStatusAsync(string companyId, string orderNumber, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT Status FROM Orders WHERE CompanyId = $companyId AND OrderNumber = $orderNumber LIMIT 1;";
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$orderNumber", orderNumber);
        return Task.FromResult(command.ExecuteScalar() as string);
    }

    public Task<OrderApplyResult> ApplyAsync(GatewayEvent ev, CancellationToken cancellationToken = default)
    {
        var orderNumber = IOrderStore.ReadOrderNumber(ev.Payload);
        if (string.IsNullOrWhiteSpace(orderNumber))
        {
            return Task.FromResult(OrderApplyResult.Of(OrderApplyOutcome.MissingOrderNumber,
                message: "payload do evento ORDER_* não contém orderNumber/orderId."));
        }

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        var current = ReadStatus(connection, transaction, ev.CompanyId, orderNumber);

        if (!OrderStateMachine.TryNext(current, ev.EventType, out var nextStatus))
        {
            transaction.Rollback();
            return Task.FromResult(OrderApplyResult.Of(OrderApplyOutcome.InvalidTransition,
                message: $"transição inválida: {current ?? "(inexistente)"} + {ev.EventType}."));
        }

        var now = _clock.UtcNow.ToString("o");
        var total = ReadTotal(ev.Payload);

        if (current is null)
        {
            using var insert = connection.CreateCommand();
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO Orders (CompanyId, OrderNumber, StoreId, TerminalId, Status, TotalAmount, Payload, Version, CreatedAt, UpdatedAt, LastEventId)
                VALUES ($companyId, $orderNumber, $storeId, $terminalId, $status, $total, $payload, 1, $createdAt, $updatedAt, $lastEventId);
                """;
            insert.Parameters.AddWithValue("$companyId", ev.CompanyId);
            insert.Parameters.AddWithValue("$orderNumber", orderNumber);
            insert.Parameters.AddWithValue("$storeId", ev.StoreId ?? string.Empty);
            insert.Parameters.AddWithValue("$terminalId", ev.TerminalId ?? string.Empty);
            insert.Parameters.AddWithValue("$status", nextStatus);
            insert.Parameters.AddWithValue("$total", (object?)total ?? DBNull.Value);
            insert.Parameters.AddWithValue("$payload", ev.Payload);
            insert.Parameters.AddWithValue("$createdAt", ev.CreatedAt);
            insert.Parameters.AddWithValue("$updatedAt", now);
            insert.Parameters.AddWithValue("$lastEventId", ev.EventId);
            insert.ExecuteNonQuery();
        }
        else
        {
            using var update = connection.CreateCommand();
            update.Transaction = transaction;
            // ORDER_UPDATED e criação atualizam o payload/total; transições de estado mantêm o snapshot atual.
            var refreshPayload = ev.EventType == OrderEventType.Updated;
            update.CommandText = $"""
                UPDATE Orders
                SET Status = $status,
                    Version = Version + 1,
                    UpdatedAt = $updatedAt,
                    LastEventId = $lastEventId
                    {(refreshPayload ? ", Payload = $payload, TotalAmount = COALESCE($total, TotalAmount)" : string.Empty)}
                WHERE CompanyId = $companyId AND OrderNumber = $orderNumber;
                """;
            update.Parameters.AddWithValue("$status", nextStatus);
            update.Parameters.AddWithValue("$updatedAt", now);
            update.Parameters.AddWithValue("$lastEventId", ev.EventId);
            update.Parameters.AddWithValue("$companyId", ev.CompanyId);
            update.Parameters.AddWithValue("$orderNumber", orderNumber);
            if (refreshPayload)
            {
                update.Parameters.AddWithValue("$payload", ev.Payload);
                update.Parameters.AddWithValue("$total", (object?)total ?? DBNull.Value);
            }
            update.ExecuteNonQuery();
        }

        transaction.Commit();

        _logger.LogInformation("Pedido {OrderNumber} → {Status} (evento {EventType}, CompanyId={CompanyId})",
            orderNumber, nextStatus, ev.EventType, ev.CompanyId);

        var view = Get(connection, ev.CompanyId, orderNumber);
        return Task.FromResult(OrderApplyResult.Of(OrderApplyOutcome.Applied, view));
    }

    public Task<OrderView?> GetAsync(string companyId, string orderNumber, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        return Task.FromResult(Get(connection, companyId, orderNumber));
    }

    public Task<IReadOnlyList<OrderView>> ListAsync(string companyId, string? status, CancellationToken cancellationToken = default)
    {
        var orders = new List<OrderView>();
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        var filterStatus = !string.IsNullOrWhiteSpace(status) && status != "all";
        command.CommandText = $"""
            SELECT CompanyId, OrderNumber, StoreId, TerminalId, Status, TotalAmount, Payload, Version, CreatedAt, UpdatedAt, LastEventId
            FROM Orders
            WHERE CompanyId = $companyId {(filterStatus ? "AND Status = $status" : string.Empty)}
            ORDER BY UpdatedAt DESC;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);
        if (filterStatus) command.Parameters.AddWithValue("$status", status!.Trim().ToUpperInvariant());

        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            orders.Add(Map(reader));
        }

        return Task.FromResult<IReadOnlyList<OrderView>>(orders);
    }

    private static string? ReadStatus(SqliteConnection connection, SqliteTransaction transaction, string companyId, string orderNumber)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = "SELECT Status FROM Orders WHERE CompanyId = $companyId AND OrderNumber = $orderNumber LIMIT 1;";
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$orderNumber", orderNumber);
        return command.ExecuteScalar() as string;
    }

    private static OrderView? Get(SqliteConnection connection, string companyId, string orderNumber)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT CompanyId, OrderNumber, StoreId, TerminalId, Status, TotalAmount, Payload, Version, CreatedAt, UpdatedAt, LastEventId
            FROM Orders WHERE CompanyId = $companyId AND OrderNumber = $orderNumber LIMIT 1;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$orderNumber", orderNumber);
        using var reader = command.ExecuteReader();
        return reader.Read() ? Map(reader) : null;
    }

    private static OrderView Map(SqliteDataReader reader)
    {
        var payloadRaw = reader.GetString(6);
        JsonElement payload;
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(payloadRaw) ? "{}" : payloadRaw);
            payload = doc.RootElement.Clone();
        }
        catch
        {
            using var doc = JsonDocument.Parse("{}");
            payload = doc.RootElement.Clone();
        }

        return new OrderView
        {
            CompanyId = reader.GetString(0),
            OrderNumber = reader.GetString(1),
            StoreId = reader.GetString(2),
            TerminalId = reader.GetString(3),
            Status = reader.GetString(4),
            TotalAmount = reader.IsDBNull(5) ? null : reader.GetString(5),
            Payload = payload,
            Version = reader.GetInt32(7),
            CreatedAt = reader.GetString(8),
            UpdatedAt = reader.GetString(9),
            LastEventId = reader.GetString(10)
        };
    }

    private static string? ReadTotal(string payloadJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(payloadJson) ? "{}" : payloadJson);
            foreach (var key in new[] { "totalAmount", "total" })
            {
                if (doc.RootElement.TryGetProperty(key, out var prop))
                {
                    return prop.ValueKind == JsonValueKind.String ? prop.GetString() : prop.GetRawText();
                }
            }
        }
        catch (JsonException)
        {
        }
        return null;
    }
}
