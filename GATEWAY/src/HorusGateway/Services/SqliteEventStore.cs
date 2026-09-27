/*
 * Arquivo: Services/SqliteEventStore.cs
 * Objetivo: implementação do Event Bus local em SQLite.
 *           - Idempotência: dedup por (CompanyId, EventId); replay quando o PayloadHash coincide,
 *             conflito quando o mesmo EventId chega com PayloadHash diferente.
 *           - Isolamento multi-tenant: CompanyId faz parte da chave lógica e de toda consulta.
 *           - Durabilidade: tudo é gravado em disco (recuperável após reinicialização).
 */
using HorusGateway.Data;
using HorusGateway.Models;
using Microsoft.Data.Sqlite;

namespace HorusGateway.Services;

public sealed class SqliteEventStore : IEventStore
{
    private readonly GatewayDatabase _database;
    private readonly ILogger<SqliteEventStore> _logger;

    public SqliteEventStore(GatewayDatabase database, ILogger<SqliteEventStore> logger)
    {
        _database = database;
        _logger = logger;
    }

    public Task<IngestResult> AppendAsync(IngestEventRequest request, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(request.EventId) ||
            string.IsNullOrWhiteSpace(request.CompanyId) ||
            string.IsNullOrWhiteSpace(request.TerminalId) ||
            string.IsNullOrWhiteSpace(request.EventType))
        {
            return Task.FromResult(IngestResult.Of(IngestOutcome.Invalid,
                message: "eventId, companyId, terminalId e eventType são obrigatórios."));
        }

        var payloadHash = CanonicalPayloadHasher.Compute(request.Payload);
        var payloadJson = request.Payload.ValueKind == System.Text.Json.JsonValueKind.Undefined
            ? "{}"
            : request.Payload.GetRawText();

        using var connection = _database.OpenConnection();
        using var transaction = connection.BeginTransaction();

        // 1. Idempotência: já existe evento com este (CompanyId, EventId)?
        using (var lookup = connection.CreateCommand())
        {
            lookup.Transaction = transaction;
            lookup.CommandText = """
                SELECT PayloadHash FROM GatewayEvents
                WHERE CompanyId = $companyId AND EventId = $eventId
                LIMIT 1;
                """;
            lookup.Parameters.AddWithValue("$companyId", request.CompanyId!);
            lookup.Parameters.AddWithValue("$eventId", request.EventId!);
            var existingHash = lookup.ExecuteScalar() as string;

            if (existingHash is not null)
            {
                transaction.Rollback();
                if (string.Equals(existingHash, payloadHash, StringComparison.Ordinal))
                {
                    _logger.LogInformation("Replay idempotente: EventId={EventId} CompanyId={CompanyId}",
                        request.EventId, request.CompanyId);
                    var existing = ReadByEventId(connection, request.CompanyId!, request.EventId!);
                    return Task.FromResult(IngestResult.Of(IngestOutcome.Replay, existing));
                }

                _logger.LogWarning("Conflito de idempotência: EventId={EventId} CompanyId={CompanyId} (PayloadHash divergente)",
                    request.EventId, request.CompanyId);
                return Task.FromResult(IngestResult.Of(IngestOutcome.Conflict,
                    message: "EventId já registrado com PayloadHash diferente."));
            }
        }

