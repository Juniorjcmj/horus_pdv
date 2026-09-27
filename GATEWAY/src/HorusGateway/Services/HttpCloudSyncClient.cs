/*
 * Arquivo: Services/HttpCloudSyncClient.cs
 * Objetivo: envio HTTP de eventos à cloud. Mapeia status HTTP para o desfecho de sincronização:
 *           2xx→Success, 409→Conflict (idempotente), 5xx/timeout/rede→Transient, demais 4xx→Permanent.
 *           Sem URL configurada, a sincronização fica desabilitada (o Gateway opera LAN-only).
 */
using System.Net;
using System.Net.Http.Json;
using HorusGateway.Configuration;
using HorusGateway.Models;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class HttpCloudSyncClient : ICloudSyncClient
{
    private readonly HttpClient _http;
    private readonly GatewayOptions _options;
    private readonly ILogger<HttpCloudSyncClient> _logger;

    public HttpCloudSyncClient(HttpClient http, IOptions<GatewayOptions> options, ILogger<HttpCloudSyncClient> logger)
    {
        _http = http;
        _options = options.Value;
        _logger = logger;
    }

    public bool Enabled => !string.IsNullOrWhiteSpace(_options.CloudSyncUrl);

    public async Task<CloudSyncResult> SendAsync(GatewayEvent ev, CancellationToken cancellationToken = default)
    {
        if (!Enabled)
        {
            return CloudSyncResult.Of(CloudSyncStatus.Transient, "CloudSyncUrl não configurada.");
        }

        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, _options.CloudSyncUrl)
            {
                Content = JsonContent.Create(GatewayEventDto.From(ev))
            };
            if (!string.IsNullOrWhiteSpace(_options.CloudSyncToken))
            {
                request.Headers.TryAddWithoutValidation("Authorization", $"Bearer {_options.CloudSyncToken}");
            }

            using var response = await _http.SendAsync(request, cancellationToken);

            if (response.IsSuccessStatusCode)
            {
                return CloudSyncResult.Of(CloudSyncStatus.Success);
            }
            if (response.StatusCode == HttpStatusCode.Conflict)
            {
                // A cloud já processou este EventId (mesmo hash) — idempotente, considerar sincronizado.
                return CloudSyncResult.Of(CloudSyncStatus.Conflict);
            }
            if ((int)response.StatusCode >= 500)
            {
                return CloudSyncResult.Of(CloudSyncStatus.Transient, $"HTTP {(int)response.StatusCode}");
            }
            return CloudSyncResult.Of(CloudSyncStatus.Permanent, $"HTTP {(int)response.StatusCode}");
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return CloudSyncResult.Of(CloudSyncStatus.Transient, "timeout");
        }
        catch (HttpRequestException ex)
        {
            _logger.LogDebug(ex, "Falha de rede ao sincronizar com a cloud.");
            return CloudSyncResult.Of(CloudSyncStatus.Transient, "rede indisponível");
        }
    }
}
