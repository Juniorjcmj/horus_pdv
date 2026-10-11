using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Fiscal;

public sealed record FiscalAiSavedReport(FiscalAiReport Relatorio, DateTime CriadaEm, bool Aplicada);
public sealed record FiscalAiLatestJob(Guid Id, string ProdutoId, string Status, string? Mensagem, DateTime CriadaEm);
public sealed record FiscalAiOverviewData(IReadOnlyList<FiscalAiSavedReport> Analises, IReadOnlyList<FiscalAiLatestJob> Solicitacoes);
public sealed record FiscalAiProductStatus(string ProdutoId, string Situacao, Guid? AnaliseId, DateTimeOffset? AnalisadoEm,
    Guid? SolicitacaoId, string Mensagem, bool Aplicada);

public static class FiscalAiOverview
{
    public static FiscalAiProductStatus Classify(FiscalAiSavedReport saved, ProdutoAD product, EmpresaAD? company, DateTimeOffset now)
    {
        var report = saved.Relatorio;
        FiscalAiProductStatus Status(string code, string message) => new(product.Id, code, report.Id, report.AnalisadoEm, null, message, saved.Aplicada);
        var snapshot = FiscalAiRules.Snapshot(product);
        if (saved.Aplicada) return Status("desatualizada", "Correções aplicadas. Analise novamente para conferir o cadastro atualizado.");
        if (company is null || report.Crt != company.Crt || report.Uf != company.Uf
            || HorusDateTime.ToBrasilia(report.AnalisadoEm).Year != HorusDateTime.ToBrasilia(now).Year
            || snapshot.Count != report.CadastroOriginal.Count
            || snapshot.Any(p => !report.CadastroOriginal.TryGetValue(p.Key, out var previous) || p.Value != previous))
            return Status("desatualizada", "O cadastro, o regime, a UF ou o período fiscal mudou. Faça uma nova análise.");
        if (report.ErrosCadastro is null) return Status("desatualizada", "Relatório anterior à identificação de situação. Analise novamente para atualizar a conferência.");
        if (report.Pendencias.Length > 0 || report.Sugestoes.Any(s => !s.PodeAplicar) || report.ErrosCadastro.Count > 0)
            return Status("revisao", "R — Há pendências, erros de cadastro ou sugestões que exigem revisão contábil. Abra o relatório.");
        if (report.Sugestoes.Count > 0) return Status("corrigir", "A análise sugeriu ajustes no cadastro. Abra o relatório para revisar.");
        return Status("ok", "OK — Nenhum apontamento nesta análise. Consulte o relatório e a data da conferência.");
    }

    public static IReadOnlyList<FiscalAiProductStatus> Build(FiscalAiOverviewData data, IReadOnlyList<ProdutoAD> products, EmpresaAD? company, DateTimeOffset now)
    {
        var current = products.ToDictionary(p => p.Id);
        var saved = data.Analises.Where(a => current.ContainsKey(a.Relatorio.ProdutoId)).ToDictionary(a => a.Relatorio.ProdutoId);
        var result = saved.ToDictionary(p => p.Key, p => Classify(p.Value, current[p.Key], company, now));
        foreach (var job in data.Solicitacoes.Where(j => current.ContainsKey(j.ProdutoId)))
        {
            if (job.Status is not ("aguardando" or "analisando" or "falha")) continue;
            if (saved.TryGetValue(job.ProdutoId, out var analysis) && job.CriadaEm <= analysis.CriadaEm) continue;
            result.TryGetValue(job.ProdutoId, out var previous);
            result[job.ProdutoId] = new(job.ProdutoId, job.Status, previous?.AnaliseId, previous?.AnalisadoEm, job.Id,
                job.Mensagem ?? (job.Status == "aguardando" ? "Aguardando a vez da análise…" : "A IA está analisando este produto…"), previous?.Aplicada ?? false);
        }
        return result.Values.ToArray();
    }
}
