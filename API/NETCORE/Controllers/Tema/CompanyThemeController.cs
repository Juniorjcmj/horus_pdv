/**
 * Arquivo: API/NETCORE/Controllers/Tema/CompanyThemeController.cs
 * Objetivo: cores de acento da empresa — cor do sistema e cor da frente de caixa (PDV).
 *   - GET  /api/company-theme  → qualquer usuário autenticado lê o tema da SUA empresa.
 *   - PUT  /api/company-theme  → admin/gerente define as cores (hex #rrggbb) ou limpa (null → padrão).
 * Isolamento: CompanyId sempre derivado do usuário autenticado. Aditivo; nenhum endpoint existente muda.
 */
using System.Text.RegularExpressions;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Tema;

[ApiController]
[Route("api/company-theme")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente")]
public partial class CompanyThemeController(EmpresaTemaAB temaAB) : ControllerBase
{
    [GeneratedRegex("^#[0-9a-fA-F]{6}$")]
    private static partial Regex HexColorRegex();

    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var tema = await temaAB.GetByCompanyAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = tema is null ? "Sem tema personalizado." : "Tema da empresa.",
            Data = tema is null ? null : new
            {
                systemAccent = tema.SystemAccent,
                pdvAccent = tema.PdvAccent,
                updatedAt = tema.UpdatedAt.ToString("o")
            }
        });
    }

    [HttpPut]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Put([FromBody] CompanyThemeRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var system = Normalize(request?.SystemAccent);
        var pdv = Normalize(request?.PdvAccent);

        if (!IsValid(system) || !IsValid(pdv))
        {
            return BadRequest(new ApiResponse<object>
            {
                Success = false,
                Message = "Cor inválida. Use formato hexadecimal #rrggbb (ex.: #0369a1) ou vazio para o padrão."
            });
        }

        var saved = await temaAB.UpsertAsync(currentUser.CompanyId, system, pdv, currentUser.Id, cancellationToken);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Tema salvo.",
            Data = new
            {
                systemAccent = saved.SystemAccent,
                pdvAccent = saved.PdvAccent,
                updatedAt = saved.UpdatedAt.ToString("o")
            }
        });
    }

    private static string? Normalize(string? value)
        => string.IsNullOrWhiteSpace(value) ? null : value.Trim();

    private static bool IsValid(string? color)
        => color is null || HexColorRegex().IsMatch(color);
}

public class CompanyThemeRequest
{
    public string? SystemAccent { get; set; }
    public string? PdvAccent { get; set; }
}
