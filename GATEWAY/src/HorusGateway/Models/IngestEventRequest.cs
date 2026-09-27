/*
 * Arquivo: Models/IngestEventRequest.cs
 * Objetivo: contrato de entrada do endpoint de ingestão de eventos (Terminal → Gateway) e resultado da ingestão.
 */
using System.Text.Json;
using System.Text.Json.Serialization;

namespace HorusGateway.Models;

public sealed class IngestEventRequest
{
    public string? EventId { get; set; }
    public string? CompanyId { get; set; }
    public string? StoreId { get; set; }
    public string? TerminalId { get; set; }
    public string? EventType { get; set; }
    public string? OccurredAt { get; set; }

    /// <summary>Payload arbitrário do evento (ex.: dados do pedido). Preservado como recebido.</summary>
    public JsonElement Payload { get; set; }

    /// <summary>Hash informado pelo terminal (opcional; auditoria).</summary>
    public string? PayloadHash { get; set; }
}

/// <summary>Desfecho lógico da ingestão de um evento.</summary>
public enum IngestOutcome
{
    /// <summary>Evento novo, persistido.</summary>
    Accepted,

    /// <summary>Mesmo EventId + mesmo PayloadHash — replay legítimo e idempotente.</summary>
    Replay,

    /// <summary>Mesmo EventId + PayloadHash diferente — conflito.</summary>
    Conflict,

    /// <summary>Requisição malformada (campos obrigatórios ausentes).</summary>
    Invalid,

    /// <summary>CompanyId não pertence a este Gateway.</summary>
    Forbidden
}

public sealed class IngestResult
{
    public IngestOutcome Outcome { get; init; }
    public GatewayEvent? Event { get; init; }
    public string? Message { get; init; }

    public static IngestResult Of(IngestOutcome outcome, GatewayEvent? ev = null, string? message = null)
        => new() { Outcome = outcome, Event = ev, Message = message };
}

/// <summary>Envelope de saída de um evento (usado no ACK e na recuperação REST).</summary>
public sealed class GatewayEventDto
{
    [JsonPropertyName("seq")] public long Seq { get; set; }
    [JsonPropertyName("eventId")] public string EventId { get; set; } = string.Empty;
    [JsonPropertyName("companyId")] public string CompanyId { get; set; } = string.Empty;
    [JsonPropertyName("storeId")] public string StoreId { get; set; } = string.Empty;
    [JsonPropertyName("terminalId")] public string TerminalId { get; set; } = string.Empty;
    [JsonPropertyName("eventType")] public string EventType { get; set; } = string.Empty;
    [JsonPropertyName("occurredAt")] public string? OccurredAt { get; set; }
    [JsonPropertyName("payloadHash")] public string PayloadHash { get; set; } = string.Empty;
    [JsonPropertyName("status")] public string Status { get; set; } = string.Empty;
    [JsonPropertyName("createdAt")] public string CreatedAt { get; set; } = string.Empty;
    [JsonPropertyName("payload")] public JsonElement Payload { get; set; }

    public static GatewayEventDto From(GatewayEvent e)
    {
        JsonElement payload;
        try
        {
            using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(e.Payload) ? "{}" : e.Payload);
            payload = doc.RootElement.Clone();
        }
        catch
        {
            using var doc = JsonDocument.Parse("{}");
            payload = doc.RootElement.Clone();
        }

        return new GatewayEventDto
        {
            Seq = e.Seq,
            EventId = e.EventId,
            CompanyId = e.CompanyId,
            StoreId = e.StoreId,
            TerminalId = e.TerminalId,
            EventType = e.EventType,
            OccurredAt = e.OccurredAt,
            PayloadHash = e.PayloadHash,
            Status = e.Status,
            CreatedAt = e.CreatedAt,
            Payload = payload
        };
    }
}
