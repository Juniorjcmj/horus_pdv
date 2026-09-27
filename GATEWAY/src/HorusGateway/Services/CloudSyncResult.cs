/*
 * Arquivo: Services/CloudSyncResult.cs
 * Objetivo: desfecho do envio de um evento à cloud, distinguindo falha transitória (retry) de
 *           permanente (FAILED) e conflito (a cloud já processou → idempotente = sucesso).
 */
namespace HorusGateway.Services;

public enum CloudSyncStatus
{
    /// <summary>2xx — a cloud aceitou o evento.</summary>
    Success,

    /// <summary>409 — a cloud já tinha este EventId com o mesmo hash (replay idempotente) → tratar como sucesso.</summary>
    Conflict,

    /// <summary>5xx / timeout / rede indisponível — reagendar com backoff.</summary>
    Transient,

    /// <summary>4xx (exceto 409) — payload/rota inválidos; não adianta repetir → FAILED.</summary>
    Permanent
}

public sealed class CloudSyncResult
{
    public CloudSyncStatus Status { get; init; }
    public string? Message { get; init; }

    public static CloudSyncResult Of(CloudSyncStatus status, string? message = null)
        => new() { Status = status, Message = message };
}
