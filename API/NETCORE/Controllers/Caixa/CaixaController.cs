/**
 * Arquivo: API/NETCORE/Controllers/Caixa/CaixaController.cs
 * Objetivo: expõe endpoints HTTP de abertura, fechamento e status de caixa e padroniza respostas para o frontend.
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras para serviços/repositórios.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Caixa;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Caixa;

[ApiController]
[Route("api/[controller]")]
public class CaixaController(HorusCaixaService caixaService, HorusSecurityOptions securityOptions) : ControllerBase
{
    private string ResolveIp() => HorusClientIpResolver.Resolve(HttpContext, securityOptions);

    [HttpGet("status")]
    public IActionResult Status()
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Status do caixa obtido com sucesso.",
            Data = caixaService.GetStatus(currentUser)
        });
    }

    [HttpPost("abrir")]
    public IActionResult Abrir([FromBody] AbrirCaixaRequest request)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Caixa aberto com sucesso.",
                Data = caixaService.Abrir(request, currentUser, ResolveIp())
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpPost("fechar")]
    public IActionResult Fechar([FromBody] FecharCaixaRequest request)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Caixa fechado com sucesso.",
                Data = caixaService.Fechar(request, currentUser, ResolveIp())
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpPost("movimento")]
    public async Task<IActionResult> Movimento([FromBody] RegistrarMovimentoCaixaRequest request)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var result = await caixaService.RegistrarMovimentoAsync(request, currentUser, ResolveIp());
            if (result.IsReplay)
            {
                return Ok(new ApiResponse<object>
                {
                    Success = true,
                    Message = "Movimento de caixa já processado anteriormente (idempotente).",
                    Data = result
                });
            }

            return StatusCode(StatusCodes.Status201Created, new ApiResponse<object>
            {
                Success = true,
                Message = "Movimento de caixa registrado com sucesso.",
                Data = result
            });
        }
        catch (IdempotencyConflictException ex)
        {
            return StatusCode(StatusCodes.Status409Conflict, new ApiResponse<object>
            {
                Success = false,
                Message = ex.Message
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }
}
