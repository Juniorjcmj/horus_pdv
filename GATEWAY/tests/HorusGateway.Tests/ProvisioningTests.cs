/*
 * Arquivo: tests/HorusGateway.Tests/ProvisioningTests.cs
 * Objetivo: pré-autorização de terminais pelo administrador + auto-identificação.
 *   - Web: provisiona por token e o terminal se identifica por token (sem config prévia no terminal).
 *   - Store: identificação por IP (o TestServer não expõe IP de origem, então testa-se no store direto).
 */
using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class ProvisioningTests : IDisposable
{
    private readonly string _dbPath;

    public ProvisioningTests()
    {
        _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-prov-{Guid.NewGuid():N}.db");
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

    // --- Web: provisionamento por token + auto-identificação por token ---

    [Fact]
    public async Task Admin_provisions_terminal_then_terminal_identifies_by_token()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        // Admin pré-autoriza o terminal com um token de provisionamento.
        var provision = await client.PostAsJsonAsync("/api/gateway/terminals/provision", new
        {
            companyId = "empresa-1",
            storeId = "store-001",
            terminalId = "PDV-01",
            terminalType = "ORDER",
            provisionToken = "token-do-pdv-01",
        });
        Assert.Equal(HttpStatusCode.OK, provision.StatusCode);

        // Terminal se identifica apresentando o token — recebe credencial sem config prévia.
        var identify = await client.PostAsJsonAsync("/api/gateway/identify", new { provisionToken = "token-do-pdv-01" });
        Assert.Equal(HttpStatusCode.OK, identify.StatusCode);
        var doc = JsonDocument.Parse(await identify.Content.ReadAsStringAsync());
        Assert.Equal("PDV-01", doc.RootElement.GetProperty("terminalId").GetString());
        Assert.False(string.IsNullOrWhiteSpace(doc.RootElement.GetProperty("apiKey").GetString()));
    }

    [Fact]
    public async Task Identify_with_unknown_token_is_rejected()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var identify = await client.PostAsJsonAsync("/api/gateway/identify", new { provisionToken = "nao-existe" });
        Assert.Equal(HttpStatusCode.Unauthorized, identify.StatusCode);
    }

    [Fact]
    public async Task Provision_for_other_company_is_forbidden()
    {
        using var factory = new GatewayAppFactory(_dbPath, requireTerminalAuth: true);
        using var client = factory.CreateClient();

        var provision = await client.PostAsJsonAsync("/api/gateway/terminals/provision", new
        {
            companyId = "empresa-2",
            terminalId = "PDV-X",
        });
        Assert.Equal(HttpStatusCode.Forbidden, provision.StatusCode);
    }

    // --- Store: auto-identificação por IP de origem ---

    [Fact]
    public async Task Terminal_is_identified_by_source_ip()
    {
        var options = Options.Create(new GatewayOptions { CompanyId = "empresa-1", StoreId = "store-001", DatabasePath = _dbPath });
        var database = new GatewayDatabase(options, NullLogger<GatewayDatabase>.Instance);
        database.Initialize();
        var identity = new GatewayIdentity(options);
        var store = new SqliteTerminalStore(database, identity, options, NullLogger<SqliteTerminalStore>.Instance);

        await store.ProvisionAsync(new ProvisionTerminalRequest
        {
            CompanyId = "empresa-1",
            StoreId = "store-001",
            TerminalId = "PDV-CAIXA",
            TerminalType = "CASH",
            AllowedIp = "192.168.0.50",
        }, "gw_test");

        // IP correto → identifica e devolve credencial.
        var ok = await store.IdentifyAsync("192.168.0.50", null, "gw_test");
        Assert.NotNull(ok);
        Assert.Equal("PDV-CAIXA", ok!.TerminalId);
        Assert.False(string.IsNullOrWhiteSpace(ok.ApiKey));

        // IP mapeado IPv6 do mesmo endereço também identifica.
        var mapped = await store.IdentifyAsync("::ffff:192.168.0.50", null, "gw_test");
        Assert.NotNull(mapped);

        // IP desconhecido → não identifica.
        var unknown = await store.IdentifyAsync("10.0.0.9", null, "gw_test");
        Assert.Null(unknown);
    }

    [Fact]
    public async Task Provisioned_terminal_appears_in_listing_with_ip()
    {
        var options = Options.Create(new GatewayOptions { CompanyId = "empresa-1", DatabasePath = _dbPath });
        var database = new GatewayDatabase(options, NullLogger<GatewayDatabase>.Instance);
        database.Initialize();
        var identity = new GatewayIdentity(options);
        var store = new SqliteTerminalStore(database, identity, options, NullLogger<SqliteTerminalStore>.Instance);

        await store.ProvisionAsync(new ProvisionTerminalRequest
        {
            CompanyId = "empresa-1",
            TerminalId = "PDV-02",
            AllowedIp = "192.168.0.51",
        }, "gw_test");

        var list = await store.ListAsync("empresa-1");
        Assert.Single(list);
        Assert.Equal("192.168.0.51", list[0].AllowedIp);
        Assert.True(list[0].Provisioned);
    }
}
