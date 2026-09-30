/*
 * Arquivo: Models/DashboardModels.cs
 * Objetivo: DTOs e modelos para o Dashboard de monitoramento local e verificação de atualização controlada (CHANGE GATEWAY 08).
 */
namespace HorusGateway.Models;

public sealed record EventStoreMetrics(
    long TotalEvents,
    long PendingCloudEvents,
    long SyncedCloudEvents,
    long FailedEvents,
    string? LastEventOccurredAt,
    string? LastSyncedAt);

public sealed record DashboardIdentity(
    string GatewayId,
    string CompanyId,
    string StoreId,
    bool Bound,
    string ServerTime,
    long UptimeSeconds);

public sealed record DashboardHealth(
    string Gateway,
    string Storage,
    string Internet,
    string Cloud);

public sealed record DashboardSync(
    string? CloudSyncUrl,
    bool Online,
    string? LastSuccessAt,
    string? LastAttemptAt,
    string? LastError);

public sealed record DashboardEventsSummary(
    long Total,
    long PendingCloud,
    long SyncedCloud,
    long Failed,
    string? LastEventOccurredAt,
    string? LastSyncedAt);

public sealed record DashboardTerminalsSummary(
    int Total,
    int Online,
    int Offline,
    IReadOnlyList<TerminalDashboardItem> Items);

public sealed record TerminalDashboardItem(
    string TerminalId,
    string TerminalType,
    string Status,
    string? LastSeenAt,
    int SecondsSinceLastSeen);

public sealed record UpdateReadiness(
    bool IsSafe,
    long PendingEvents,
    long FailedEvents,
    string Message,
    string ActionRecommended);

public sealed record DashboardSummaryResponse(
    DashboardIdentity Identity,
    DashboardHealth Health,
    DashboardSync Sync,
    DashboardEventsSummary Events,
    DashboardTerminalsSummary Terminals,
    int ActiveOrdersCount,
    UpdateReadiness UpdateReadiness);

public sealed record SafeUpdateCheckResponse(
    bool Safe,
    long PendingEvents,
    long FailedEvents,
    string Message,
    string ActionRecommended,
    string Timestamp);
