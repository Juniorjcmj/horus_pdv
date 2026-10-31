/**
 * Arquivo: API/NETCORE/Controllers/Estoque/EstoqueRelatorioController.cs
 * Objetivo: relatórios de apoio à compra (api/Estoque/relatorios/*): reposição por giro, curva ABC, crítico, parado,
 *           validade e perdas. Valores (custo, faturamento) só para gerente/administrador; o estoquista recebe
 *           apenas reposição, crítico e validade, sem campos de valor.
 */
using System.Globalization;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Estoque;

[ApiController]
[Route("api/Estoque/relatorios")]
public class EstoqueRelatorioController(EstoqueRelatorioAB relatorios) : ControllerBase
{
    [HttpGet("reposicao")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Reposicao([FromQuery] int dias = 30, [FromQuery] int cobertura = 15, [FromQuery] string? categoriaId = null)
    {
        if (Usuario() is not { } u) return Sessao();
        return Ok(Resp("Sugestão de reposição.", await relatorios.ReposicaoAsync(u.CompanyId, dias, cobertura, categoriaId, ComValores(u))));
    }

    [HttpGet("curva-abc")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> CurvaAbc([FromQuery] string? de = null, [FromQuery] string? ate = null,
        [FromQuery] string? categoriaId = null, [FromQuery] decimal a = 80, [FromQuery] decimal b = 95)
    {
        if (Usuario() is not { } u) return Sessao();
        var (resultado, erro) = await relatorios.CurvaAbcAsync(u.CompanyId, Data(de), Data(ate), categoriaId, a, b);
        return erro is not null
            ? BadRequest(new ApiResponse<object> { Success = false, Message = erro })
            : Ok(Resp("Curva ABC.", resultado!));
    }

    [HttpGet("critico")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Critico()
    {
        if (Usuario() is not { } u) return Sessao();
        return Ok(Resp("Estoque crítico.", await relatorios.CriticoAsync(u.CompanyId, ComValores(u))));
    }

    [HttpGet("parado")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Parado([FromQuery] int dias = 60)
    {
        if (Usuario() is not { } u) return Sessao();
        return Ok(Resp("Estoque parado.", await relatorios.ParadoAsync(u.CompanyId, dias, true)));
    }

    [HttpGet("validade")]
    [HorusAuthorizeRoles("administrador", "gerente", "estoquista")]
    public async Task<IActionResult> Validade([FromQuery] int dias = 30)
    {
        if (Usuario() is not { } u) return Sessao();
        return Ok(Resp("Lotes vencidos e a vencer.", await relatorios.ValidadeAsync(u.CompanyId, dias, ComValores(u))));
    }

    [HttpGet("perdas")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Perdas([FromQuery] string? de = null, [FromQuery] string? ate = null)
    {
        if (Usuario() is not { } u) return Sessao();
        return Ok(Resp("Perdas do período.", await relatorios.PerdasAsync(u.CompanyId, Data(de), Data(ate), true)));
    }

    private static ApiResponse<object> Resp(string mensagem, object dados) =>
        new() { Success = true, Message = mensagem, Data = dados };

    private static DateTime? Data(string? valor) =>
        DateTime.TryParse(valor, CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d.Date : null;

    private static bool ComValores(AuthenticatedUser u) => HorusRoles.IsGerenteOuAdmin(u.Role);

    private AuthenticatedUser? Usuario() => HttpContext.Items["CurrentUser"] as AuthenticatedUser;

    private IActionResult Sessao() => Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
}
