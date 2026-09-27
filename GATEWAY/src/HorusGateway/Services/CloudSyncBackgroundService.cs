/*
 * Arquivo: Services/CloudSyncBackgroundService.cs
 * Objetivo: laço em background que dispara o CloudSyncDispatcher periodicamente para a empresa
 *           do Gateway. Só roda quando a sincronização está habilitada (CloudSyncUrl configurada) e
 *           o Gateway está vinculado a uma empresa. Recupera automaticamente após reinicialização,
 *           pois os eventos pendentes vivem no SQLite.
 */
using HorusGateway.Configuration;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class CloudSyncBackgroundService : BackgroundService
{
    private readonly IServiceProvider _services;
    private readonly GatewayIdentity _identity;
    private readonly GatewayOptions _options;
    private readonly ILogger<CloudSyncBackgroundService> _logger;

    public CloudSyncBackgroundService(
        IServiceProvider services,
        GatewayIdentity identity,
        IOptions<GatewayOptions> options,
        ILogger<CloudSyncBackgroundService> logger)
    {
        _services = services;
        _identity = identity;
        _options = options.Value;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var enabled = !string.IsNullOrWhiteSpace(_options.CloudSyncUrl);
        if (!enabled || !_identity.IsBound)
        {
            _logger.LogInformation("Sincronização Gateway→Cloud desabilitada (CloudSyncUrl ausente ou Gateway sem empresa). Operando LAN-only.");
            return;
        }

        var interval = TimeSpan.FromSeconds(Math.Max(2, _options.CloudSyncIntervalSeconds));
        _logger.LogInformation("Dispatcher Gateway→Cloud iniciado (intervalo {Interval}s).", interval.TotalSeconds);

        using var timer = new PeriodicTimer(interval);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = _services.CreateScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<CloudSyncDispatcher>();
                await dispatcher.RunOnceAsync(_identity.CompanyId, stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Erro no ciclo do dispatcher Gateway→Cloud.");
            }

            try
            {
                await timer.WaitForNextTickAsync(stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }
}
