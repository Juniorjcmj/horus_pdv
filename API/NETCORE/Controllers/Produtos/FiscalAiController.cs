using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.SqlClient;
using System.Security.Cryptography;
using System.Text.Json;

namespace HORUSPDV_API.Controllers.Produtos;

[ApiController]
[Route("api/Produto/fiscal-ia")]
[HorusAuthorizeRoles("administrador", "gerente")]
public sealed class FiscalAiController(FiscalAiAB store, ProdutoAB products, EmpresaAB companies,
    FiscalReferenceTables tables, FiscalAiService service, ILogger<FiscalAiController> logger) : ControllerBase
{
    private AuthenticatedUser? UserSession => HttpContext.Items["CurrentUser"] as AuthenticatedUser;

    [HttpGet("resumos")]
    public async Task<IActionResult> Overview(CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        var data = await store.ListLatestAsync(user.CompanyId, ct);
        var current = await products.ListarAsync(user.CompanyId);
        var company = await companies.ObterExataAsync(user.CompanyId);
        return Ok(new ApiResponse<IReadOnlyList<FiscalAiProductStatus>> { Success = true,
            Data = FiscalAiOverview.Build(data, current, company, HORUSPDV_API.Services.Shared.HorusDateTime.Now) });
    }

    [HttpGet("relatorios/{analysisId:guid}")]
    public async Task<IActionResult> Report(Guid analysisId, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        var report = await store.GetReportAsync(user.CompanyId, analysisId, ct);
        return report is null ? ProblemResponse(404, "Análise não encontrada nesta empresa.")
            : Ok(new ApiResponse<FiscalAiSavedReport> { Success = true, Data = report });
    }

    [HttpGet("resumos/{id}")]
    public async Task<IActionResult> ProductOverview(string id, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        var product = await products.ObterAsync(user.CompanyId, id);
        if (product is null) return ProblemResponse(404, "Produto não encontrado nesta empresa.");
        var data = await store.ListLatestAsync(user.CompanyId, ct, id);
        var company = await companies.ObterExataAsync(user.CompanyId);
        return Ok(new ApiResponse<FiscalAiProductStatus> { Success = true,
            Data = FiscalAiOverview.Build(data, [product], company, HORUSPDV_API.Services.Shared.HorusDateTime.Now).SingleOrDefault() });
    }

    [HttpGet("config")]
    public async Task<IActionResult> Config(CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        var config = await store.GetConfigAsync(user.CompanyId, ct);
        return Ok(new ApiResponse<FiscalAiConfigStatus> { Success = true, Data = new(!string.IsNullOrWhiteSpace(config.Key), FiscalAiRules.Model, config.Jev) });
    }

    [HttpPut("config")]
    [HorusAuthorizeRoles("administrador")]
    public async Task<IActionResult> SaveConfig(FiscalAiConfigRequest request, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        try
        {
            await store.SaveConfigAsync(user.CompanyId, request.Chave, request.UsarJev, request.RemoverChave, ct);
            return await Config(ct);
        }
        catch (InvalidOperationException ex) { return ProblemResponse(400, ex.Message); }
    }

    [HttpPost("{id}/analises")]
    public async Task<IActionResult> StartAnalysis(string id, FiscalAiStartRequest request, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        try
        {
            if (await products.ObterAsync(user.CompanyId, id) is null) return ProblemResponse(404, "Produto não encontrado nesta empresa.");
            var jobId = await store.EnqueueAsync(user.CompanyId, id, user.Id, request.SolicitacaoId, ct);
            return Accepted(new ApiResponse<object> { Success = true, Data = new { Id = jobId } });
        }
        catch (FiscalAiConflict ex) { return ProblemResponse(409, ex.Message); }
        catch (InvalidOperationException ex) { return ProblemResponse(400, ex.Message); }
        catch (SqlException ex)
        {
            logger.LogError(ex, "Falha ao iniciar análise fiscal {RequestId}", HttpContext.TraceIdentifier);
            return ProblemResponse(503, $"A fila de análises está indisponível. Informe ao suporte a referência {HttpContext.TraceIdentifier}.");
        }
    }

    [HttpGet("analises/{jobId:guid}")]
    public async Task<IActionResult> AnalysisStatus(Guid jobId, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        var job = await store.GetJobAsync(user.CompanyId, jobId, ct);
        return job is null ? ProblemResponse(404, "Análise não encontrada nesta empresa.")
            : Ok(new ApiResponse<FiscalAiJobStatus> { Success = true, Data = job });
    }

    [HttpPost("{id}/analisar")]
    public async Task<IActionResult> Analyze(string id, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        try
        {
            var product = await products.ObterAsync(user.CompanyId, id);
            if (product is null) return ProblemResponse(404, "Produto não encontrado nesta empresa.");
            var company = await companies.ObterExataAsync(user.CompanyId);
            if (company is null || company.Uf != "RJ" || company.Crt is not (1 or 2 or 3 or 4)) return ProblemResponse(400, "Confira UF RJ e regime tributário em Minha Empresa.");
            var config = await store.GetConfigAsync(user.CompanyId, ct);
            var report = await service.AnalyzeAsync(product, company, await tables.GetAsync(ct), config.Key, config.Jev, ct);
            await store.SaveReportAsync(user.CompanyId, report, ct);
            return Ok(new ApiResponse<FiscalAiReport> { Success = true, Data = report });
        }
        catch (FiscalAiProviderError ex) { return ProblemResponse(502, ex.Message); }
        catch (InvalidOperationException ex) { return ProblemResponse(400, ex.Message); }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException)
        {
            ct.ThrowIfCancellationRequested();
            return ProblemResponse(504, "A consulta demorou ou a conexão falhou. Tente novamente; nenhum cadastro foi alterado.");
        }
        catch (Exception ex) when (ex is JsonException or KeyNotFoundException or ArgumentException)
        { return ProblemResponse(502, "O modelo retornou dados incompletos ou inválidos. Nenhum cadastro foi alterado."); }
        catch (CryptographicException ex)
        {
            logger.LogError(ex, "Não foi possível recuperar a chave da IA. Referência {RequestId}", HttpContext.TraceIdentifier);
            return ProblemResponse(400, "Não foi possível ler a chave OpenRouter salva. Peça ao administrador para salvá-la novamente em Configurar IA.");
        }
        catch (SqlException ex)
        {
            logger.LogError(ex, "Falha de banco na análise fiscal. Referência {RequestId}", HttpContext.TraceIdentifier);
            return ProblemResponse(503, $"Não foi possível consultar ou salvar os dados da análise. Informe ao suporte a referência {HttpContext.TraceIdentifier}.");
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Falha inesperada na análise fiscal. Referência {RequestId}", HttpContext.TraceIdentifier);
            return ProblemResponse(500, $"Não foi possível concluir a análise fiscal. Informe ao suporte a referência {HttpContext.TraceIdentifier}.");
        }
    }

    [HttpPost("{id}/aplicar")]
    public async Task<IActionResult> Apply(string id, FiscalAiApplyRequest request, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        if (!request.Revisado || request.Campos is null) return ProblemResponse(400, "Revise as sugestões antes de aplicar.");
        try
        {
            await store.ApplyAsync(user.CompanyId, id, request.AnaliseId, request.Campos, user.Id, user.Name,
                await tables.GetAsync(ct), service.Cests, ct);
            return Ok(new ApiResponse<object> { Success = true, Message = "Correções fiscais aplicadas e registradas no histórico." });
        }
        catch (FiscalAiConflict ex) { return ProblemResponse(409, ex.Message); }
        catch (InvalidOperationException ex) { return ProblemResponse(400, ex.Message); }
    }

    private ObjectResult ProblemResponse(int status, string message) => StatusCode(status, new ApiResponse<object> { Success = false, Message = message });
}

public sealed record FiscalAiConfigRequest(string? Chave, bool UsarJev = true, bool RemoverChave = false);
public sealed record FiscalAiStartRequest(Guid SolicitacaoId);
public sealed record FiscalAiApplyRequest(Guid AnaliseId, string[] Campos, bool Revisado);
