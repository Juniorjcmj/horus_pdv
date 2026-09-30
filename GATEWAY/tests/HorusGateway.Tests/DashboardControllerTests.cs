/*
 * Arquivo: tests/HorusGateway.Tests/DashboardControllerTests.cs
 * Objetivo: testes unitários e de integração para CHANGE GATEWAY 08 (Dashboard de Monitoramento e Update Controlado).
 */
using System.Net;
using System.Text.Json;
using HorusGateway.Models;
using Xunit;

namespace HorusGateway.Tests;

public sealed class DashboardControllerTests : IDisposable
{
    private readonly string _dbPath;

    public DashboardControllerTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-dash-{Guid.NewGuid():N}.db");
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
    public async Task Dashboard_summary_returns_200_and_reflects_gateway_state()
    {
        using var factory = new GatewayAppFactory(_dbPath, "empresa-1", "store-001");
        var client = factory.CreateClient();

        var response = await client.GetAsync("/api/gateway/dashboard");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var root = doc.RootElement;

        Assert.Equal("empresa-1", root.GetProperty("identity").GetProperty("companyId").GetString());
        Assert.Equal("store-001", root.GetProperty("identity").GetProperty("storeId").GetString());
        Assert.True(root.GetProperty("identity").GetProperty("bound").GetBoolean());

        Assert.Equal("healthy", root.GetProperty("health").GetProperty("gateway").GetString());
        Assert.Equal("healthy", root.GetProperty("health").GetProperty("storage").GetString());

        Assert.True(root.GetProperty("updateReadiness").GetProperty("isSafe").GetBoolean());
        Assert.Equal(0, root.GetProperty("updateReadiness").GetProperty("pendingEvents").GetInt64());
    }

    [Fact]
    public async Task Check_safe_update_returns_safe_when_no_pending_events()
    {
        using var factory = new GatewayAppFactory(_dbPath, "empresa-1", "store-001");
        var client = factory.CreateClient();

        var response = await client.GetAsync("/api/gateway/system/update-check");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var root = doc.RootElement;

        Assert.True(root.GetProperty("safe").GetBoolean());
        Assert.Equal(0, root.GetProperty("pendingEvents").GetInt64());
        Assert.Equal("PROCEED", root.GetProperty("actionRecommended").GetString());
    }

    [Fact]
    public async Task Check_safe_update_returns_unsafe_when_pending_events_exist()
    {
        using var factory = new GatewayAppFactory(_dbPath, "empresa-1", "store-001");
        var client = factory.CreateClient();

        // Ingest an event to generate a pending event
        var ingest = await client.PostAsync("/api/gateway/events",
            new StringContent(JsonSerializer.Serialize(new
            {
                eventId = "ev-test-pending",
                companyId = "empresa-1",
                storeId = "store-001",
                terminalId = "PDV-01",
                eventType = "ORDER_CREATED",
                payload = new { orderId = "PED-01" }
            }), System.Text.Encoding.UTF8, "application/json"));
        Assert.Equal(HttpStatusCode.OK, ingest.StatusCode);

        var response = await client.GetAsync("/api/gateway/system/update-check");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var root = doc.RootElement;

        Assert.False(root.GetProperty("safe").GetBoolean());
        Assert.Equal(1, root.GetProperty("pendingEvents").GetInt64());
        Assert.Equal("WAIT_FOR_SYNC", root.GetProperty("actionRecommended").GetString());
    }

    [Fact]
    public async Task Dashboard_html_page_is_served_at_slash_and_dashboard()
    {
        using var factory = new GatewayAppFactory(_dbPath, "empresa-1", "store-001");
        var client = factory.CreateClient();

        var responseRoot = await client.GetAsync("/");
        Assert.Equal(HttpStatusCode.OK, responseRoot.StatusCode);
        Assert.Equal("text/html; charset=utf-8", responseRoot.Content.Headers.ContentType?.ToString());
        var htmlRoot = await responseRoot.Content.ReadAsStringAsync();
        Assert.Contains("Hórus Gateway", htmlRoot);
        Assert.Contains("Verificando prontidão de atualização", htmlRoot);

        var responseDash = await client.GetAsync("/dashboard");
        Assert.Equal(HttpStatusCode.OK, responseDash.StatusCode);
        Assert.Equal("text/html; charset=utf-8", responseDash.Content.Headers.ContentType?.ToString());
    }

    [Fact]
    public async Task Unbound_gateway_returns_503_for_dashboard_and_update_check()
    {
        using var factory = new GatewayAppFactory(_dbPath, companyId: "");
        var client = factory.CreateClient();

        var respDash = await client.GetAsync("/api/gateway/dashboard");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, respDash.StatusCode);

        var respCheck = await client.GetAsync("/api/gateway/system/update-check");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, respCheck.StatusCode);
    }
}
