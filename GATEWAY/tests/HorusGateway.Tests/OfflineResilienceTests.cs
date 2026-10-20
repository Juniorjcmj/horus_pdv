/*
 * Arquivo: tests/HorusGateway.Tests/OfflineResilienceTests.cs
 * Objetivo: change offline-caixa-resiliencia no Gateway — migração de eventos abandonados por versão anterior,
 *           cadeia de caixa por terminal, painel que não declara "seguro" com FAILED, e resolução (justificativa)
 *           de CASH_CLOSE recusado.
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class OfflineResilienceTests : IDisposable
{
    private sealed class FakeClock : IClock
    {
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.Parse("2026-01-01T00:00:00Z");
    }

    private readonly string _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-offline-{Guid.NewGuid():N}.db");

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            try { if (File.Exists(_dbPath + suffix)) File.Delete(_dbPath + suffix); } catch { }
        }
    }

    private (GatewayDatabase Db, SqliteEventStore Store) Build()
    {
        var options = Options.Create(new GatewayOptions { CompanyId = "empresa-1", DatabasePath = _dbPath });
        var db = new GatewayDatabase(options, NullLogger<GatewayDatabase>.Instance);
        db.Initialize();
        return (db, new SqliteEventStore(db, new FakeClock(), NullLogger<SqliteEventStore>.Instance));
    }

    private static IngestEventRequest Ev(string id, string type, string terminal = "PDV-01", string payload = "{\"operatorId\":\"u1\"}") => new()
    {
        EventId = id,
        CompanyId = "empresa-1",
        StoreId = "store-001",
        TerminalId = terminal,
        EventType = type,
        Payload = JsonDocument.Parse(payload).RootElement.Clone()
    };

    [Fact]
    public async Task Startup_requeues_only_transient_failed_events()
    {
        var (db, store) = Build();
        var net = (await store.AppendAsync(Ev("e-net", "SALE_CREATED"))).Event!.Seq;
        var timeout = (await store.AppendAsync(Ev("e-timeout", "SALE_CREATED"))).Event!.Seq;
        var http503 = (await store.AppendAsync(Ev("e-503", "SALE_CREATED"))).Event!.Seq;
        var business = (await store.AppendAsync(Ev("e-400", "CASH_CLOSE"))).Event!.Seq;
        await store.MarkFailedAsync(net, "rede indisponível");
        await store.MarkFailedAsync(timeout, "timeout");
        await store.MarkFailedAsync(http503, "HTTP 503: indisponível");
        await store.MarkFailedAsync(business, "HTTP 400: Informe uma justificativa antes de fechar o caixa");

        db.Initialize(); // simula reinício do Gateway atualizado

        var failed = await store.GetFailedAsync("empresa-1", 10);
        var only = Assert.Single(failed);
        Assert.Equal(business, only.Seq);
        Assert.Equal(3, await store.CountPendingCloudAsync("empresa-1"));
    }

    [Fact]
    public async Task Cash_event_is_held_behind_failed_cash_event_of_same_terminal_but_sales_are_not()
    {
        var (_, store) = Build();
        var close = (await store.AppendAsync(Ev("c-close", "CASH_CLOSE"))).Event!.Seq;
        await store.AppendAsync(Ev("c-open", "CASH_OPEN"));
        await store.AppendAsync(Ev("s-1", "SALE_CREATED"));
        await store.AppendAsync(Ev("c-open-other", "CASH_OPEN", terminal: "PDV-02"));
        await store.MarkFailedAsync(close, "HTTP 400: diferença");

        var due = (await store.GetDueForDispatchAsync("empresa-1", 20)).Select(e => e.EventId).ToList();

        Assert.DoesNotContain("c-open", due);
        Assert.Contains("s-1", due);
        Assert.Contains("c-open-other", due);
    }

    [Fact]
    public async Task Resolve_updates_payload_and_requeues_same_event_only_for_failed_cash_close()
    {
        var (_, store) = Build();
        var close = (await store.AppendAsync(Ev("c-close", "CASH_CLOSE", payload: "{\"operatorId\":\"u1\",\"closingAmount\":\"10,00\",\"payloadHash\":\"old\"}"))).Event!.Seq;
        var sale = (await store.AppendAsync(Ev("s-1", "SALE_CREATED"))).Event!.Seq;
        await store.MarkFailedAsync(close, "HTTP 400: diferença");
        await store.MarkFailedAsync(sale, "HTTP 400: x");

        Assert.False(await store.ResolveFailedCashCloseAsync("empresa-1", sale, "motivo"));
        Assert.True(await store.ResolveFailedCashCloseAsync("empresa-1", close, "Quebra de caixa"));

        var due = await store.GetDueForDispatchAsync("empresa-1", 10);
        var ev = Assert.Single(due, e => e.Seq == close);
        Assert.Equal("c-close", ev.EventId);
        using var doc = JsonDocument.Parse(ev.Payload);
        Assert.Equal("Quebra de caixa", doc.RootElement.GetProperty("differenceReason").GetString());
        Assert.False(doc.RootElement.TryGetProperty("payloadHash", out _));
        Assert.Null(ev.ClientPayloadHash);
    }

    [Fact]
    public async Task Dashboard_is_not_safe_with_only_failed_events_and_lists_them()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        var client = factory.CreateClient();
        var store = factory.Services.GetRequiredService<IEventStore>();
        var seq = (await store.AppendAsync(Ev("c-close", "CASH_CLOSE"))).Event!.Seq;
        await store.MarkFailedAsync(seq, "HTTP 400: Informe uma justificativa");

        using var summary = JsonDocument.Parse(await client.GetStringAsync("/api/gateway/dashboard"));
        var readiness = summary.RootElement.GetProperty("updateReadiness");
        Assert.False(readiness.GetProperty("isSafe").GetBoolean());
        Assert.Equal(0, readiness.GetProperty("pendingEvents").GetInt64());
        Assert.Equal(1, readiness.GetProperty("failedEvents").GetInt64());
        Assert.Equal("RESOLVE_FAILED", readiness.GetProperty("actionRecommended").GetString());

        using var check = JsonDocument.Parse(await client.GetStringAsync("/api/gateway/system/update-check"));
        Assert.False(check.RootElement.GetProperty("safe").GetBoolean());

        using var list = JsonDocument.Parse(await client.GetStringAsync("/api/gateway/dashboard/failed-events"));
        var item = Assert.Single(list.RootElement.EnumerateArray());
        Assert.Contains("justificativa", item.GetProperty("lastError").GetString());
    }

    [Fact]
    public async Task Resolve_endpoint_requeues_failed_cash_close_and_rejects_empty_reason()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        var client = factory.CreateClient();
        var store = factory.Services.GetRequiredService<IEventStore>();
        var seq = (await store.AppendAsync(Ev("c-close", "CASH_CLOSE"))).Event!.Seq;
        await store.MarkFailedAsync(seq, "HTTP 400: diferença");

        var empty = await client.PostAsJsonAsync($"/api/gateway/events/{seq}/resolve", new { differenceReason = " " });
        Assert.Equal(HttpStatusCode.BadRequest, empty.StatusCode);

        var ok = await client.PostAsJsonAsync($"/api/gateway/events/{seq}/resolve", new { differenceReason = "Troco errado" });
        Assert.Equal(HttpStatusCode.OK, ok.StatusCode);
        Assert.Equal(1, await store.CountPendingCloudAsync("empresa-1"));

        var again = await client.PostAsJsonAsync($"/api/gateway/events/{seq}/resolve", new { differenceReason = "Troco errado" });
        Assert.Equal(HttpStatusCode.NotFound, again.StatusCode);
    }
}
