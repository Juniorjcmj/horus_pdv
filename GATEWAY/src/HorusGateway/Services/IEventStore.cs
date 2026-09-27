/*
 * Arquivo: Services/IEventStore.cs
 * Objetivo: contrato do Event Bus local — ingestão idempotente e recuperação incremental de eventos.
 */
using HorusGateway.Models;

namespace HorusGateway.Services;

public interface IEventStore
{
    /// <summary>Persiste um evento de forma idempotente (dedup por CompanyId + EventId + PayloadHash).</summary>
    Task<IngestResult> AppendAsync(IngestEventRequest request, CancellationToken cancellationToken = default);

    /// <summary>Recupera eventos de uma empresa com Seq maior que o cursor informado, em ordem crescente.</summary>
    Task<IReadOnlyList<GatewayEvent>> GetEventsAsync(string companyId, long afterSeq, int limit, CancellationToken cancellationToken = default);

    /// <summary>Total de eventos persistidos para a empresa.</summary>
    Task<long> CountAsync(string companyId, CancellationToken cancellationToken = default);

    /// <summary>Total de eventos ainda não sincronizados com a cloud (para health/dashboard).</summary>
    Task<long> CountPendingCloudAsync(string companyId, CancellationToken cancellationToken = default);

    /// <summary>
    /// Eventos prontos para envio à cloud (PENDING_CLOUD, tentativas não esgotadas e agendamento vencido).
    /// Base do dispatcher Gateway → Cloud do CHANGE GATEWAY 06.
    /// </summary>
    Task<IReadOnlyList<GatewayEvent>> GetDueForDispatchAsync(string companyId, int limit, CancellationToken cancellationToken = default);

    /// <summary>Registra uma falha de envio: incrementa RetryCount, guarda o erro e reagenda via backoff (ou marca FAILED).</summary>
    Task RecordDispatchFailureAsync(long seq, string error, CancellationToken cancellationToken = default);

    /// <summary>Marca o evento como sincronizado com a cloud.</summary>
    Task MarkSyncedAsync(long seq, CancellationToken cancellationToken = default);
}
