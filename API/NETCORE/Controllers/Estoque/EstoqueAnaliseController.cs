/**
 * Arquivo: API/NETCORE/Controllers/Estoque/EstoqueAnaliseController.cs
 * Objetivo: endpoints da análise de estoque (api/Estoque/analise/*). Nesta etapa: fotografia diária sob demanda.
 *           Os demais blocos (kpis, séries, ABC, reposição...) entram nas etapas seguintes.
 */
using System.Globalization;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Estoque;

[ApiController]
[Route("api/Estoque/analise")]
public class EstoqueAnaliseController(EstoqueSnapshotAB snapshots) : ControllerBase
{
    /// <summary>Grava (ou reconstrói) a fotografia de um dia (padrão: ontem). Idempotente.</summary>
    [HttpPost("snapshot")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Snapshot([FromQuery] string? data = null)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser u)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var dia = EstoqueSnapshotAB.Ontem();
        if (!string.IsNullOrWhiteSpace(data))
        {
            if (!DateOnly.TryParseExact(data, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out dia))
                return BadRequest(new ApiResponse<object> { Success = false, Message = "Data inválida. Use AAAA-MM-DD." });
            if (dia > EstoqueSnapshotAB.Ontem())
                return BadRequest(new ApiResponse<object> { Success = false, Message = "A fotografia só existe para dias já encerrados." });
        }

        var linhas = await snapshots.GravarAsync(u.CompanyId, dia, HttpContext.RequestAborted);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Fotografia do estoque gravada.",
            Data = new { data = dia.ToString("yyyy-MM-dd"), produtos = linhas },
        });
    }
}
