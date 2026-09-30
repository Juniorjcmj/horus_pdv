/*
 * Arquivo: tests/HorusGateway.Tests/UpdateReadinessTests.cs
 * Objetivo: prontidão para atualização controlada (CHANGE GATEWAY 08). Sem eventos pendentes é
 *           seguro atualizar; com eventos ainda não sincronizados NÃO é (evita perder dados presos
 *           apenas no SQLite local). O painel usa este endpoint antes de parar o Gateway.
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace HorusGateway.Tests;

public sealed class UpdateReadinessTests : IDisposable
{
    private readonly string _dbPath;

    public UpdateReadinessTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-upd-{Guid.NewGuid():N}.db");
    }

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            try { if (File.Exists(f)) File.Delete(f); } catch { }
        }
    }

    [Fact]
    public async Task Fresh_gateway_is_safe_to_update()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var resp = await client.GetAsync("/health/update-readiness");
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        Assert.True(doc.RootElement.GetProperty("safeToUpdate").GetBoolean());
        Assert.Equal(0, doc.RootElement.GetProperty("pendingEvents").GetInt32());
        Assert.False(string.IsNullOrWhiteSpace(doc.RootElement.GetProperty("reason").GetString()));
    }

    [Fact]
    public async Task Pending_event_makes_update_unsafe()
    {
        // Sem CloudSyncUrl o sync fica desligado, então um evento ingerido permanece PENDING_CLOUD.
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: false);
        using var client = factory.CreateClient();

        var ingest = await client.PostAsJsonAsync("/api/gateway/events", new
        {
            eventId = Guid.NewGuid().ToString(),
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-01",
            occurredAt = DateTimeOffset.UtcNow.ToString("o"),
            eventType = "ORDER_CREATED",
            payload = new { orderId = "PED-001", items = Array.Empty<object>() },
        });
        Assert.Equal(HttpStatusCode.OK, ingest.StatusCode);

        var resp = await client.GetAsync("/health/update-readiness");
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        Assert.False(doc.RootElement.GetProperty("safeToUpdate").GetBoolean());
        Assert.True(doc.RootElement.GetProperty("pendingEvents").GetInt32() >= 1);
    }
}
