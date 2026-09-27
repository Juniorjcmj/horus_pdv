/*
 * Arquivo: Services/SqliteTerminalStore.cs
 * Objetivo: implementação SQLite do registro de terminais.
 *           - Registro emite apiKey aleatória; só o hash (PBKDF2) é persistido.
 *           - Autenticação valida apiKey + status + vínculo com a empresa do Gateway.
 *           - Heartbeat atualiza LastSeenAt; listagem marca online/offline pela janela configurada.
 */
using HorusGateway.Configuration;
using HorusGateway.Data;
using HorusGateway.Models;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class SqliteTerminalStore : ITerminalStore
{
    private readonly GatewayDatabase _database;
    private readonly GatewayIdentity _identity;
    private readonly int _onlineWindowSeconds;
    private readonly ILogger<SqliteTerminalStore> _logger;

    public SqliteTerminalStore(
        GatewayDatabase database,
        GatewayIdentity identity,
        IOptions<GatewayOptions> options,
        ILogger<SqliteTerminalStore> logger)
    {
        _database = database;
        _identity = identity;
        _onlineWindowSeconds = Math.Max(5, options.Value.TerminalOnlineWindowSeconds);
        _logger = logger;
    }

    public Task<TerminalRegistrationResult> RegisterAsync(RegisterTerminalRequest request, string gatewayId, CancellationToken cancellationToken = default)
    {
        var companyId = request.CompanyId!.Trim();
        var terminalId = request.TerminalId!.Trim();
        var storeId = (request.StoreId ?? _identity.StoreId).Trim();
        var terminalType = NormalizeType(request.TerminalType);

        var apiKey = CredentialHasher.GenerateSecret();
        var credentialHash = CredentialHasher.Hash(apiKey);
        var now = DateTimeOffset.UtcNow.ToString("o");

        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        // Re-registro rotaciona a credencial e reativa o terminal (idempotente por (CompanyId, TerminalId)).
        command.CommandText = """
            INSERT INTO Terminals (CompanyId, TerminalId, StoreId, TerminalType, CredentialHash, Status, RegisteredAt, LastSeenAt)
            VALUES ($companyId, $terminalId, $storeId, $terminalType, $hash, 'active', $now, NULL)
            ON CONFLICT (CompanyId, TerminalId) DO UPDATE SET
                StoreId = excluded.StoreId,
                TerminalType = excluded.TerminalType,
                CredentialHash = excluded.CredentialHash,
                Status = 'active',
                RegisteredAt = excluded.RegisteredAt;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$terminalId", terminalId);
        command.Parameters.AddWithValue("$storeId", storeId);
        command.Parameters.AddWithValue("$terminalType", terminalType);
        command.Parameters.AddWithValue("$hash", credentialHash);
        command.Parameters.AddWithValue("$now", now);
        command.ExecuteNonQuery();

        _logger.LogInformation("Terminal registrado: TerminalId={TerminalId} Type={Type} CompanyId={CompanyId}",
            terminalId, terminalType, companyId);

        return Task.FromResult(new TerminalRegistrationResult
        {
            TerminalId = terminalId,
            CompanyId = companyId,
            StoreId = storeId,
            TerminalType = terminalType,
            GatewayId = gatewayId,
            ApiKey = apiKey,
            RegisteredAt = now
        });
    }

    public Task<TerminalAuthResult> AuthenticateAsync(string? terminalId, string? apiKey, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(terminalId) || string.IsNullOrWhiteSpace(apiKey))
        {
            return Task.FromResult(TerminalAuthResult.Of(TerminalAuthOutcome.MissingCredential));
        }

        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT CompanyId, TerminalId, StoreId, TerminalType, CredentialHash, Status, RegisteredAt, LastSeenAt
            FROM Terminals
            WHERE CompanyId = $companyId AND TerminalId = $terminalId
            LIMIT 1;
            """;
        command.Parameters.AddWithValue("$companyId", _identity.CompanyId);
        command.Parameters.AddWithValue("$terminalId", terminalId.Trim());

        using var reader = command.ExecuteReader();
        if (!reader.Read())
        {
            return Task.FromResult(TerminalAuthResult.Of(TerminalAuthOutcome.Unknown));
        }

        var storedHash = reader.GetString(4);
        var status = reader.GetString(5);
        if (!string.Equals(status, TerminalStatus.Active, StringComparison.Ordinal))
        {
            return Task.FromResult(TerminalAuthResult.Of(TerminalAuthOutcome.Revoked));
        }

        if (!CredentialHasher.Verify(apiKey, storedHash))
        {
            return Task.FromResult(TerminalAuthResult.Of(TerminalAuthOutcome.InvalidKey));
        }

        var info = MapInfo(reader);
        return Task.FromResult(TerminalAuthResult.Of(TerminalAuthOutcome.Ok, info));
    }

    public Task TouchAsync(string companyId, string terminalId, CancellationToken cancellationToken = default)
    {
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE Terminals SET LastSeenAt = $now
            WHERE CompanyId = $companyId AND TerminalId = $terminalId;
            """;
        command.Parameters.AddWithValue("$now", DateTimeOffset.UtcNow.ToString("o"));
        command.Parameters.AddWithValue("$companyId", companyId);
        command.Parameters.AddWithValue("$terminalId", terminalId);
        command.ExecuteNonQuery();
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<TerminalInfo>> ListAsync(string companyId, CancellationToken cancellationToken = default)
    {
        var terminals = new List<TerminalInfo>();
        using var connection = _database.OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT CompanyId, TerminalId, StoreId, TerminalType, CredentialHash, Status, RegisteredAt, LastSeenAt
            FROM Terminals
            WHERE CompanyId = $companyId
            ORDER BY TerminalId ASC;
            """;
        command.Parameters.AddWithValue("$companyId", companyId);

        using var reader = command.ExecuteReader();
        while (reader.Read())
        {
            terminals.Add(MapInfo(reader));
        }

        return Task.FromResult<IReadOnlyList<TerminalInfo>>(terminals);
    }

    private TerminalInfo MapInfo(SqliteDataReader reader)
    {
        var lastSeen = reader.IsDBNull(7) ? null : reader.GetString(7);
        var online = false;
        if (lastSeen is not null && DateTimeOffset.TryParse(lastSeen, out var seenAt))
        {
            online = (DateTimeOffset.UtcNow - seenAt).TotalSeconds <= _onlineWindowSeconds;
        }

        return new TerminalInfo
        {
            CompanyId = reader.GetString(0),
            TerminalId = reader.GetString(1),
            StoreId = reader.GetString(2),
            TerminalType = reader.GetString(3),
            Status = reader.GetString(5),
            RegisteredAt = reader.GetString(6),
            LastSeenAt = lastSeen,
            Online = online
        };
    }

    private static string NormalizeType(string? type)
    {
        var t = (type ?? string.Empty).Trim().ToUpperInvariant();
        return t == TerminalType.Cash ? TerminalType.Cash : TerminalType.Order;
    }
}
