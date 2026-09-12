/**
 * Arquivo: API/NETCORE/Controllers/Usuario/UsuarioController.cs
 * Objetivo: expõe endpoints HTTP de contas de usuários e permissões e padroniza respostas para o frontend.
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras para serviços/repositórios.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Email;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Usuario;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente")]
public class UsuarioController(
    HorusSecurityStore securityStore,
    HorusEmailService emailService,
    IWebHostEnvironment environment,
    ILogger<UsuarioController> logger) : ControllerBase
{
    [HttpGet]
    public IActionResult Listar()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Usuarios obtidos com sucesso.",
            Data = securityStore.ListUsers(currentUser.CompanyId)
        });
    }

    [HttpPost]
    public IActionResult Criar([FromBody] UsuarioRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var user = securityStore.CreateUser(request, currentUser.CompanyId);
            return StatusCode(StatusCodes.Status201Created, new ApiResponse<object>
            {
                Success = true,
                Message = "Usuario criado com sucesso.",
                Data = user
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpPut("{id}")]
    public IActionResult Atualizar(string id, [FromBody] UsuarioRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var user = securityStore.UpdateUser(id, request, currentUser.CompanyId);
            if (user is null)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Usuário não encontrado." });
            }

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Usuario atualizado com sucesso.",
                Data = user
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpPatch("{id}/status")]
    public IActionResult AlterarStatus(string id, [FromBody] UsuarioStatusRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var user = securityStore.UpdateStatus(id, request.Status, currentUser.CompanyId);
        if (user is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Usuário não encontrado." });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Status atualizado com sucesso.",
            Data = user
        });
    }

    [HttpPost("{id}/resetar-senha")]
    public async Task<IActionResult> ResetarSenha(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var emailEnabled = await emailService.IsEnabledAsync();
        if (!emailEnabled && !environment.IsDevelopment())
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new ApiResponse<object>
            {
                Success = false,
                Message = "Reset de senha indisponível. Configure o envio de e-mail."
            });
        }

        var result = securityStore.CreateAdminPasswordResetToken(
            id,
            currentUser.CompanyId,
            GetClientIp(),
            Request.Headers.UserAgent.ToString());
        if (result is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Usuário não encontrado." });
        }

        if (!string.IsNullOrWhiteSpace(result.ResetToken) && result.ExpiresAt is not null && emailEnabled)
        {
            var resetUrl = emailService.BuildPasswordResetUrl(result.ResetToken);
            try
            {
                await emailService.SendPasswordResetEmailAsync(
                    result.User.Email,
                    "Quack PDV",
                    resetUrl,
                    result.ExpiresAt.Value,
                    HttpContext.RequestAborted);
            }
            catch (Exception ex)
            {
                securityStore.ConsumePasswordResetToken(
                    result.ResetToken,
                    GetClientIp(),
                    Request.Headers.UserAgent.ToString());
                logger.LogWarning(ex, "Não foi possível enviar e-mail de reset administrativo para {Email}.", result.User.Email);
                return StatusCode(StatusCodes.Status502BadGateway, new ApiResponse<object>
                {
                    Success = false,
                    Message = "Não foi possível enviar o e-mail de redefinição de senha."
                });
            }
        }

        object responseData = emailEnabled || !environment.IsDevelopment()
            ? new
            {
                result.User,
                result.Accepted,
                result.MaskedEmail,
                result.ExpiresAt
            }
            : result;

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Redefinição de senha gerada com sucesso.",
            Data = responseData
        });
    }

    public class UsuarioStatusRequest
    {
        public string Status { get; set; } = "ativo";
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;

    private string GetClientIp()
    {
        var securityOptions = HttpContext.RequestServices.GetRequiredService<HorusSecurityOptions>();
        return HorusClientIpResolver.Resolve(HttpContext, securityOptions, "0.0.0.0");
    }
}
