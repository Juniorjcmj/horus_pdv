/**
 * Arquivo: API/NETCORE/Controllers/Integracao/IntegracaoTokenController.cs
 * Objetivo: token de integração por empresa para agentes de IA usarem o servidor MCP (/mcp), somente leitura.
 *   - POST /api/IntegracaoToken              → admin/gerente gera um token (o texto volta UMA vez).
 *   - GET  /api/IntegracaoToken              → admin/gerente lista os tokens da empresa (sem o segredo).
 *   - POST /api/IntegracaoToken/{id}/revogar → admin/gerente revoga.
 * Isolamento: CompanyId sempre do usuário autenticado; nunca por parâmetro livre. Um token de integração
 *             não chega aqui: o HorusAuthMiddleware só o aceita em /mcp.
 */
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Integracao;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Gerente)]
public class IntegracaoTokenController(IntegracaoTokenAB integracaoTokenAB) : ControllerBase
{
    [HttpPost]
    public async Task<IActionResult> Gerar([FromBody] GerarIntegracaoTokenRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var nome = request?.Nome?.Trim();
        if (string.IsNullOrWhiteSpace(nome) || nome.Length > 120)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe um nome para o token (até 120 caracteres), ex.: \"Agente Hermes\"." });
        }

        var (token, raw) = await integracaoTokenAB.CreateAsync(currentUser.CompanyId, nome, currentUser.Name, cancellationToken);

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Token gerado. Copie agora: ele não será mostrado de novo.",
            Data = new { token = raw, info = token }
        });
    }

    [HttpGet]
    public async Task<IActionResult> Listar(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var tokens = await integracaoTokenAB.ListAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<List<IntegracaoTokenDto>> { Success = true, Message = "Tokens de integração.", Data = tokens });
    }

    [HttpPost("{id}/revogar")]
    public async Task<IActionResult> Revogar(string id, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var revoked = await integracaoTokenAB.RevokeAsync(currentUser.CompanyId, id, currentUser.Name, cancellationToken);
        return revoked
            ? Ok(new ApiResponse<object> { Success = true, Message = "Token revogado. O agente que usava esse token perde o acesso." })
            : NotFound(new ApiResponse<object> { Success = false, Message = "Token não encontrado." });
    }
}

public class GerarIntegracaoTokenRequest
{
    public string Nome { get; set; } = string.Empty;
}
