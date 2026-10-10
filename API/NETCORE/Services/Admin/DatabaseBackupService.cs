using System.Collections.Concurrent;
using System.Threading.Channels;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DatabaseAccess;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Services.Admin;

public sealed record DatabaseBackupStatus(Guid Id, string Status, string FileName, DateTimeOffset CreatedAt,
    DateTimeOffset ExpiresAt, long? SizeBytes = null, string? Message = null);

/// <summary>Backup nativo de todo o banco central; a geração continua fora da requisição HTTP.</summary>
public sealed class DatabaseBackupService(Connection connection, IConfiguration configuration,
    IServiceScopeFactory scopes, ILogger<DatabaseBackupService> logger) : BackgroundService
{
    private sealed class Job(DatabaseBackupStatus status, string userId, string userName, string? ip)
    {
        public DatabaseBackupStatus State = status;
        public readonly string UserId = userId;
        public readonly string UserName = userName;
        public readonly string? Ip = ip;
    }

    private readonly object gate = new();
    private readonly ConcurrentDictionary<Guid, Job> jobs = new();
    private readonly Channel<Job> queue = Channel.CreateUnbounded<Job>(new UnboundedChannelOptions { SingleReader = true });
    private Guid? activeId;
    private string StorageDirectory => configuration["DatabaseBackup:StorageDirectory"]?.Trim() ?? "";
    private string SqlDirectory => configuration["DatabaseBackup:SqlDirectory"]?.Trim() ?? "";
    private string LocalPath(Job job) => Path.Combine(StorageDirectory, job.State.FileName);
    private string SqlPath(Job job) => SqlDirectory.TrimEnd('/', '\\') +
        (SqlDirectory.Contains('\\') ? "\\" : "/") + job.State.FileName;

    public DatabaseBackupStatus Start(string userId, string userName, string? ip)
    {
        if (string.IsNullOrWhiteSpace(StorageDirectory) || string.IsNullOrWhiteSpace(SqlDirectory))
            throw new InvalidOperationException("O backup completo ainda não está configurado no servidor. Configure a pasta compartilhada de backups da API e do SQL Server.");
        if (!Directory.Exists(StorageDirectory))
            throw new InvalidOperationException("A pasta de backups está indisponível. Verifique o volume compartilhado no servidor.");
        var database = new SqlConnectionStringBuilder(connection.ConnectionString).InitialCatalog;
        if (string.IsNullOrWhiteSpace(database) || new[] { "master", "model", "msdb", "tempdb" }.Contains(database, StringComparer.OrdinalIgnoreCase))
            throw new InvalidOperationException("Configure o banco de dados do PDV antes de gerar o backup.");

        lock (gate)
        {
            if (activeId is Guid active && jobs.TryGetValue(active, out var running))
            {
                if (running.UserId != userId) throw new InvalidOperationException("Outro administrador já está gerando um backup. Aguarde a conclusão.");
                return running.State;
            }
            var now = DateTimeOffset.UtcNow;
            var id = Guid.NewGuid();
            var state = new DatabaseBackupStatus(id, "gerando", $"quack-pdv-completo-{now:yyyyMMdd-HHmmss}-{id:N}.bak", now, now.AddHours(6));
            var job = new Job(state, userId, userName, ip);
            jobs[id] = job;
            activeId = id;
            queue.Writer.TryWrite(job);
            return state;
        }
    }

    public DatabaseBackupStatus? Get(Guid id, string userId)
    {
        lock (gate)
            return jobs.TryGetValue(id, out var job) && job.UserId == userId && job.State.ExpiresAt > DateTimeOffset.UtcNow
                ? job.State : null;
    }

    public (FileStream Stream, string FileName)? OpenDownload(Guid id, string userId)
    {
        lock (gate)
        {
            if (!jobs.TryGetValue(id, out var job) || job.UserId != userId || job.State.Status != "concluido" ||
                job.State.ExpiresAt <= DateTimeOffset.UtcNow) return null;
            try
            {
                var stream = new FileStream(LocalPath(job), FileMode.Open, FileAccess.Read, FileShare.Read,
                    64 * 1024, FileOptions.Asynchronous | FileOptions.SequentialScan);
                return (stream, job.State.FileName);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return null; }
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
        => await Task.WhenAll(ProcessQueue(stoppingToken), Cleanup(stoppingToken));

    private async Task ProcessQueue(CancellationToken token)
    {
        await foreach (var job in queue.Reader.ReadAllAsync(token))
        {
            try
            {
                await Audit(job, "BackupBancoSolicitado", "Backup completo do banco central solicitado.");
                await using var db = await connection.OpenConnectionAsync(token);
                await using (var backup = new SqlCommand("BACKUP DATABASE @database TO DISK = @path WITH COPY_ONLY, CHECKSUM, INIT;", db))
                {
                    backup.CommandTimeout = 3600;
                    backup.Parameters.AddWithValue("@database", db.Database);
                    backup.Parameters.AddWithValue("@path", SqlPath(job));
                    await backup.ExecuteNonQueryAsync(token);
                }
                SetState(job, job.State with { Status = "verificando" });
                await using (var verify = new SqlCommand("RESTORE VERIFYONLY FROM DISK = @path WITH CHECKSUM;", db))
                {
                    verify.CommandTimeout = 3600;
                    verify.Parameters.AddWithValue("@path", SqlPath(job));
                    await verify.ExecuteNonQueryAsync(token);
                }
                var file = new FileInfo(LocalPath(job));
                if (!file.Exists || file.Length == 0) throw new IOException("O arquivo gerado pelo SQL Server não está acessível na API.");
                await Audit(job, "BackupBancoConcluido", $"Backup completo verificado: {job.State.FileName}; {file.Length} bytes.");
                SetState(job, job.State with { Status = "concluido", SizeBytes = file.Length, ExpiresAt = DateTimeOffset.UtcNow.AddHours(6) });
            }
            catch (Exception error)
            {
                logger.LogError(error, "Falha no backup do banco central {BackupId}, solicitado pelo administrador {UserId}.", job.State.Id, job.UserId);
                DeleteFile(LocalPath(job));
                SetState(job, job.State with { Status = "falhou", Message = "Não foi possível gerar e verificar o backup. Verifique o espaço disponível, as permissões do SQL Server e a pasta compartilhada de backups." });
            }
            finally { lock (gate) activeId = null; }
        }
    }

    private void SetState(Job job, DatabaseBackupStatus state) { lock (gate) job.State = state; }

    private async Task Audit(Job job, string eventType, string description)
    {
        using var scope = scopes.CreateScope();
        await scope.ServiceProvider.GetRequiredService<AuditLogAB>().RegistrarAsync("empresa-principal", job.UserId,
            job.UserName, eventType, description, "DatabaseBackup", job.State.Id.ToString(), job.Ip);
    }

    private async Task Cleanup(CancellationToken token)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(30));
        do
        {
            try
            {
                lock (gate)
                {
                    var now = DateTimeOffset.UtcNow;
                    foreach (var pair in jobs.Where(pair => pair.Value.State.ExpiresAt <= now && pair.Key != activeId))
                    {
                        if (DeleteFile(LocalPath(pair.Value))) jobs.TryRemove(pair.Key, out _);
                    }
                    // Inclui cópias antigas de uma execução anterior da API. Somente arquivos criados por este serviço.
                    if (Directory.Exists(StorageDirectory))
                        foreach (var file in Directory.EnumerateFiles(StorageDirectory, "quack-pdv-completo-*.bak"))
                        {
                            var name = Path.GetFileNameWithoutExtension(file);
                            if (name.Length == 67 && Guid.TryParseExact(name[^32..], "N", out var id) && !jobs.ContainsKey(id) &&
                                File.GetLastWriteTimeUtc(file) < now.UtcDateTime.AddHours(-6)) DeleteFile(file);
                        }
                }
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            { logger.LogWarning(error, "A pasta de backups não está acessível para limpeza. A próxima execução tentará novamente."); }
        } while (await timer.WaitForNextTickAsync(token));
    }

    private bool DeleteFile(string file)
    {
        try { File.Delete(file); return true; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        { logger.LogWarning(error, "Não foi possível limpar o arquivo temporário de backup."); return false; }
    }
}
