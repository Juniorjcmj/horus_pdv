/**
 * Arquivo: API/NETCORE/Repositories/Connection.cs
 * Objetivo: centraliza a criação de conexões SQL Server usadas pelos repositórios da API.
 * Entradas esperadas: espera configuração de connection string válida para abrir conexões com o banco.
 */
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories;

public sealed class Connection(IConfiguration configuration)
{
    // Escopo por fluxo assíncrono: uma importação usa uma única transação local,
    // sem compartilhar conexão entre requisições e sem promover para MSDTC.
    private readonly AsyncLocal<SqlTransaction?> importTransaction = new();
    public SqlTransaction? CurrentImportTransaction => importTransaction.Value;

    public IDisposable UseImportTransaction(SqlTransaction transaction)
    {
        if (importTransaction.Value is not null) throw new InvalidOperationException("Transação de entrada já aberta.");
        importTransaction.Value = transaction;
        return new ImportScope(() => importTransaction.Value = null);
    }

    public async Task<ConnectionLease> OpenLeaseAsync(CancellationToken cancellationToken = default)
    {
        var transaction = CurrentImportTransaction;
        return transaction is null
            ? new ConnectionLease(await OpenConnectionAsync(cancellationToken), true)
            : new ConnectionLease(transaction.Connection!, false);
    }

    public SqlCommand CreateCommand(string sql, SqlConnection db, SqlTransaction? transaction = null)
        => new(sql, db, transaction ?? (CurrentImportTransaction?.Connection == db ? CurrentImportTransaction : null));

    public sealed class ConnectionLease(SqlConnection connection, bool ownsConnection) : IAsyncDisposable
    {
        public static implicit operator SqlConnection(ConnectionLease lease) => lease.Connection;
        private SqlConnection Connection => connection;
        public ValueTask<System.Data.Common.DbTransaction> BeginTransactionAsync(CancellationToken ct = default)
            => connection.BeginTransactionAsync(ct);
        public ValueTask DisposeAsync() => ownsConnection ? connection.DisposeAsync() : ValueTask.CompletedTask;
    }

    private sealed class ImportScope(Action dispose) : IDisposable
    {
        public void Dispose() => dispose();
    }
    public string ConnectionString { get; } = ResolveConnectionString(configuration);

    public async Task<SqlConnection> OpenConnectionAsync(
        CancellationToken cancellationToken = default,
        string? database = null)
    {
        var connectionString = string.IsNullOrWhiteSpace(database)
            ? ConnectionString
            : BuildConnectionString(database);
        var connection = new SqlConnection(connectionString);
        await connection.OpenAsync(cancellationToken);
        return connection;
    }

    public SqlConnection OpenConnection(string? database = null)
    {
        var connectionString = string.IsNullOrWhiteSpace(database)
            ? ConnectionString
            : BuildConnectionString(database);
        var connection = new SqlConnection(connectionString);
        connection.Open();
        return connection;
    }

    private string BuildConnectionString(string database)
    {
        var builder = new SqlConnectionStringBuilder(ConnectionString)
        {
            InitialCatalog = database
        };
        return builder.ConnectionString;
    }

    private static string ResolveConnectionString(IConfiguration configuration)
    {
        var connectionString =
            FirstNonEmpty(
                configuration.GetConnectionString("HorusPdv"),
                configuration.GetConnectionString("DefaultConnection"),
                Environment.GetEnvironmentVariable("SQLCONNSTR_HorusPdv"),
                Environment.GetEnvironmentVariable("SQLCONNSTR_DefaultConnection"),
                Environment.GetEnvironmentVariable("HORUSPDV_CONNECTION_STRING"));

        if (string.IsNullOrWhiteSpace(connectionString))
        {
            throw new InvalidOperationException(
                "Connection string não configurada. Defina ConnectionStrings:HorusPdv ou HORUSPDV_CONNECTION_STRING.");
        }

        return connectionString;
    }

    private static string? FirstNonEmpty(params string?[] values)
        => values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));
}
