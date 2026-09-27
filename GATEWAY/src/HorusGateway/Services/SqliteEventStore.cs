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
    private readonly IClock _clock;
    private readonly ILogger<SqliteEventStore> _logger;

    public SqliteEventStore(GatewayDatabase database, IClock clock, ILogger<SqliteEventStore> logger)
    {
        _database = database;
        _clock = clock;
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

        // 1. Idempotência: o ledger ProcessedEvents é a autoridade (espelha o modelo da cloud).
        using (var lookup = connection.CreateCommand())
        {
            lookup.Transaction = transaction;
            lookup.CommandText = """
                SELECT PayloadHash FROM ProcessedEvents
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

        // 2. Novo evento: persiste durável (evento + ledger de idempotência) na mesma transação.
        var createdAt = _clock.UtcNow.ToString("o");
        long seq;
        using (var insert = connection.CreateCommand())
        {
            insert.Transaction = transaction;
            insert.CommandText = """
                INSERT INTO GatewayEvents
                    (EventId, CompanyId, StoreId, TerminalId, EventType, OccurredAt, Payload, PayloadHash, ClientPayloadHash, Status, CreatedAt, RetryCount, NextAttemptAt)
                VALUES
                    ($eventId, $companyId, $storeId, $terminalId, $eventType, $occurredAt, $payload, $payloadHash, $clientHash, $status, $createdAt, 0, $createdAt);
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

        using (var ledger = connection.CreateCommand())
        {
            ledger.Transaction = transaction;
            ledger.CommandText = """
                INSERT INTO ProcessedEvents (CompanyId, EventId, PayloadHash, ProcessedAt)
                VALUES ($companyId, $eventId, $payloadHash, $processedAt);
                """;
            ledger.Parameters.AddWithValue("$companyId", request.CompanyId!);
            ledger.Parameters.AddWithValue("$eventId", request.EventId!);
            ledger.Parameters.AddWithValue("$payloadHash", payloadHash);
            ledger.Parameters.AddWithValue("$processedAt", createdAt);
            ledger.ExecuteNonQuery();
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

    public Task<IReadOnlyList<GatewayEvent>> GetDueForDispatchAsync(string companyId, int limit, CancellationToken cancellationToken = default)
    {
        var now = _clock.UtcNow.ToString("o");
        var events = new List<GatewayEvent>();
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT Seq, EventId, CompanyId, StoreId, TerminalId, EventType, OccurredAt, Payload, PayloadHash, ClientPayloadHash, Status, CreatedAt, ProcessedAt, RetryCount
            FROM GatewayEvents
            WHERE CompanyId = $companyId
              AND Status = $pending
              AND RetryCount < $maxRetries
              AND (NextAttemptAt IS NULL OR NextAttemptAt <= $now)
            ORDER BY Seq ASC
            LIMIT $limit;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$pending", GatewayEventStatus.PendingCloud);
        command.Parameters.AddWithValue("$maxRetries", BackoffPolicy.MaxRetries);
        command.Parameters.AddWithValue("$now", now);
        command.Parameters.AddWithValue("$limit", limit);

        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            var ev = Map(reader);
            ev.RetryCount = reader.GetInt32(13);
            events.Add(ev);
        }

        return Task.FromResult<IReadOnlyList<GatewayEvent>>(events);
    }

    public Task RecordDispatchFailureAsync(long seq, string error, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();

        int retryCount;
        using (var read = connection.CreateCommand())
        {
            read.CommandText = "SELECT RetryCount FROM GatewayEvents WHERE Seq = $seq;";
            read.Parameters.AddWithValue("$seq", seq);
            var raw = read.ExecuteScalar();
            if (raw is null) return Task.CompletedTask;
            retryCount = Convert.ToInt32(raw) + 1;
        }

        var now = _clock.UtcNow;
        var exhausted = BackoffPolicy.Exhausted(retryCount);
        var nextAttempt = exhausted ? (string?)null : now.Add(BackoffPolicy.NextDelay(retryCount)).ToString("o");
        var status = exhausted ? GatewayEventStatus.Failed : GatewayEventStatus.PendingCloud;

        using var update = connection.CreateCommand();
        update.CommandText = """
            UPDATE GatewayEvents
            SET RetryCount = $retryCount,
                LastAttemptAt = $now,
                LastError = $error,
                NextAttemptAt = $nextAttempt,
                Status = $status
            WHERE Seq = $seq;
            """;
        update.Parameters.AddWithValue("$retryCount", retryCount);
        update.Parameters.AddWithValue("$now", now.ToString("o"));
        update.Parameters.AddWithValue("$error", (object?)error ?? DBNull.Value);
        update.Parameters.AddWithValue("$nextAttempt", (object?)nextAttempt ?? DBNull.Value);
        update.Parameters.AddWithValue("$status", status);
        update.Parameters.AddWithValue("$seq", seq);
        update.ExecuteNonQuery();

        if (exhausted)
        {
            _logger.LogWarning("Evento Seq={Seq} atingiu o limite de tentativas ({Max}) e foi marcado como FAILED.",
                seq, BackoffPolicy.MaxRetries);
        }

        return Task.CompletedTask;
    }

    public Task MarkSyncedAsync(long seq, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE GatewayEvents
            SET Status = $status, ProcessedAt = $now, LastError = NULL, NextAttemptAt = NULL
            WHERE Seq = $seq;
            """;
        command.Parameters.AddWithValue("$status", GatewayEventStatus.SyncedCloud);
        command.Parameters.AddWithValue("$now", _clock.UtcNow.ToString("o"));
        command.Parameters.AddWithValue("$seq", seq);
        command.ExecuteNonQuery();
        return Task.CompletedTask;
    }

    public Task<bool> ExistsAsync(string companyId, string eventId, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(1) FROM ProcessedEvents WHERE CompanyId = $companyId AND EventId = $eventId;";
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$eventId", eventId);
        return Task.FromResult(Convert.ToInt64(command.ExecuteScalar()) > 0);
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
