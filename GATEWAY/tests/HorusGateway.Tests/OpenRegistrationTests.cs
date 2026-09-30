/*
 * Arquivo: tests/HorusGateway.Tests/OpenRegistrationTests.cs
 * Objetivo: registro aberto (auto-provisionamento) — qualquer número de terminais da mesma empresa
 *           se registra sem token pré-compartilhado e recebe credencial própria na hora. O isolamento
 *           por empresa continua valendo.
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Xunit;

namespace HorusGateway.Tests;

public sealed class OpenRegistrationTests : IDisposable
{
    private readonly string _dbPath;

    public OpenRegistrationTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-open-{Guid.NewGuid():N}.db");
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

    private GatewayAppFactory Factory() => new(_dbPath, requireTerminalAuth: true, openRegistration: true);

    private static object Reg(string terminalId, string companyId = "empresa-1")
        => new { companyId, storeId = "store-001", terminalId, terminalType = "ORDER" }; // sem registrationToken

    [Fact]
    public async Task Terminal_registers_without_token_and_gets_credential()
    {
        using var factory = Factory();
        using var client = factory.CreateClient();

        var resp = await client.PostAsJsonAsync("/api/gateway/register", Reg("PDV-01"));
        Assert.Equal(HttpStatusCode.OK, resp.StatusCode);
        var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        Assert.False(string.IsNullOrWhiteSpace(doc.RootElement.GetProperty("apiKey").GetString()));
    }

    [Fact]
    public async Task Many_terminals_can_register_on_the_same_company()
    {
        using var factory = Factory();
        using var client = factory.CreateClient();

        for (var i = 1; i <= 8; i++)
        {
            var resp = await client.PostAsJsonAsync("/api/gateway/register", Reg($"PDV-{i:00}"));
            Assert.Equal(HttpStatusCode.OK, resp.StatusCode);
        }

        var list = await client.GetFromJsonAsync<JsonElement>("/api/gateway/terminals");
        Assert.Equal(8, list.GetProperty("count").GetInt32());
    }

    [Fact]
    public async Task Open_registration_still_isolates_by_company()
    {
        using var factory = Factory();
        using var client = factory.CreateClient();

        var resp = await client.PostAsJsonAsync("/api/gateway/register", Reg("PDV-X", companyId: "empresa-2"));
        Assert.Equal(HttpStatusCode.Forbidden, resp.StatusCode);
    }

    [Fact]
    public async Task Status_advertises_open_registration()
    {
        using var factory = Factory();
        using var client = factory.CreateClient();

        var status = await client.GetFromJsonAsync<JsonElement>("/api/gateway/status");
        Assert.True(status.GetProperty("openRegistration").GetBoolean());
    }
}
