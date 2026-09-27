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
}
