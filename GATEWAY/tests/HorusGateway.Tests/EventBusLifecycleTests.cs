/*
 * Arquivo: tests/HorusGateway.Tests/EventBusLifecycleTests.cs
 * Objetivo: cobrir o CHANGE GATEWAY 04 — ledger ProcessedEvents e o ciclo de vida de reprocessamento
 *           (seleção de eventos devidos, reagendamento por backoff, esgotamento → FAILED, sincronização).
 *           Constrói o SqliteEventStore diretamente com um relógio falso (determinístico).
 */
using System.Text.Json;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class EventBusLifecycleTests : IDisposable
{
    private sealed class FakeClock : IClock
    {
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.Parse("2026-01-01T00:00:00Z");
        public void Advance(TimeSpan by) => UtcNow = UtcNow.Add(by);
    }

    private readonly string _dbPath;
    private readonly FakeClock _clock = new();
    private readonly SqliteEventStore _store;

    public EventBusLifecycleTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-bus-{Guid.NewGuid():N}.db");
        var options = Options.Create(new GatewayOptions { CompanyId = "empresa-1", DatabasePath = _dbPath });
        var database = new GatewayDatabase(options, NullLogger<GatewayDatabase>.Instance);
        database.Initialize();
        _store = new SqliteEventStore(database, _clock, NullLogger<SqliteEventStore>.Instance);
    }

    public void Dispose()
    {
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            if (File.Exists(f)) File.Delete(f);
        }
    }

    private static IngestEventRequest Event(string eventId, string payloadJson = "{\"orderId\":\"PED-1\"}")
        => new()
        {
            EventId = eventId,
            CompanyId = "empresa-1",
            StoreId = "store-001",
            TerminalId = "PDV-01",
            EventType = "ORDER_CREATED",
            Payload = JsonDocument.Parse(payloadJson).RootElement.Clone()
        };

    [Fact]
    public async Task Append_populates_processed_events_ledger_and_dedups()
    {
        var first = await _store.AppendAsync(Event("evt-1"));
        Assert.Equal(IngestOutcome.Accepted, first.Outcome);

        var replay = await _store.AppendAsync(Event("evt-1"));
        Assert.Equal(IngestOutcome.Replay, replay.Outcome);

        var conflict = await _store.AppendAsync(Event("evt-1", "{\"orderId\":\"OUTRO\"}"));
        Assert.Equal(IngestOutcome.Conflict, conflict.Outcome);
    }

    [Fact]
    public async Task New_event_is_due_for_dispatch_immediately()
    {
        await _store.AppendAsync(Event("evt-due"));
        var due = await _store.GetDueForDispatchAsync("empresa-1", 10);
        Assert.Single(due);
        Assert.Equal("evt-due", due[0].EventId);
    }

    [Fact]
    public async Task Dispatch_failure_reschedules_via_backoff_then_becomes_due_again()
    {
        var accepted = await _store.AppendAsync(Event("evt-retry"));
        var seq = accepted.Event!.Seq;

        await _store.RecordDispatchFailureAsync(seq, "cloud indisponível");

        // Logo após a falha, o evento NÃO está devido (reagendado no futuro).
        var dueNow = await _store.GetDueForDispatchAsync("empresa-1", 10);
        Assert.Empty(dueNow);

        // Passado o tempo de backoff, volta a ficar devido.
        _clock.Advance(TimeSpan.FromMinutes(1));
        var dueLater = await _store.GetDueForDispatchAsync("empresa-1", 10);
        Assert.Single(dueLater);
        Assert.Equal(1, dueLater[0].RetryCount);
    }

    [Fact]
    public async Task Event_becomes_FAILED_after_max_retries_and_is_no_longer_due()
    {
        var accepted = await _store.AppendAsync(Event("evt-exhaust"));
        var seq = accepted.Event!.Seq;

        for (var i = 0; i < BackoffPolicy.MaxRetries; i++)
        {
            await _store.RecordDispatchFailureAsync(seq, "falha");
            _clock.Advance(TimeSpan.FromMinutes(10));
        }

        Assert.Equal(0, await _store.CountPendingCloudAsync("empresa-1"));
        var due = await _store.GetDueForDispatchAsync("empresa-1", 10);
        Assert.Empty(due);
    }

    [Fact]
    public async Task Mark_synced_clears_pending_and_dispatch_queue()
    {
        var accepted = await _store.AppendAsync(Event("evt-sync"));
        var seq = accepted.Event!.Seq;

        Assert.Equal(1, await _store.CountPendingCloudAsync("empresa-1"));

        await _store.MarkSyncedAsync(seq);

        Assert.Equal(0, await _store.CountPendingCloudAsync("empresa-1"));
        Assert.Empty(await _store.GetDueForDispatchAsync("empresa-1", 10));
    }
}
