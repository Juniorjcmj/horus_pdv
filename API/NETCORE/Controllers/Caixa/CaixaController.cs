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
    public async Task<IActionResult> Status(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var status = await caixaService.GetStatusAsync(currentUser, cancellationToken: cancellationToken);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Status do caixa obtido com sucesso.",
            Data = status
        });
    }

    [HttpPost("abrir")]
    public async Task<IActionResult> Abrir([FromBody] AbrirCaixaRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var result = await caixaService.AbrirAsync(request, currentUser, ResolveIp(), cancellationToken);
            if (result.IsReplay)
            {
                return Ok(new ApiResponse<object>
                {
                    Success = true,
                    Message = "Abertura de caixa já processada anteriormente (idempotente).",
                    Data = result
                });
            }

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Caixa aberto com sucesso.",
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

    [HttpPost("fechar")]
    public async Task<IActionResult> Fechar([FromBody] FecharCaixaRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var result = await caixaService.FecharAsync(request, currentUser, ResolveIp(), cancellationToken);
            if (result.IsReplay)
            {
                return Ok(new ApiResponse<object>
                {
                    Success = true,
                    Message = "Fechamento de caixa já processado anteriormente (idempotente).",
                    Data = result
                });
            }

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Caixa fechado com sucesso.",
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

    [HttpPost("movimento")]
    public async Task<IActionResult> Movimento([FromBody] RegistrarMovimentoCaixaRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var result = await caixaService.RegistrarMovimentoAsync(request, currentUser, ResolveIp(), cancellationToken);
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
