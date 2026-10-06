/*
 * Arquivo: Services/CloudConnectionMonitor.cs
 * Objetivo: testa o token da loja com a nuvem (GET {CloudApiBaseUrl}/api/GatewayToken/ping) ao iniciar e
 *           periodicamente, e guarda o resultado em CloudConnectionState (exposto em /api/gateway/cloud).
 *           Detecta token inválido/revogado e token de OUTRA empresa (CompanyId diferente do configurado).
 */
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using HorusGateway.Configuration;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

/// <summary>Estado da conexão Gateway → nuvem (último teste do token).</summary>
public sealed class CloudConnectionState
{
    private readonly object _lock = new();

    public bool Configured { get; private set; }
    public bool Connected { get; private set; }
    public string? CloudCompanyId { get; private set; }
    public string? CloudStoreId { get; private set; }
    public bool CompanyMismatch { get; private set; }
    public DateTimeOffset? LastCheckAt { get; private set; }
    public DateTimeOffset? LastSuccessAt { get; private set; }
    public string? LastError { get; private set; }

    public void SetNotConfigured(string reason)
    {
        lock (_lock)
        {
            Configured = false;
            Connected = false;
            LastError = reason;
        }
    }

    public void RecordSuccess(DateTimeOffset now, string? companyId, string? storeId, bool mismatch)
    {
        lock (_lock)
        {
            Configured = true;
            Connected = !mismatch;
            CloudCompanyId = companyId;
            CloudStoreId = storeId;
            CompanyMismatch = mismatch;
            LastCheckAt = now;
            if (!mismatch) LastSuccessAt = now;
            LastError = mismatch
                ? $"O token é da empresa '{companyId}', mas este Gateway está configurado para outra empresa."
                : null;
        }
    }

    public void RecordFailure(DateTimeOffset now, string error)
    {
        lock (_lock)
        {
            Configured = true;
            Connected = false;
            LastCheckAt = now;
            LastError = error;
        }
    }
}

public sealed class CloudConnectionMonitor(
    IHttpClientFactory httpClientFactory,
    CloudConnectionState state,
    GatewayIdentity identity,
    IClock clock,
    IOptions<GatewayOptions> options,
    ILogger<CloudConnectionMonitor> logger) : BackgroundService
{
    public const string HttpClientName = "cloud-api";

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var opts = options.Value;
        if (string.IsNullOrWhiteSpace(opts.CloudApiBaseUrl) || string.IsNullOrWhiteSpace(opts.CloudSyncToken))
        {
            state.SetNotConfigured("Conexão com a nuvem desligada: configure Gateway:CloudApiBaseUrl e Gateway:CloudSyncToken.");
            logger.LogInformation("Conexão Gateway → nuvem desligada (CloudApiBaseUrl/CloudSyncToken ausentes).");
            return;
        }

        var interval = TimeSpan.FromSeconds(Math.Max(30, opts.CloudConnectionCheckSeconds));
        while (!stoppingToken.IsCancellationRequested)
        {
            await CheckOnceAsync(stoppingToken);
            try
            {
                await Task.Delay(interval, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    /// <summary>Faz um teste do token. Público para testes e para um futuro "testar agora".</summary>
    public async Task CheckOnceAsync(CancellationToken cancellationToken = default)
    {
        var opts = options.Value;
        var url = $"{opts.CloudApiBaseUrl.TrimEnd('/')}/api/GatewayToken/ping";
        try
        {
            var client = httpClientFactory.CreateClient(HttpClientName);
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", opts.CloudSyncToken.Trim());
            using var response = await client.SendAsync(request, cancellationToken);

            if (response.StatusCode == HttpStatusCode.Unauthorized)
            {
                state.RecordFailure(clock.UtcNow, "Token do Gateway inválido ou revogado. Gere um novo em Configurações → Token do Gateway.");
                logger.LogWarning("Nuvem recusou o token do Gateway (401).");
                return;
            }
            if (!response.IsSuccessStatusCode)
            {
                state.RecordFailure(clock.UtcNow, $"Nuvem respondeu HTTP {(int)response.StatusCode}.");
                return;
            }

            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
            var data = doc.RootElement.TryGetProperty("data", out var d) ? d : default;
            var companyId = data.ValueKind == JsonValueKind.Object && data.TryGetProperty("companyId", out var c) ? c.GetString() : null;
            var storeId = data.ValueKind == JsonValueKind.Object && data.TryGetProperty("storeId", out var s) ? s.GetString() : null;

            // Gateway vinculado a uma empresa: o token precisa ser da MESMA empresa (isolamento multi-tenant).
            var mismatch = identity.IsBound && companyId is not null &&
                           !string.Equals(companyId, identity.CompanyId, StringComparison.Ordinal);
            state.RecordSuccess(clock.UtcNow, companyId, storeId, mismatch);

            if (mismatch)
            {
                logger.LogError("Token do Gateway é da empresa {CloudCompany}, mas o Gateway está configurado para {LocalCompany}.",
                    companyId, identity.CompanyId);
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or JsonException)
        {
            state.RecordFailure(clock.UtcNow, ex is TaskCanceledException ? "Tempo esgotado ao falar com a nuvem." : "Sem conexão com a nuvem.");
            logger.LogDebug(ex, "Falha ao testar o token com a nuvem.");
        }
    }
}
