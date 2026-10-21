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
        // Resolve o caminho do SQLite a partir do diretório do executável quando for relativo.
        // Um Serviço do Windows inicia com o diretório de trabalho em System32, então um caminho
        // relativo criaria o banco no lugar errado — ancoramos em AppContext.BaseDirectory.
        var raw = options.Value.DatabasePath;
        var path = Path.IsPathRooted(raw) ? raw : Path.Combine(AppContext.BaseDirectory, raw);
        var dir = Path.GetDirectoryName(path);
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

            -- Ledger de idempotência dedicado, espelhando o modelo ProcessedEvents da cloud.
            CREATE TABLE IF NOT EXISTS ProcessedEvents (
                CompanyId   TEXT NOT NULL,
                EventId     TEXT NOT NULL,
                PayloadHash TEXT NOT NULL,
                ProcessedAt TEXT NOT NULL,
                PRIMARY KEY (CompanyId, EventId)
            );

            -- Projeção (read model) do estado atual dos pedidos, derivada dos eventos ORDER_*.
            CREATE TABLE IF NOT EXISTS Orders (
                CompanyId   TEXT NOT NULL,
                OrderNumber TEXT NOT NULL,
                StoreId     TEXT NOT NULL DEFAULT '',
                TerminalId  TEXT NOT NULL DEFAULT '',
                Status      TEXT NOT NULL,
                TotalAmount TEXT NULL,
                Payload     TEXT NOT NULL DEFAULT '{}',
                Version     INTEGER NOT NULL DEFAULT 1,
                CreatedAt   TEXT NOT NULL,
                UpdatedAt   TEXT NOT NULL,
                LastEventId TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (CompanyId, OrderNumber)
            );

            CREATE INDEX IF NOT EXISTS IX_Orders_Company_Status
                ON Orders (CompanyId, Status);

            -- Controle de numeração sequencial de NFC-e emitida no Gateway local
            CREATE TABLE IF NOT EXISTS LocalFiscalSequencias (
                CompanyId     TEXT NOT NULL,
                Serie         INTEGER NOT NULL,
                ProximoNumero INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (CompanyId, Serie)
            );

            -- Tabela de persistência durável das NFC-e emitidas em contingência offline no Gateway
            CREATE TABLE IF NOT EXISTS LocalNfceContingencias (
                Id             TEXT PRIMARY KEY,
                CompanyId      TEXT NOT NULL,
                VendaId        TEXT NOT NULL,
                TerminalId     TEXT NOT NULL,
                Serie          INTEGER NOT NULL,
                NumeroNf       INTEGER NOT NULL,
                ChaveAcesso    TEXT NOT NULL,
                DhContingencia TEXT NOT NULL,
                Justificativa  TEXT NOT NULL,
                XmlAssinado    TEXT NOT NULL,
                QrCodeUrl      TEXT NOT NULL,
                DigestValue    TEXT NOT NULL,
                TotalVenda     REAL NOT NULL,
                StatusSync     TEXT NOT NULL DEFAULT 'PENDING',
                CreatedAt      TEXT NOT NULL,
                SyncedAt       TEXT NULL
            );

            CREATE UNIQUE INDEX IF NOT EXISTS UX_LocalNfce_Chave ON LocalNfceContingencias (ChaveAcesso);
            CREATE INDEX IF NOT EXISTS IX_LocalNfce_Company_Sync ON LocalNfceContingencias (CompanyId, StatusSync);
            """;
        command.ExecuteNonQuery();
        CreditSchema.Initialize(connection);

        // Colunas de ciclo de vida de retry (adicionadas de forma idempotente sobre a tabela do 02).
        EnsureColumn(connection, "GatewayEvents", "RetryCount", "INTEGER NOT NULL DEFAULT 0");
        EnsureColumn(connection, "GatewayEvents", "LastAttemptAt", "TEXT NULL");
        EnsureColumn(connection, "GatewayEvents", "LastError", "TEXT NULL");
        EnsureColumn(connection, "GatewayEvents", "CloudSaleNumber", "TEXT NULL");
        EnsureColumn(connection, "GatewayEvents", "NextAttemptAt", "TEXT NULL");
        EnsureColumn(connection, "LocalNfceContingencias", "RequestHash", "TEXT NULL");
        EnsureColumn(connection, "LocalNfceContingencias", "ResponseJson", "TEXT NULL");

        // Pré-autorização de terminais pelo administrador (auto-identificação por IP/token na LAN).
        EnsureColumn(connection, "Terminals", "AllowedIp", "TEXT NULL");
        EnsureColumn(connection, "Terminals", "ProvisionTokenHash", "TEXT NULL");
        EnsureColumn(connection, "Terminals", "Provisioned", "INTEGER NOT NULL DEFAULT 0");

        RequeueTransientFailures(connection);

        _logger.LogInformation("Schema local do Gateway inicializado (SQLite).");
    }

    /// <summary>
    /// Versões anteriores abandonavam o evento (FAILED) após 10 falhas de rede. Falha transitória nunca deve
    /// abandonar: devolve à fila os FAILED cujo motivo é transitório (rede, timeout, 408, 429, 5xx, 401/403).
    /// Idempotente — a nuvem deduplica por EventId, e recusas de negócio permanecem FAILED.
    /// </summary>
    private void RequeueTransientFailures(SqliteConnection connection)
    {
        using var update = connection.CreateCommand();
        update.CommandText = """
            UPDATE GatewayEvents
               SET Status = 'PENDING_CLOUD', NextAttemptAt = NULL
             WHERE Status = 'FAILED'
               AND (LastError IN ('rede indisponível', 'timeout')
                    OR LastError LIKE 'HTTP 5%'
                    OR LastError LIKE 'HTTP 408%'
                    OR LastError LIKE 'HTTP 429%'
                    OR LastError LIKE 'Nuvem recusou o acesso%'
                    OR LastError LIKE 'Envio à nuvem não configurado%');
            """;
        var requeued = update.ExecuteNonQuery();
        if (requeued > 0)
        {
            _logger.LogWarning("{Count} evento(s) abandonados por falha transitória foram devolvidos à fila de envio.", requeued);
        }
    }

    /// <summary>Adiciona uma coluna só se ela ainda não existir (SQLite não tem ADD COLUMN IF NOT EXISTS).</summary>
    private static void EnsureColumn(SqliteConnection connection, string table, string column, string definition)
    {
        using (var check = connection.CreateCommand())
        {
            check.CommandText = $"SELECT COUNT(1) FROM pragma_table_info('{table}') WHERE name = $name;";
            check.Parameters.AddWithValue("$name", column);
            if (Convert.ToInt64(check.ExecuteScalar()) > 0) return;
        }

        using var alter = connection.CreateCommand();
        alter.CommandText = $"ALTER TABLE {table} ADD COLUMN {column} {definition};";
        alter.ExecuteNonQuery();
    }
}
