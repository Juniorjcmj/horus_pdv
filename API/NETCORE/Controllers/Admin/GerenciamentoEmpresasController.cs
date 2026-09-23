/**
 * Arquivo: API/NETCORE/Controllers/Admin/GerenciamentoEmpresasController.cs
 * Objetivo: expõe endpoints exclusivos de SuperAdmin para aprovação, rejeição, bloqueio e auditoria de empresas cadastradas na plataforma.
 * Restrição de Acesso: Apenas usuários com CompanyId == 'empresa-principal' e Role == 'administrador'.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Admin;

[ApiController]
[Route("api/Admin/Empresas")]
[HorusAuthorizeRoles("administrador")]
public class GerenciamentoEmpresasController(
    HorusSecurityStore securityStore,
    ILogger<GerenciamentoEmpresasController> logger) : ControllerBase
{
    [HttpGet]
    public IActionResult Listar(
        [FromQuery] string? search,
        [FromQuery] string? status,
        [FromQuery] int page = 1,
        [FromQuery] int pageSize = 20)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        try
        {
            var result = securityStore.ListCompaniesAdmin(search, status, page, pageSize);
            return Ok(new ApiResponse<EmpresasAdminListResult>
            {
                Success = true,
                Message = "Empresas listadas com sucesso.",
                Data = result
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao listar empresas no painel administrativo.");
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao buscar empresas."
            });
        }
    }

    [HttpGet("metricas")]
    public IActionResult ObterMetricas()
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        try
        {
            var metricas = securityStore.GetCompaniesMetrics();
            return Ok(new ApiResponse<EmpresasMetricasDto>
            {
                Success = true,
                Message = "Métricas obtidas com sucesso.",
                Data = metricas
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao obter métricas de empresas.");
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao obter métricas."
            });
        }
    }

    [HttpPost("{id}/aprovar")]
    public IActionResult Aprovar([FromRoute] string id)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        if (string.IsNullOrWhiteSpace(id) || string.Equals(id, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa inválida para aprovação." });
        }

        try
        {
            var ok = securityStore.ApproveCompany(id, adminUser.Name);
            if (!ok)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Empresa não encontrada ou já aprovada." });
            }

            logger.LogInformation("Empresa {CompanyId} aprovada pelo SuperAdmin {AdminName}.", id, adminUser.Name);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Empresa aprovada com sucesso! O acesso aos usuários foi liberado."
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao aprovar empresa {CompanyId}.", id);
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao aprovar empresa."
            });
        }
    }

    [HttpPost("{id}/rejeitar")]
    public IActionResult Rejeitar([FromRoute] string id, [FromBody] RejeitarEmpresaRequest request)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        if (string.IsNullOrWhiteSpace(id) || string.Equals(id, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa inválida para rejeição." });
        }

        if (string.IsNullOrWhiteSpace(request.Reason))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe o motivo da recusa/rejeição." });
        }

        try
        {
            var ok = securityStore.RejectCompany(id, request.Reason, adminUser.Name);
            if (!ok)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Empresa não encontrada." });
            }

            logger.LogInformation("Empresa {CompanyId} rejeitada pelo SuperAdmin {AdminName}. Motivo: {Reason}", id, adminUser.Name, request.Reason);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Inscrição da empresa rejeitada. O acesso de login permanece bloqueado."
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao rejeitar empresa {CompanyId}.", id);
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao rejeitar empresa."
            });
        }
    }

    [HttpPost("{id}/bloquear")]
    public IActionResult Bloquear([FromRoute] string id, [FromBody] BloquearEmpresaRequest request)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        if (string.IsNullOrWhiteSpace(id) || string.Equals(id, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa inválida para bloqueio." });
        }

        if (string.IsNullOrWhiteSpace(request.Reason))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe o motivo do bloqueio." });
        }

        try
        {
            var ok = securityStore.BlockCompany(id, request.Reason, adminUser.Name);
            if (!ok)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Empresa não encontrada." });
            }

            logger.LogInformation("Empresa {CompanyId} bloqueada pelo SuperAdmin {AdminName}. Motivo: {Reason}", id, adminUser.Name, request.Reason);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Empresa suspensa/bloqueada com sucesso. Todas as sessões ativas foram revogadas."
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao bloquear empresa {CompanyId}.", id);
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao bloquear empresa."
            });
        }
    }

    [HttpPost("{id}/reativar")]
    public IActionResult Reativar([FromRoute] string id)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        if (string.IsNullOrWhiteSpace(id) || string.Equals(id, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa inválida para reativação." });
        }

        try
        {
            var ok = securityStore.ReactivateCompany(id, adminUser.Name);
            if (!ok)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Empresa não encontrada." });
            }

            logger.LogInformation("Empresa {CompanyId} reativada pelo SuperAdmin {AdminName}.", id, adminUser.Name);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Empresa reativada com sucesso! O acesso foi restabelecido."
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao reativar empresa {CompanyId}.", id);
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao reativar empresa."
            });
        }
    }

    [HttpGet("configuracao")]
    public IActionResult ObterConfiguracao()
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        var requireApproval = securityStore.IsApprovalRequiredForNewCompanies();
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Configuração obtida com sucesso.",
            Data = new { requireApprovalForNewCompanies = requireApproval }
        });
    }

    [HttpPut("configuracao")]
    public IActionResult AtualizarConfiguracao([FromBody] AtualizarConfiguracaoPlataformaRequest request)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        try
        {
            securityStore.SetApprovalRequiredForNewCompanies(request.RequireApprovalForNewCompanies);
            logger.LogInformation(
                "SuperAdmin {AdminName} alterou RequireApprovalForNewCompanies para {Value}.",
                adminUser.Name,
                request.RequireApprovalForNewCompanies);

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = request.RequireApprovalForNewCompanies
                    ? "Aprovação prévia ativada: novos cadastros ficarão pendentes até sua análise."
                    : "Aprovação automática ativada: novos cadastros terão acesso imediato.",
                Data = new { requireApprovalForNewCompanies = request.RequireApprovalForNewCompanies }
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao atualizar configuração de aprovação de empresas.");
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao atualizar configuração."
            });
        }
    }

    [HttpPut("{id}/credenciais")]
    public IActionResult AlterarCredenciais([FromRoute] string id, [FromBody] AlterarCredenciaisEmpresaRequest request)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        if (string.IsNullOrWhiteSpace(id) || string.Equals(id, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa inválida para esta operação." });
        }

        try
        {
            var (success, message) = securityStore.UpdateCompanyAdminCredentials(id, request.NewEmail, request.NewPassword);
            if (!success)
            {
                return BadRequest(new ApiResponse<object> { Success = false, Message = message });
            }

            logger.LogInformation(
                "Credenciais do admin da empresa {CompanyId} alteradas pelo SuperAdmin {AdminName}.",
                id, adminUser.Name);

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = message
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao alterar credenciais da empresa {CompanyId}.", id);
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao alterar credenciais."
            });
        }
    }

    [HttpDelete("{id}")]
    public IActionResult Excluir([FromRoute] string id)
    {
        var adminUser = GetSuperAdminUser(out var errorResult);
        if (adminUser is null) return errorResult!;

        if (string.IsNullOrWhiteSpace(id) || string.Equals(id, "empresa-principal", StringComparison.OrdinalIgnoreCase))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa inválida para exclusão." });
        }

        try
        {
            var ok = securityStore.DeleteCompany(id);
            if (!ok)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Empresa não encontrada." });
            }

            logger.LogInformation("Empresa {CompanyId} excluída permanentemente pelo SuperAdmin {AdminName}.", id, adminUser.Name);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Empresa e todos os seus dados foram excluídos permanentemente."
            });
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Erro ao excluir empresa {CompanyId}.", id);
            return StatusCode(StatusCodes.Status500InternalServerError, new ApiResponse<object>
            {
                Success = false,
                Message = "Erro interno ao excluir empresa."
            });
        }
    }

    private AuthenticatedUser? GetSuperAdminUser(out IActionResult? errorResult)
    {
        errorResult = null;
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)
        {
            errorResult = Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
            return null;
        }

        var isSuperAdmin =
            string.Equals(user.CompanyId, "empresa-principal", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(user.Email, "jotacfs2010@hotmail.com", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(user.Email, "jotanaval2009@gmail.com", StringComparison.OrdinalIgnoreCase) ||
            string.Equals(user.Email, "flavio@hpdv.com.br", StringComparison.OrdinalIgnoreCase);

        if (!isSuperAdmin || !string.Equals(user.Role, "administrador", StringComparison.OrdinalIgnoreCase))
        {
            errorResult = StatusCode(StatusCodes.Status403Forbidden, new ApiResponse<object>
            {
                Success = false,
                Message = "Acesso negado: módulo restrito ao Administrador Geral da Plataforma."
            });
            return null;
        }

        return user;
    }
}
