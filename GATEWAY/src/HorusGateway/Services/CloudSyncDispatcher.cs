/*
 * Arquivo: Services/CloudSyncDispatcher.cs
 * Objetivo: orquestra a sincronização Gateway → Cloud. A cada ciclo consome os eventos devidos
 *           (GetDueForDispatchAsync, já com backoff/idempotência do CHANGE 04), envia à cloud e
 *           atualiza o estado do evento:
 *             - Success/Conflict → SYNCED_CLOUD (Conflict = a cloud já processou → idempotente)
 *             - Transient       → reagenda com backoff (RecordDispatchFailureAsync)
 *             - Permanent       → FAILED (MarkFailedAsync)
 *           Preserva EventId + PayloadHash (idempotência ponta-a-ponta); não cria segunda verdade.
 */
using HorusGateway.Configuration;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class CloudSyncSummary
{
    public int Synced { get; set; }
    public int Retried { get; set; }
    public int Failed { get; set; }
    public int Processed => Synced + Retried + Failed;
}

public sealed class CloudSyncDispatcher
{
    private readonly IEventStore _events;
    private readonly ICloudSyncClient _client;
    private readonly CloudSyncState _state;
    private readonly IClock _clock;
    private readonly GatewayOptions _options;
    private readonly ILogger<CloudSyncDispatcher> _logger;

    public CloudSyncDispatcher(
        IEventStore events,
        ICloudSyncClient client,
        CloudSyncState state,
        IClock clock,
        IOptions<GatewayOptions> options,
        ILogger<CloudSyncDispatcher> logger)
    {
        _events = events;
        _client = client;
        _state = state;
        _clock = clock;
        _options = options.Value;
        _logger = logger;
    }

    /// <summary>Processa um lote de eventos devidos para a empresa. Retorna o resumo do ciclo.</summary>
    public async Task<CloudSyncSummary> RunOnceAsync(string companyId, CancellationToken cancellationToken = default)
    {
        var summary = new CloudSyncSummary();
        if (!_client.Enabled) return summary;

        var due = await _events.GetDueForDispatchAsync(companyId, _options.CloudSyncBatchSize, cancellationToken);

        foreach (var ev in due)
        {
            if (cancellationToken.IsCancellationRequested) break;

            var result = await _client.SendAsync(ev, cancellationToken);
            switch (result.Status)
            {
                case CloudSyncStatus.Success:
                case CloudSyncStatus.Conflict:
                    await _events.MarkSyncedAsync(ev.Seq, cancellationToken);
                    _state.RecordSuccess(_clock.UtcNow);
                    summary.Synced++;
                    break;

                case CloudSyncStatus.Transient:
                    await _events.RecordDispatchFailureAsync(ev.Seq, result.Message ?? "falha transitória", cancellationToken);
                    _state.RecordFailure(_clock.UtcNow, result.Message);
                    summary.Retried++;
                    break;

                case CloudSyncStatus.Permanent:
                    await _events.MarkFailedAsync(ev.Seq, result.Message ?? "falha permanente", cancellationToken);
                    _state.RecordFailure(_clock.UtcNow, result.Message);
                    summary.Failed++;
                    _logger.LogWarning("Evento Seq={Seq} falhou permanentemente na cloud: {Message}", ev.Seq, result.Message);
                    break;
            }
        }

        if (summary.Processed > 0)
        {
            _logger.LogInformation("Ciclo Gateway→Cloud: sincronizados={Synced} reagendados={Retried} falhos={Failed}",
                summary.Synced, summary.Retried, summary.Failed);
        }

        return summary;
    }
}