        // 2. Novo evento: persiste durável.
        var createdAt = DateTimeOffset.UtcNow.ToString("o");
        long seq;
        using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO GatewayEvents
                    (EventId, CompanyId, StoreId, TerminalId, EventType, OccurredAt, Payload, PayloadHash, ClientPayloadHash, Status, CreatedAt)
                VALUES
                    ($eventId, $companyId, $storeId, $terminalId, $eventType, $occurredAt, $payload, $payloadHash, $clientHash, $status, $createdAt);
                SELECT last_insert_rowid();
                """;
            insert.Parameters.AddWithValue("$eventId", request.EventId!);
            insert.Parameters.AddWithValue("$companyId", request.CompanyId!);
            insert.Parameters.AddWithValue("$storeId", (object?)request.StoreId ?? string.Empty);
            insert.Parameters.AddWithValue("$terminalId", request.TerminalId!);
            insert.Parameters.AddWithValue("$eventType", request.EventType!);
            insert.Parameters.AddWithValue("$occurredAt", (object?)request.OccurredAt ?? DBNull.Value);
            insert.Parameters.AddWithValue("$payload", payloadJson);
            insert.Parameters.AddWithValue("$payloadHash", payloadHash);
            insert.Parameters.AddWithValue("$clientHash", (object?)request.PayloadHash ?? DBNull.Value);
            insert.Parameters.AddWithValue("$status", GatewayEventStatus.PendingCloud);
            insert.Parameters.AddWithValue("$createdAt", createdAt);
            seq = (long)insert.ExecuteScalar()!;
        }

        transaction.Commit();

        var stored = new GatewayEvent
        {
            Seq = seq,
            EventId = request.EventId!,
            CompanyId = request.CompanyId!,
            StoreId = request.StoreId ?? string.Empty,
            TerminalId = request.TerminalId!,
            EventType = request.EventType!,
            OccurredAt = request.OccurredAt,
            Payload = payloadJson,
            PayloadHash = payloadHash,
            ClientPayloadHash = request.PayloadHash,
            Status = GatewayEventStatus.PendingCloud,
            CreatedAt = createdAt
        };

        _logger.LogInformation("Evento persistido: Seq={Seq} EventId={EventId} Type={EventType} CompanyId={CompanyId}",
            seq, stored.EventId, stored.EventType, stored.CompanyId);

        return Task.FromResult(IngestResult.Of(IngestOutcome.Accepted, stored));
    }

    public Task<IReadOnlyList<GatewayEvent>> GetEventsAsync(string companyId, long afterSeq, int limit, CancellationToken cancellationToken = default)
    {
        var events = new List<GatewayEvent>();
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT Seq, EventId, CompanyId, StoreId, TerminalId, EventType, OccurredAt, Payload, PayloadHash, ClientPayloadHash, Status, CreatedAt, ProcessedAt
            FROM GatewayEvents
            WHERE CompanyId = $companyId AND Seq > $afterSeq
            ORDER BY Seq ASC
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$afterSeq", afterSeq);
        command.Parameters.AddWithValue("$limit", limit);

        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            events.Add(Map(reader));
        }

        return Task.FromResult<IReadOnlyList<GatewayEvent>>(events);
    }

    public Task<long> CountAsync(string companyId, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(1) FROM GatewayEvents WHERE CompanyId = $companyId;";
        command.Parameters.AddWithValue("$companyId", companyId);
        return Task.FromResult(Convert.ToInt64(command.ExecuteScalar()));
    }

    public Task<long> CountPendingCloudAsync(string companyId, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(1) FROM GatewayEvents WHERE CompanyId = $companyId AND Status = $status;";
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$status", GatewayEventStatus.PendingCloud);
        return Task.FromResult(Convert.ToInt64(command.ExecuteScalar()));
    }

    private static GatewayEvent? ReadByEventId(SqliteConnection connection, string companyId, string eventId)
    {
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT Seq, EventId, CompanyId, StoreId, TerminalId, EventType, OccurredAt, Payload, PayloadHash, ClientPayloadHash, Status, CreatedAt, ProcessedAt
            FROM GatewayEvents
            WHERE CompanyId = $companyId AND EventId = $eventId
            LIMIT 1;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$eventId", eventId);
        using var reader = command.ExecuteReader();
        return reader.Read() ? Map(reader) : null;
    }

    private static GatewayEvent Map(SqliteDataReader reader) => new()
    {
        Seq = reader.GetInt64(0),
        EventId = reader.GetString(1),
        CompanyId = reader.GetString(2),
        StoreId = reader.GetString(3),
        TerminalId = reader.GetString(4),
        EventType = reader.GetString(5),
        OccurredAt = reader.IsDBNull(6) ? null : reader.GetString(6),
        Payload = reader.GetString(7),
        PayloadHash = reader.GetString(8),
        ClientPayloadHash = reader.IsDBNull(9) ? null : reader.GetString(9),
        Status = reader.GetString(10),
        CreatedAt = reader.GetString(11),
        ProcessedAt = reader.IsDBNull(12) ? null : reader.GetString(12)
    };
}
