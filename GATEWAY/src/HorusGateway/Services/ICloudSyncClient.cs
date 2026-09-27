/*
 * Arquivo: Services/ICloudSyncClient.cs
 * Objetivo: contrato do transporte Gateway → Cloud. Envia um evento à API central preservando
 *           EventId + PayloadHash para a idempotência ponta-a-ponta.
 */
using HorusGateway.Models;

namespace HorusGateway.Services;

public interface ICloudSyncClient
{
    /// <summary>True quando a sincronização com a cloud está configurada (URL presente).</summary>
    bool Enabled { get; }

    Task<CloudSyncResult> SendAsync(GatewayEvent ev, CancellationToken cancellationToken = default);
}
