/*
 * Arquivo: Services/CloudSyncState.cs
 * Objetivo: estado observável da sincronização Gateway → Cloud (para health/dashboard).
 */
namespace HorusGateway.Services;

public sealed class CloudSyncState
{
    private readonly object _lock = new();

    public DateTimeOffset? LastSuccessAt { get; private set; }
    public DateTimeOffset? LastAttemptAt { get; private set; }
    public string? LastError { get; private set; }
    public bool Online { get; private set; }

    public void RecordSuccess(DateTimeOffset at)
    {
        lock (_lock)
        {
            LastSuccessAt = at;
            LastAttemptAt = at;
            LastError = null;
            Online = true;
        }
    }

    public void RecordFailure(DateTimeOffset at, string? error)
    {
        lock (_lock)
        {
            LastAttemptAt = at;
            LastError = error;
            Online = false;
        }
    }

    public object Snapshot()
    {
        lock (_lock)
        {
            return new
            {
                online = Online,
                lastSuccessAt = LastSuccessAt?.ToString("o"),
                lastAttemptAt = LastAttemptAt?.ToString("o"),
                lastError = LastError
            };
        }
    }
}
