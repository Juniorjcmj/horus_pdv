/*
 * Arquivo: tests/HorusGateway.Tests/PrivateNetworkAccessTests.cs
 * Objetivo: o PDV (página https da internet) chama o Gateway em localhost/LAN. O Chromium faz antes um
 *           preflight com "Access-Control-Request-Private-Network"; o Gateway precisa liberar.
 */
using System.Net;
using Xunit;

namespace HorusGateway.Tests;

public sealed class PrivateNetworkAccessTests : IDisposable
{
    private readonly string _dbPath = Path.Combine(Path.GetTempPath(), $"horus-gw-pna-{Guid.NewGuid():N}.db");

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        foreach (var suffix in new[] { "", "-wal", "-shm" })
        {
            var f = _dbPath + suffix;
            try { if (File.Exists(f)) File.Delete(f); } catch { }
        }
    }

    private static HttpRequestMessage Preflight(bool privateNetwork)
    {
        var request = new HttpRequestMessage(HttpMethod.Options, "/api/gateway/events");
        request.Headers.Add("Origin", "https://pdv.quacksistemas.com.br");
        request.Headers.Add("Access-Control-Request-Method", "POST");
        request.Headers.Add("Access-Control-Request-Headers", "content-type,x-terminal-id,x-terminal-key");
        if (privateNetwork) request.Headers.Add("Access-Control-Request-Private-Network", "true");
        return request;
    }

    [Fact]
    public async Task Preflight_from_public_page_allows_private_network()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var response = await client.SendAsync(Preflight(privateNetwork: true));

        Assert.True(response.IsSuccessStatusCode, $"preflight devolveu {(int)response.StatusCode}");
        Assert.True(response.Headers.TryGetValues("Access-Control-Allow-Private-Network", out var values));
        Assert.Equal("true", Assert.Single(values));
        Assert.True(response.Headers.Contains("Access-Control-Allow-Origin"));
    }

    [Fact]
    public async Task Ordinary_preflight_does_not_get_private_network_header()
    {
        using var factory = new GatewayAppFactory(_dbPath);
        using var client = factory.CreateClient();

        var response = await client.SendAsync(Preflight(privateNetwork: false));

        Assert.NotEqual(HttpStatusCode.InternalServerError, response.StatusCode);
        Assert.False(response.Headers.Contains("Access-Control-Allow-Private-Network"));
    }
}
