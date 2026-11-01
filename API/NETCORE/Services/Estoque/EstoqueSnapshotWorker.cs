/**
 * Arquivo: API/NETCORE/Services/Estoque/EstoqueSnapshotWorker.cs
 * Objetivo: grava a fotografia diária do estoque (ontem, no horário de Brasília) das empresas que ainda não a têm.
 *           Acorda a cada 30 minutos; MERGE idempotente, então rodar mais de uma vez no mesmo dia não duplica.
 */
using HORUSPDV_API.Repositories.DatabaseAccess;

namespace HORUSPDV_API.Services.Estoque;

public sealed class EstoqueSnapshotWorker(IServiceScopeFactory scopeFactory, ILogger<EstoqueSnapshotWorker> logger) : BackgroundService
{
    private static readonly TimeSpan Intervalo = TimeSpan.FromMinutes(30);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Pequena espera para a migração terminar antes do primeiro ciclo.
        try { await Task.Delay(TimeSpan.FromSeconds(20), stoppingToken); } catch (OperationCanceledException) { return; }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await ExecutarCicloAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Erro no ciclo do EstoqueSnapshotWorker.");
            }

            try { await Task.Delay(Intervalo, stoppingToken); } catch (OperationCanceledException) { break; }
        }
    }

    private async Task ExecutarCicloAsync(CancellationToken ct)
    {
        using var scope = scopeFactory.CreateScope();
        var snapshots = scope.ServiceProvider.GetRequiredService<EstoqueSnapshotAB>();
        var ontem = EstoqueSnapshotAB.Ontem();
        foreach (var companyId in await snapshots.EmpresasSemSnapshotAsync(ontem, ct))
        {
            var linhas = await snapshots.GravarAsync(companyId, ontem, ct);
            logger.LogInformation("Fotografia do estoque de {Data} gravada para {Empresa}: {Linhas} produtos.", ontem, companyId, linhas);
        }
    }
}
