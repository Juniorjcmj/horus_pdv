using System.Security.Cryptography;
using System.Text.Json;
using HORUSPDV_API.Repositories.DatabaseAccess;

namespace HORUSPDV_API.Services.Fiscal;

public sealed record FiscalAiJob(Guid Id, string CompanyId, string ProductId);
public sealed record FiscalAiJobStatus(Guid Id, string ProdutoId, string Status, string? Mensagem, FiscalAiReport? Relatorio);

/// <summary>Fila persistente: cada análise é assumida uma vez, fora do prazo da conexão HTTP.</summary>
public sealed class FiscalAiWorker(IServiceScopeFactory scopes, ILogger<FiscalAiWorker> logger) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) => Task.WhenAll(RunAsync(stoppingToken), RunAsync(stoppingToken));

    private async Task RunAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var store = scope.ServiceProvider.GetRequiredService<FiscalAiAB>();
                var job = await store.ClaimJobAsync(stoppingToken);
                if (job is null) { await Task.Delay(1500, stoppingToken); continue; }
                using var deadline = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                deadline.CancelAfter(TimeSpan.FromMinutes(4));
                try
                {
                    var product = await scope.ServiceProvider.GetRequiredService<ProdutoAB>().ObterAsync(job.CompanyId, job.ProductId)
                        ?? throw new InvalidOperationException("Produto não encontrado nesta empresa.");
                    var company = await scope.ServiceProvider.GetRequiredService<EmpresaAB>().ObterExataAsync(job.CompanyId);
                    if (company is null || company.Uf != "RJ" || company.Crt is not (1 or 2 or 3 or 4))
                        throw new InvalidOperationException("Confira UF RJ e regime tributário em Minha Empresa.");
                    var config = await store.GetConfigAsync(job.CompanyId, deadline.Token);
                    var tables = await scope.ServiceProvider.GetRequiredService<FiscalReferenceTables>().GetAsync(deadline.Token);
                    var report = await scope.ServiceProvider.GetRequiredService<FiscalAiService>().AnalyzeAsync(product, company, tables, config.Key, config.Jev, deadline.Token);
                    await store.CompleteJobAsync(job, report, deadline.Token);
                }
                catch (Exception ex)
                {
                    logger.LogError(ex, "Falha na solicitação fiscal {JobId}", job.Id);
                    var message = ex switch {
                        FiscalAiProviderError or InvalidOperationException => ex.Message,
                        CryptographicException => "Não foi possível ler a chave OpenRouter. Salve-a novamente em Configurar IA.",
                        OperationCanceledException => "A análise foi interrompida ou excedeu o prazo. Nenhum cadastro foi alterado.",
                        HttpRequestException => "Não foi possível consultar o provedor de IA. Tente novamente.",
                        JsonException or KeyNotFoundException or ArgumentException => "O modelo retornou dados inválidos. Nenhum cadastro foi alterado.",
                        _ => $"Não foi possível concluir a análise. Informe ao suporte a referência {job.Id}."
                    };
                    using var saveDeadline = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                    await store.FailJobAsync(job, message, saveDeadline.Token);
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex)
            {
                logger.LogError(ex, "Falha ao consultar a fila fiscal");
                try { await Task.Delay(5000, stoppingToken); } catch (OperationCanceledException) { break; }
            }
        }
    }
}
