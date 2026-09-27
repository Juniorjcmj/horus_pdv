/*
 * Arquivo: Models/TerminalModels.cs
 * Objetivo: contratos de registro/heartbeat de terminais e projeções para o dashboard.
 */
using System.Text.Json.Serialization;

namespace HorusGateway.Models;

public static class TerminalStatus
{
    public const string Active = "active";
    public const string Revoked = "revoked";
}

public static class TerminalType
{
    public const string Order = "ORDER";
    public const string Cash = "CASH";
}

public sealed class RegisterTerminalRequest
{
    public string? CompanyId { get; set; }
    public string? StoreId { get; set; }
    public string? TerminalId { get; set; }
    public string? TerminalType { get; set; }

    /// <summary>Token compartilhado provisionado pelo administrador (autoriza o registro na LAN).</summary>
    public string? RegistrationToken { get; set; }
}

/// <summary>Resultado do registro — a apiKey em texto puro só é retornada aqui, uma vez.</summary>
public sealed class TerminalRegistrationResult
{
    [JsonPropertyName("terminalId")] public string TerminalId { get; set; } = string.Empty;
    [JsonPropertyName("companyId")] public string CompanyId { get; set; } = string.Empty;
    [JsonPropertyName("storeId")] public string StoreId { get; set; } = string.Empty;
    [JsonPropertyName("terminalType")] public string TerminalType { get; set; } = string.Empty;
    [JsonPropertyName("gatewayId")] public string GatewayId { get; set; } = string.Empty;
    [JsonPropertyName("apiKey")] public string ApiKey { get; set; } = string.Empty;
    [JsonPropertyName("registeredAt")] public string RegisteredAt { get; set; } = string.Empty;
}

/// <summary>Projeção de terminal para status/dashboard (nunca expõe credencial).</summary>
public sealed class TerminalInfo
{
    [JsonPropertyName("terminalId")] public string TerminalId { get; set; } = string.Empty;
    [JsonPropertyName("companyId")] public string CompanyId { get; set; } = string.Empty;
    [JsonPropertyName("storeId")] public string StoreId { get; set; } = string.Empty;
    [JsonPropertyName("terminalType")] public string TerminalType { get; set; } = string.Empty;
    [JsonPropertyName("status")] public string Status { get; set; } = string.Empty;
    [JsonPropertyName("registeredAt")] public string RegisteredAt { get; set; } = string.Empty;
    [JsonPropertyName("lastSeenAt")] public string? LastSeenAt { get; set; }
    [JsonPropertyName("online")] public bool Online { get; set; }
}

/// <summary>Desfecho da autenticação de um terminal.</summary>
public enum TerminalAuthOutcome
{
    Ok,
    MissingCredential,
    Unknown,
    InvalidKey,
    Revoked,
    CompanyMismatch
}

public sealed class TerminalAuthResult
{
    public TerminalAuthOutcome Outcome { get; init; }
    public TerminalInfo? Terminal { get; init; }
    public bool Authenticated => Outcome == TerminalAuthOutcome.Ok;

    public static TerminalAuthResult Of(TerminalAuthOutcome outcome, TerminalInfo? terminal = null)
        => new() { Outcome = outcome, Terminal = terminal };
}
