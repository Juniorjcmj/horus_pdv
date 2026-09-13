/**
 * Arquivo: API/NETCORE/Controllers/Fiado/FiadoController.cs
 * Objetivo: expõe endpoints REST para operações de fiado, recebimentos, extrato e cobrança de clientes.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Fiado;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Fiado;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente", "caixa")]
public class FiadoController(IFiadoService fiadoService) : ControllerBase
{
    [HttpPost("receber")]
    [ProducesResponseType(typeof(ApiResponse<FiadoMovimentoAD>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<FiadoMovimentoAD>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Receber([FromBody] RecebimentoFiadoRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<FiadoMovimentoAD> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var mov = await fiadoService.ReceberAsync(currentUser.CompanyId, currentUser.Id, currentUser.Name, request);
            return Ok(new ApiResponse<FiadoMovimentoAD>
            {
                Success = true,
                Message = "Recebimento registrado com sucesso.",
                Data = mov
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<FiadoMovimentoAD> { Success = false, Message = ex.Message });
        }
    }

    [HttpGet("extrato/{clienteId}")]
    [ProducesResponseType(typeof(ApiResponse<List<FiadoMovimentoAD>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> ObterExtrato(
        string clienteId,
        [FromQuery] DateTimeOffset? dataInicio = null,
        [FromQuery] DateTimeOffset? dataFim = null)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<FiadoMovimentoAD>> { Success = false, Message = "Sessão não encontrada." });

        var extrato = await fiadoService.ObterExtratoAsync(currentUser.CompanyId, clienteId, dataInicio, dataFim);
        return Ok(new ApiResponse<List<FiadoMovimentoAD>>
        {
            Success = true,
            Message = "Extrato obtido com sucesso.",
            Data = extrato
        });
    }

    [HttpGet("devedores")]
    [ProducesResponseType(typeof(ApiResponse<List<FiadoDevedorAD>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> ListarDevedores([FromQuery] string? busca = null)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<FiadoDevedorAD>> { Success = false, Message = "Sessão não encontrada." });

        var devedores = await fiadoService.ListarDevedoresAsync(currentUser.CompanyId, busca);
        return Ok(new ApiResponse<List<FiadoDevedorAD>>
        {
            Success = true,
            Message = "Lista de devedores obtida com sucesso.",
            Data = devedores
        });
    }

    [HttpGet("resumo")]
    [ProducesResponseType(typeof(ApiResponse<FiadoResumoAD>), StatusCodes.Status200OK)]
    public async Task<IActionResult> ObterResumo()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<FiadoResumoAD> { Success = false, Message = "Sessão não encontrada." });

        var resumo = await fiadoService.ObterResumoAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<FiadoResumoAD>
        {
            Success = true,
            Message = "Resumo financeiro de fiado obtido com sucesso.",
            Data = resumo
        });
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
