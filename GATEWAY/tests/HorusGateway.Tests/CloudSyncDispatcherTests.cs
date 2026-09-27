/*
 * Arquivo: tests/HorusGateway.Tests/CloudSyncDispatcherTests.cs
 * Objetivo: cobrir o CHANGE GATEWAY 06 — dispatcher Gateway → Cloud com um client falso:
 *   sucesso → SYNCED_CLOUD; conflito (cloud já tem) → idempotente = sincronizado;
 *   transitório → reagenda com backoff (volta a ficar devido); permanente → FAILED;
 *   desabilitado (sem URL) → não faz nada. Idempotência ponta-a-ponta: EventId preservado.
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

public sealed class CloudSyncDispatcherTests : IDisposable
{
    private sealed class FakeClock : IClock
    {
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.Parse("2026-01-01T00:00:00Z");
        public void Advance(TimeSpan by) => UtcNow = UtcNow.Add(by);
    }

    private sealed class FakeCloudClient : ICloudSyncClient
    {
        public bool Enabled { get; set; } = true;
        public Func<GatewayEvent, CloudSyncResult> Behavior { get; set; } = _ => CloudSyncResult.Of(CloudSyncStatus.Success);
        public List<string> SentEventIds { get; } = new();

        public Task<CloudSyncResult> SendAsync(GatewayEvent ev, CancellationToken cancellationToken = default)
        {
            SentEventIds.Add(ev.EventId);
            return Task.FromResult(Behavior(ev));
        }
    }

    private readonly string _dbPath;
    private readonly FakeClock _clock = new();
    private readonly SqliteEventStore _store;
    private readonly CloudSyncState _state = new();

    public CloudSyncDispatcherTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-cloud-{Guid.NewGuid():N}.db");
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

    private CloudSyncDispatcher BuildDispatcher(FakeCloudClient client)
        => new(_store, client, _state, _clock,
            Options.Create(new GatewayOptions { CompanyId = "empresa-1", CloudSyncUrl = "http://cloud/api/ingest", CloudSyncBatchSize = 50 }),
            NullLogger<CloudSyncDispatcher>.Instance);

    private async Task<long> Seed(string eventId)
    {
        var result = await _store.AppendAsync(new IngestEventRequest
        {
            EventId = eventId,
            CompanyId = "empresa-1",
            TerminalId = "PDV-01",
            EventType = "ORDER_CREATED",
            Payload = JsonDocument.Parse("{\"orderId\":\"PED-1\"}").RootElement.Clone()
        });
        return result.Event!.Seq;
    }

    [Fact]
    public async Task Success_marks_event_synced_and_clears_pending()
    {
        await Seed("evt-1");
        var client = new FakeCloudClient { Behavior = _ => CloudSyncResult.Of(CloudSyncStatus.Success) };

        var summary = await BuildDispatcher(client).RunOnceAsync("empresa-1");

        Assert.Equal(1, summary.Synced);
        Assert.Equal(0, await _store.CountPendingCloudAsync("empresa-1"));
        Assert.Equal(new[] { "evt-1" }, client.SentEventIds); // EventId preservado ponta-a-ponta
        Assert.True(_state.Online);
    }

    [Fact]
    public async Task Conflict_is_treated_as_synced_idempotent()
    {
        await Seed("evt-dup");
        var client = new FakeCloudClient { Behavior = _ => CloudSyncResult.Of(CloudSyncStatus.Conflict) };

        var summary = await BuildDispatcher(client).RunOnceAsync("empresa-1");

        Assert.Equal(1, summary.Synced);
        Assert.Equal(0, await _store.CountPendingCloudAsync("empresa-1"));
    }

    [Fact]
    public async Task Transient_failure_reschedules_then_succeeds_later()
    {
        await Seed("evt-retry");
        var client = new FakeCloudClient { Behavior = _ => CloudSyncResult.Of(CloudSyncStatus.Transient, "cloud fora") };
        var dispatcher = BuildDispatcher(client);

        var first = await dispatcher.RunOnceAsync("empresa-1");
        Assert.Equal(1, first.Retried);
        Assert.Equal(1, await _store.CountPendingCloudAsync("empresa-1")); // continua pendente
        Assert.False(_state.Online);

        // Ainda não é devido (reagendado no futuro): novo ciclo não processa nada.
        var immediate = await dispatcher.RunOnceAsync("empresa-1");
        Assert.Equal(0, immediate.Processed);

        // Cloud volta; passado o backoff, sincroniza.
        client.Behavior = _ => CloudSyncResult.Of(CloudSyncStatus.Success);
        _clock.Advance(TimeSpan.FromMinutes(1));
        var later = await dispatcher.RunOnceAsync("empresa-1");
        Assert.Equal(1, later.Synced);
        Assert.Equal(0, await _store.CountPendingCloudAsync("empresa-1"));
    }

    [Fact]
    public async Task Permanent_failure_marks_failed_and_stops_retrying()
    {
        await Seed("evt-bad");
        var client = new FakeCloudClient { Behavior = _ => CloudSyncResult.Of(CloudSyncStatus.Permanent, "HTTP 400") };
        var dispatcher = BuildDispatcher(client);

        var summary = await dispatcher.RunOnceAsync("empresa-1");
        Assert.Equal(1, summary.Failed);
        Assert.Equal(0, await _store.CountPendingCloudAsync("empresa-1")); // saiu de PENDING_CLOUD

        // Não volta a ser processado.
        _clock.Advance(TimeSpan.FromHours(1));
        Assert.Equal(0, (await dispatcher.RunOnceAsync("empresa-1")).Processed);
    }

    [Fact]
    public async Task Disabled_client_does_nothing()
    {
        await Seed("evt-x");
        var client = new FakeCloudClient { Enabled = false };

        var summary = await BuildDispatcher(client).RunOnceAsync("empresa-1");

        Assert.Equal(0, summary.Processed);
        Assert.Equal(1, await _store.CountPendingCloudAsync("empresa-1"));
    }

    [Fact]
    public async Task Events_are_dispatched_in_sequence_order()
    {
        await Seed("evt-1");
        await Seed("evt-2");
        await Seed("evt-3");
        var client = new FakeCloudClient { Behavior = _ => CloudSyncResult.Of(CloudSyncStatus.Success) };

        await BuildDispatcher(client).RunOnceAsync("empresa-1");

        Assert.Equal(new[] { "evt-1", "evt-2", "evt-3" }, client.SentEventIds);
    }
}
