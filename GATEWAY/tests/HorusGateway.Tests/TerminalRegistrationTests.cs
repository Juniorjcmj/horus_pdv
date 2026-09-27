/*
 * Arquivo: tests/HorusGateway.Tests/TerminalRegistrationTests.cs
 * Objetivo: cobrir o CHANGE GATEWAY 03 — registro/autorização de terminais, heartbeat e
 *           autenticação LAN aplicada à ingestão/recuperação de eventos.
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace HorusGateway.Tests;

public sealed class TerminalRegistrationTests : IDisposable
{
    private const string Token = "test-registration-token";
    private readonly string _dbPath;

    public TerminalRegistrationTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-term-{Guid.NewGuid():N}.db");
    }

    public void Dispose()
    {
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            if (File.Exists(f)) File.Delete(f);
        }
    }

    private static object RegisterBody(string terminalId, string companyId = "empresa-1", string type = "ORDER", string token = Token)
        => new { companyId, storeId = "store-001", terminalId, terminalType = type, registrationToken = token };

    private static async Task<string> RegisterAndGetKey(HttpClient client, string terminalId, string type = "ORDER")
    {
        var resp = await client.PostAsJsonAsync("/api/gateway/register", RegisterBody(terminalId, type: type));
        resp.EnsureSuccessStatusCode();
        var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        return doc.RootElement.GetProperty("apiKey").GetString()!;
    }

    // Registro válido emite credencial e o terminal passa a aparecer na listagem.
    [Fact]
    public async Task Register_with_valid_token_issues_credential()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var resp = await client.PostAsJsonAsync("/api/gateway/register", RegisterBody("PDV-01"));
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);

        var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        Assert.False(string.IsNullOrWhiteSpace(doc.RootElement.GetProperty("apiKey").GetString()));
        Assert.Equal("PDV-01", doc.RootElement.GetProperty("terminalId").GetString());

        var list = await client.GetFromJsonAsync<JsonElement>("/api/gateway/terminals");
        Assert.Equal(1, list.GetProperty("count").GetInt32());
    }

    // Token inválido é recusado (a LAN é rede não confiável).
    [Fact]
    public async Task Register_with_invalid_token_is_rejected()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var resp = await client.PostAsJsonAsync("/api/gateway/register", RegisterBody("PDV-01", token: "errado"));
        Assert.Equal(HttpStatusCode.Unauthorized, resp.StatusCode);
    }

    // Registro para outra empresa é proibido (isolamento multi-tenant).
    [Fact]
    public async Task Register_for_other_company_is_forbidden()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var resp = await client.PostAsJsonAsync("/api/gateway/register", RegisterBody("PDV-X", companyId: "empresa-2"));
        Assert.Equal(HttpStatusCode.Forbidden, resp.StatusCode);
    }

    // Com auth exigida, ingestão sem credencial é recusada e com credencial válida é aceita.
    [Fact]
    public async Task Ingest_requires_valid_terminal_credential_when_auth_enabled()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var apiKey = await RegisterAndGetKey(client, "PDV-01");
        var evt = new
        {
            eventId = "evt-auth-1",
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-01",
            eventType = "ORDER_CREATED",
            payload = new { orderId = "PED-1" }
        };

        // Sem credencial → 401.
        var noAuth = await client.PostAsJsonAsync("/api/gateway/events", evt);
        Assert.Equal(HttpStatusCode.Unauthorized, noAuth.StatusCode);

        // Com credencial válida → 200.
        using var authed = new HttpRequestMessage(HttpMethod.Post, "/api/gateway/events")
        {
            Content = JsonContent.Create(evt)
        };
        authed.Headers.Add("X-Terminal-Id", "PDV-01");
        authed.Headers.Add("X-Terminal-Key", apiKey);
        var ok = await client.SendAsync(authed);
        Assert.Equal(HttpStatusCode.OK, ok.StatusCode);
    }

    // Credencial de um terminal não pode publicar evento em nome de outro terminalId.
    [Fact]
    public async Task Ingest_rejects_when_authenticated_terminal_differs_from_event()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var apiKey = await RegisterAndGetKey(client, "PDV-01");
        var evt = new
        {
            eventId = "evt-spoof",
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-02", // diferente do terminal autenticado
            eventType = "ORDER_CREATED",
            payload = new { orderId = "PED-2" }
        };

        using var req = new HttpRequestMessage(HttpMethod.Post, "/api/gateway/events") { Content = JsonContent.Create(evt) };
        req.Headers.Add("X-Terminal-Id", "PDV-01");
        req.Headers.Add("X-Terminal-Key", apiKey);
        var resp = await client.SendAsync(req);
        Assert.Equal(HttpStatusCode.Forbidden, resp.StatusCode);
    }

    // Heartbeat com credencial válida atualiza LastSeenAt e marca o terminal como online.
    [Fact]
    public async Task Heartbeat_updates_last_seen_and_marks_online()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var apiKey = await RegisterAndGetKey(client, "PDV-01");

        using var hb = new HttpRequestMessage(HttpMethod.Post, "/api/gateway/heartbeat");
        hb.Headers.Add("X-Terminal-Id", "PDV-01");
        hb.Headers.Add("X-Terminal-Key", apiKey);
        var hbResp = await client.SendAsync(hb);
        Assert.Equal(HttpStatusCode.OK, hbResp.StatusCode);

        var list = await client.GetFromJsonAsync<JsonElement>("/api/gateway/terminals");
        var terminal = list.GetProperty("terminals")[0];
        Assert.True(terminal.GetProperty("online").GetBoolean());
        Assert.False(terminal.GetProperty("lastSeenAt").ValueKind == JsonValueKind.Null);
    }

    // Heartbeat com credencial inválida é recusado.
    [Fact]
    public async Task Heartbeat_with_invalid_credential_is_rejected()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();
        await RegisterAndGetKey(client, "PDV-01");

        using var hb = new HttpRequestMessage(HttpMethod.Post, "/api/gateway/heartbeat");
        hb.Headers.Add("X-Terminal-Id", "PDV-01");
        hb.Headers.Add("X-Terminal-Key", "chave-errada");
        var resp = await client.SendAsync(hb);
        Assert.Equal(HttpStatusCode.Unauthorized, resp.StatusCode);
    }

    // Recuperação de eventos também exige credencial quando a auth está ligada.
    [Fact]
    public async Task Get_events_requires_credential_when_auth_enabled()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();
        var apiKey = await RegisterAndGetKey(client, "PDV-01");

        var noAuth = await client.GetAsync("/api/gateway/events?companyId=empresa-1&after=0");
        Assert.Equal(HttpStatusCode.Unauthorized, noAuth.StatusCode);

        using var req = new HttpRequestMessage(HttpMethod.Get, "/api/gateway/events?companyId=empresa-1&after=0");
        req.Headers.Add("X-Terminal-Id", "PDV-01");
        req.Headers.Add("X-Terminal-Key", apiKey);
        var ok = await client.SendAsync(req);
        Assert.Equal(HttpStatusCode.OK, ok.StatusCode);
    }

    // Registro persiste o terminal (sobrevive a "reinicialização" do Gateway).
    [Fact]
    public async Task Registered_terminal_survives_restart()
    {
        string apiKey;
        using (var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true))
        using (var client = factory.CreateClient())
        {
            apiKey = await RegisterAndGetKey(client, "PDV-01");
        }

        // Nova instância, mesmo arquivo: a credencial continua válida.
        using (var factory2 = new GatewayAppFactory(_dbPath, requireTerminalAuth: true))
        using (var client2 = factory2.CreateClient())
        {
            using var hb = new HttpRequestMessage(HttpMethod.Post, "/api/gateway/heartbeat");
            hb.Headers.Add("X-Terminal-Id", "PDV-01");
            hb.Headers.Add("X-Terminal-Key", apiKey);
            var resp = await client2.SendAsync(hb);
            Assert.Equal(HttpStatusCode.OK, resp.StatusCode);
        }
    }
}
