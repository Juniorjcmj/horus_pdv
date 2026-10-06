/*
 * Arquivo: tests/HorusGateway.Tests/CloudConnectionMonitorTests.cs
 * Objetivo: token por loja — o Gateway testa o token com a nuvem (ping) usando uma nuvem falsa:
 *   token válido da mesma empresa → conectado; token de OUTRA empresa → divergência (não conectado);
 *   401 → token inválido/revogado; envia "Authorization: Bearer qgw_..." para a URL certa.
 */
using System.Net;
using System.Text;
using HorusGateway.Configuration;
using HorusGateway.Services;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace HorusGateway.Tests;

public sealed class CloudConnectionMonitorTests
{
    private sealed class FakeClock : IClock
    {
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.Parse("2026-10-06T12:00:00Z");
    }

    private sealed class FakeCloudHandler(HttpStatusCode status, string body) : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            return Task.FromResult(new HttpResponseMessage(status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class FakeHttpClientFactory(HttpMessageHandler handler) : IHttpClientFactory
    {
        public HttpClient CreateClient(string name) => new(handler, disposeHandler: false);
    }

    private static (CloudConnectionMonitor Monitor, CloudConnectionState State, FakeCloudHandler Handler) Build(
        HttpStatusCode status, string body, string localCompanyId = "empresa-1")
    {
        var options = Options.Create(new GatewayOptions
        {
            CompanyId = localCompanyId,
            CloudApiBaseUrl = "https://cloud.test/",
            CloudSyncToken = "qgw_token-de-teste"
        });
        var handler = new FakeCloudHandler(status, body);
        var state = new CloudConnectionState();
        var monitor = new CloudConnectionMonitor(
            new FakeHttpClientFactory(handler),
            state,
            new GatewayIdentity(options),
            new FakeClock(),
            options,
            NullLogger<CloudConnectionMonitor>.Instance);
        return (monitor, state, handler);
    }

    private const string PingEmpresa1 =
        """{"success":true,"message":"ok","data":{"companyId":"empresa-1","storeId":"loja-01","tokenId":"gwt-1"}}""";

    [Fact]
    public async Task Token_valido_da_mesma_empresa_fica_conectado()
    {
        var (monitor, state, handler) = Build(HttpStatusCode.OK, PingEmpresa1);

        await monitor.CheckOnceAsync();

        Assert.True(state.Connected);
        Assert.False(state.CompanyMismatch);
        Assert.Equal("empresa-1", state.CloudCompanyId);
        Assert.Equal("loja-01", state.CloudStoreId);
        Assert.Null(state.LastError);
        Assert.Equal("https://cloud.test/api/GatewayToken/ping", handler.LastRequest!.RequestUri!.ToString());
        Assert.Equal("Bearer", handler.LastRequest.Headers.Authorization!.Scheme);
        Assert.Equal("qgw_token-de-teste", handler.LastRequest.Headers.Authorization.Parameter);
    }

    [Fact]
    public async Task Token_de_outra_empresa_nao_conecta_e_avisa()
    {
        var (monitor, state, _) = Build(HttpStatusCode.OK, PingEmpresa1, localCompanyId: "empresa-2");

        await monitor.CheckOnceAsync();

        Assert.False(state.Connected);
        Assert.True(state.CompanyMismatch);
        Assert.Contains("empresa-1", state.LastError);
    }

    [Fact]
    public async Task Token_revogado_401_registra_falha()
    {
        var (monitor, state, _) = Build(HttpStatusCode.Unauthorized, """{"success":false}""");

        await monitor.CheckOnceAsync();

        Assert.False(state.Connected);
        Assert.Contains("inválido ou revogado", state.LastError);
    }
}
