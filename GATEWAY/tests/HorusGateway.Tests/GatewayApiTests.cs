/*
 * Arquivo: tests/HorusGateway.Tests/GatewayApiTests.cs
 * Objetivo: cobrir os 10 cenários exigidos pelo CHANGE GATEWAY 02:
 *   1) Gateway inicia            2) Health responde       3) Evento é recebido
 *   4) Evento persiste (SQLite)  5) Reenvio = replay       6) Mesmo EventId + hash diferente = conflito
 *   7) CompanyId diferente não é aceito/entregue           8) Evento sobrevive a reinicialização
 *   9) GET recupera eventos      10) Dois terminais publicam/recebem na mesma empresa
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace HorusGateway.Tests;

public sealed class GatewayApiTests : IDisposable
{
    private readonly string _dbPath;

    public GatewayApiTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-test-{Guid.NewGuid():N}.db");
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

    private static object BuildEvent(string eventId, string companyId, string terminalId, object payload, string type = "ORDER_CREATED")
        => new
        {
            eventId,
            companyId,
            storeId = "store-001",
            terminalId,
            eventType = type,
            occurredAt = DateTimeOffset.UtcNow.ToString("o"),
            payload
        };

    // (1) Gateway inicia + (2) Health check responde
    [Fact]
    public async Task Gateway_starts_and_health_endpoints_respond()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var live = await client.GetAsync("/health/live");
        var ready = await client.GetAsync("/health/ready");
        var health = await client.GetAsync("/health");
        var status = await client.GetAsync("/api/gateway/status");

        Assert.Equal(HttpStatusCode.OK, live.StatusCode);
        Assert.Equal(HttpStatusCode.OK, ready.StatusCode);
        Assert.Equal(HttpStatusCode.OK, health.StatusCode);
        Assert.Equal(HttpStatusCode.OK, status.StatusCode);

        var statusDoc = JsonDocument.Parse(await status.Content.ReadAsStringAsync());
        Assert.Equal("empresa-1", statusDoc.RootElement.GetProperty("companyId").GetString());
        Assert.Equal("HorusGateway", statusDoc.RootElement.GetProperty("service").GetString());
    }

    // (3) Evento é recebido + (4) Evento é persistido no SQLite
    [Fact]
    public async Task Event_is_accepted_and_persisted()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var evt = BuildEvent("evt-001", "empresa-1", "PDV-01", new { orderId = "PED-001", items = Array.Empty<object>() });
        var response = await client.PostAsJsonAsync("/api/gateway/events", evt);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var body = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("accepted", body.RootElement.GetProperty("status").GetString());
        Assert.True(body.RootElement.GetProperty("ack").GetBoolean());

        // Persistência confirmada via recuperação.
        var get = await client.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(1, get.GetProperty("count").GetInt32());
        Assert.Equal("evt-001", get.GetProperty("events")[0].GetProperty("eventId").GetString());
    }

    // (5) Mesmo evento reenviado = replay legítimo (sem duplicar)
    [Fact]
    public async Task Same_event_resent_is_treated_as_replay()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var evt = BuildEvent("evt-replay", "empresa-1", "PDV-01", new { orderId = "PED-010" });
        var first = await client.PostAsJsonAsync("/api/gateway/events", evt);
        var second = await client.PostAsJsonAsync("/api/gateway/events", evt);

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.OK, second.StatusCode);

        var secondBody = JsonDocument.Parse(await second.Content.ReadAsStringAsync());
        Assert.Equal("replay", secondBody.RootElement.GetProperty("status").GetString());

        // Não duplicou.
        var get = await client.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(1, get.GetProperty("count").GetInt32());
    }

    // (6) Mesmo EventId com PayloadHash diferente = conflito
    [Fact]
    public async Task Same_eventId_different_payload_returns_conflict()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var a = BuildEvent("evt-conflict", "empresa-1", "PDV-01", new { orderId = "PED-020", total = 10 });
        var b = BuildEvent("evt-conflict", "empresa-1", "PDV-01", new { orderId = "PED-020", total = 999 });

        var first = await client.PostAsJsonAsync("/api/gateway/events", a);
        var second = await client.PostAsJsonAsync("/api/gateway/events", b);

        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, second.StatusCode);

        // Continua com apenas 1 evento (o original).
        var get = await client.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(1, get.GetProperty("count").GetInt32());
    }

    // (7) Evento de CompanyId diferente não é aceito nem entregue
    [Fact]
    public async Task Event_from_other_company_is_rejected_and_not_delivered()
    {
        using var factory = new GatewayAppFactory(_dbPath, companyId: "empresa-1");
        using var client = factory.CreateClient();

        var foreign = BuildEvent("evt-foreign", "empresa-2", "PDV-X1", new { orderId = "PED-999" });
        var response = await client.PostAsJsonAsync("/api/gateway/events", foreign);
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);

        // Recuperar como empresa-1 não traz nada.
        var getOwn = await client.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(0, getOwn.GetProperty("count").GetInt32());

        // Recuperar como empresa-2 é proibido.
        var getForeign = await client.GetAsync("/api/gateway/events?companyId=empresa-2&after=0");
        Assert.Equal(HttpStatusCode.Forbidden, getForeign.StatusCode);
    }

    // (8) Evento permanece após reinicialização do Gateway (mesmo arquivo SQLite, nova instância)
    [Fact]
    public async Task Events_survive_gateway_restart()
    {
        var evt = BuildEvent("evt-durable", "empresa-1", "PDV-02", new { orderId = "PED-500" });

        using (var factory = new GatewayAppFactory(_dbPath))
        using (var client = factory.CreateClient())
        {
            var response = await client.PostAsJsonAsync("/api/gateway/events", evt);
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }

        // "Reinicialização": nova instância do app apontando para o mesmo arquivo.
        using (var factory2 = new GatewayAppFactory(_dbPath))
        using (var client2 = factory2.CreateClient())
        {
            var get = await client2.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
            Assert.Equal(1, get.GetProperty("count").GetInt32());
            Assert.Equal("evt-durable", get.GetProperty("events")[0].GetProperty("eventId").GetString());
        }
    }

    // (9) GET recupera eventos persistidos, respeitando o cursor "after"
    [Fact]
    public async Task Get_events_recovers_persisted_events_with_cursor()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        for (var i = 1; i <= 3; i++)
        {
            var evt = BuildEvent($"evt-cursor-{i}", "empresa-1", "PDV-01", new { orderId = $"PED-{i}" });
            var r = await client.PostAsJsonAsync("/api/gateway/events", evt);
            Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        }

        var all = await client.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(3, all.GetProperty("count").GetInt32());

        var firstSeq = all.GetProperty("events")[0].GetProperty("seq").GetInt64();
        var afterFirst = await client.GetFromJsonAsync<JsonElement>($"/api/gateway/events?companyId=empresa-1&after={firstSeq}");
        Assert.Equal(2, afterFirst.GetProperty("count").GetInt32());
    }

    // (10) Dois terminais publicam/recebem eventos na mesma CompanyId
    [Fact]
    public async Task Two_terminals_publish_and_both_are_recoverable_in_same_company()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var fromPdv1 = BuildEvent("evt-t1", "empresa-1", "PDV-01", new { orderId = "PED-A" });
        var fromPdv2 = BuildEvent("evt-t2", "empresa-1", "PDV-02", new { orderId = "PED-B" });

        Assert.Equal(HttpStatusCode.OK, (await client.PostAsJsonAsync("/api/gateway/events", fromPdv1)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsJsonAsync("/api/gateway/events", fromPdv2)).StatusCode);

        // O caixa (ou qualquer terminal da empresa) recupera os dois pedidos.
        var get = await client.GetFromJsonAsync<JsonElement>("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(2, get.GetProperty("count").GetInt32());

        var terminals = get.GetProperty("events").EnumerateArray()
            .Select(e => e.GetProperty("terminalId").GetString())
            .OrderBy(x => x)
            .ToArray();
        Assert.Equal(new[] { "PDV-01", "PDV-02" }, terminals);
    }
}
