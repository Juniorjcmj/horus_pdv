/*
 * Arquivo: Data/GatewayDatabase.cs
 * Objetivo: fábrica de conexões SQLite e inicialização idempotente do schema local do Gateway.
 *           A persistência é durável (arquivo em disco), garantindo recuperação após reinicialização.
 */
using HorusGateway.Configuration;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Options;

namespace HorusGateway.Data;

public sealed class GatewayDatabase
{
    private readonly string _connectionString;
    private readonly ILogger<GatewayDatabase> _logger;

    public GatewayDatabase(IOptions<GatewayOptions> options, ILogger<GatewayDatabase> logger)
    {
        _logger = logger;
        var path = options.Value.DatabasePath;
        var dir = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared
        }.ToString();
    }

    public SqliteConnection OpenConnection()
    {
        var connection = new SqliteConnection(_connectionString);
        connection.Open();
        using (var pragma = connection.CreateCommand())
        {
            // WAL + busy_timeout permitem escrita concorrente de vários terminais sem lock global.
            pragma.CommandText = "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;";
            pragma.ExecuteNonQuery();
        }
        return connection;
    }

    /// <summary>Cria o schema local se ainda não existir. Idempotente (seguro a cada boot).</summary>
    public void Initialize()
    {
        using var connection = OpenConnection();
        using var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS GatewayEvents (
                Seq               INTEGER PRIMARY KEY AUTOINCREMENT,
                EventId           TEXT    NOT NULL,
                CompanyId         TEXT    NOT NULL,
                StoreId           TEXT    NOT NULL DEFAULT '',
                TerminalId        TEXT    NOT NULL,
                EventType         TEXT    NOT NULL,
                OccurredAt        TEXT    NULL,
                Payload           TEXT    NOT NULL DEFAULT '{}',
                PayloadHash       TEXT    NOT NULL,
                ClientPayloadHash TEXT    NULL,
                Status            TEXT    NOT NULL DEFAULT 'PENDING_CLOUD',
                CreatedAt         TEXT    NOT NULL,
                ProcessedAt       TEXT    NULL
            );

            CREATE UNIQUE INDEX IF NOT EXISTS UX_GatewayEvents_Company_Event
                ON GatewayEvents (CompanyId, EventId);

            CREATE INDEX IF NOT EXISTS IX_GatewayEvents_Company_Seq
                ON GatewayEvents (CompanyId, Seq);

            CREATE TABLE IF NOT EXISTS Terminals (
                CompanyId      TEXT NOT NULL,
                TerminalId     TEXT NOT NULL,
                StoreId        TEXT NOT NULL DEFAULT '',
                TerminalType   TEXT NOT NULL DEFAULT 'ORDER',
                CredentialHash TEXT NOT NULL,
                Status         TEXT NOT NULL DEFAULT 'active',
                RegisteredAt   TEXT NOT NULL,
                LastSeenAt     TEXT NULL,
                PRIMARY KEY (CompanyId, TerminalId)
            );
            """;
        command.ExecuteNonQuery();

        _logger.LogInformation("Schema local do Gateway inicializado (SQLite).");
    }
}
