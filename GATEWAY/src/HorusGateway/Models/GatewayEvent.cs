/*
 * Arquivo: Models/GatewayEvent.cs
 * Objetivo: representa um evento operacional persistido no Event Bus local do Gateway.
 */
namespace HorusGateway.Models;

/// <summary>Estado do evento no fluxo de sincronização em duas etapas.</summary>
public static class GatewayEventStatus
{
    /// <summary>Persistido localmente (entregue ao Gateway), aguardando envio à cloud.</summary>
    public const string PendingCloud = "PENDING_CLOUD";

    /// <summary>Já sincronizado com a cloud (usado a partir do CHANGE GATEWAY 06).</summary>
    public const string SyncedCloud = "SYNCED_CLOUD";

    /// <summary>Falha permanente no processamento.</summary>
    public const string Failed = "FAILED";
}

public sealed class GatewayEvent
{
    /// <summary>Cursor monotônico local (ordem de chegada). Usado pela recuperação incremental.</summary>
    public long Seq { get; set; }

    /// <summary>Identidade do evento — nasce no terminal e nunca muda ao atravessar Gateway → Cloud.</summary>
    public string EventId { get; set; } = string.Empty;

    public string CompanyId { get; set; } = string.Empty;
    public string StoreId { get; set; } = string.Empty;
    public string TerminalId { get; set; } = string.Empty;
    public string EventType { get; set; } = string.Empty;
    public string? OccurredAt { get; set; }

    /// <summary>Payload original (JSON) exatamente como recebido do terminal.</summary>
    public string Payload { get; set; } = "{}";

    /// <summary>Hash canônico calculado pelo Gateway sobre o payload — autoridade local de idempotência.</summary>
    public string PayloadHash { get; set; } = string.Empty;

    /// <summary>Hash informado pelo terminal (auditoria); pode divergir da canonicalização do Gateway.</summary>
    public string? ClientPayloadHash { get; set; }

    public string Status { get; set; } = GatewayEventStatus.PendingCloud;
    public string CreatedAt { get; set; } = string.Empty;
    public string? ProcessedAt { get; set; }

    /// <summary>Número de tentativas de envio à cloud já registradas (ciclo de vida de retry).</summary>
    public int RetryCount { get; set; }
}
