using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;

namespace HORUSPDV_API.Controllers.Produtos;

[ApiController]
[Route("api/Produto/fiscal-ia")]
[HorusAuthorizeRoles("administrador", "gerente")]
public sealed class FiscalAiController(FiscalAiAB store, ProdutoAB products, EmpresaAB companies,
    FiscalReferenceTables tables, FiscalAiService service) : ControllerBase
{
    private AuthenticatedUser? UserSession => HttpContext.Items["CurrentUser"] as AuthenticatedUser;

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

    [HttpPost("{id}/analisar")]
    public async Task<IActionResult> Analyze(string id, CancellationToken ct)
    {
        if (UserSession is not { } user) return Unauthorized();
        Response.Headers.CacheControl = "no-store";
        var product = await products.ObterAsync(user.CompanyId, id);
        if (product is null) return ProblemResponse(404, "Produto não encontrado nesta empresa.");
        var company = await companies.ObterExataAsync(user.CompanyId);
        if (company is null || company.Uf != "RJ" || company.Crt is not (1 or 2 or 3 or 4)) return ProblemResponse(400, "Confira UF RJ e regime tributário em Minha Empresa.");
        try
        {
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
public sealed record FiscalAiApplyRequest(Guid AnaliseId, string[] Campos, bool Revisado);
