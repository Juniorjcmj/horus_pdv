/*
 * Arquivo: Models/OrderModels.cs
 * Objetivo: estados, tipos de evento e projeções de pedido para o Event Bus local.
 */
using System.Text.Json;
using System.Text.Json.Serialization;

namespace HorusGateway.Models;

/// <summary>Estados do ciclo de vida do pedido.</summary>
public static class OrderState
{
    public const string Created = "CREATED";
    public const string Received = "RECEIVED";
    public const string Confirmed = "CONFIRMED";
    public const string Preparing = "PREPARING";
    public const string Ready = "READY";
    public const string Delivered = "DELIVERED";
    public const string Cancelled = "CANCELLED";
}

/// <summary>Tipos de evento operacional de pedido.</summary>
public static class OrderEventType
{
    public const string Created = "ORDER_CREATED";
    public const string Updated = "ORDER_UPDATED";
    public const string Received = "ORDER_RECEIVED";
    public const string Confirmed = "ORDER_CONFIRMED";
    public const string Preparing = "ORDER_PREPARING";
    public const string Ready = "ORDER_READY";
    public const string Delivered = "ORDER_DELIVERED";
    public const string Cancelled = "ORDER_CANCELLED";

    public static bool IsOrderEvent(string? type)
        => !string.IsNullOrWhiteSpace(type) && type!.StartsWith("ORDER_", StringComparison.Ordinal);
}

public enum OrderApplyOutcome
{
    Applied,
    InvalidTransition,
    MissingOrderNumber,
    UnknownEventType
}

public sealed class OrderApplyResult
{
    public OrderApplyOutcome Outcome { get; init; }
    public OrderView? Order { get; init; }
    public string? Message { get; init; }
    public bool Ok => Outcome == OrderApplyOutcome.Applied;

    public static OrderApplyResult Of(OrderApplyOutcome outcome, OrderView? order = null, string? message = null)
        => new() { Outcome = outcome, Order = order, Message = message };
}

/// <summary>Projeção (read model) do estado atual de um pedido, para o caixa/dashboard.</summary>
public sealed class OrderView
{
    [JsonPropertyName("orderNumber")] public string OrderNumber { get; set; } = string.Empty;
    [JsonPropertyName("companyId")] public string CompanyId { get; set; } = string.Empty;
    [JsonPropertyName("storeId")] public string StoreId { get; set; } = string.Empty;
    [JsonPropertyName("terminalId")] public string TerminalId { get; set; } = string.Empty;
    [JsonPropertyName("status")] public string Status { get; set; } = string.Empty;
    [JsonPropertyName("totalAmount")] public string? TotalAmount { get; set; }
    [JsonPropertyName("version")] public int Version { get; set; }
    [JsonPropertyName("createdAt")] public string CreatedAt { get; set; } = string.Empty;
    [JsonPropertyName("updatedAt")] public string UpdatedAt { get; set; } = string.Empty;
    [JsonPropertyName("lastEventId")] public string LastEventId { get; set; } = string.Empty;
    [JsonPropertyName("payload")] public JsonElement Payload { get; set; }
}
