/**
 * Arquivo: API/NETCORE/Controllers/Gateway/GatewayTokenController.cs
 * Objetivo: token por loja do Local Gateway (Gateway → Cloud).
 *   - POST /api/GatewayToken             → admin/gerente gera um token (o texto volta UMA vez).
 *   - GET  /api/GatewayToken             → admin/gerente lista os tokens da empresa (sem o segredo).
 *   - POST /api/GatewayToken/{id}/revogar → admin/gerente revoga.
 *   - GET  /api/GatewayToken/ping        → o Gateway testa o token (só com token de Gateway).
 * Isolamento: CompanyId sempre do usuário/token autenticado; nunca por parâmetro livre.
 */
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Gateway;

[ApiController]
[Route("api/[controller]")]
public class GatewayTokenController(GatewayTokenAB gatewayTokenAB) : ControllerBase
{
    [HttpPost]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Gerente)]
    public async Task<IActionResult> Gerar([FromBody] GerarGatewayTokenRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser || HttpContext.Items["CurrentGateway"] is not null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var nome = request?.Nome?.Trim();
        if (string.IsNullOrWhiteSpace(nome) || nome.Length > 120)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe um nome para o token (até 120 caracteres), ex.: \"Loja Centro\"." });
        }

        var storeId = string.IsNullOrWhiteSpace(request!.StoreId) ? null : request.StoreId.Trim();
        var (token, raw) = await gatewayTokenAB.CreateAsync(currentUser.CompanyId, nome, storeId, currentUser.Name, cancellationToken);

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Token gerado. Copie agora: ele não será mostrado de novo.",
            Data = new { token = raw, info = token }
        });
    }

    [HttpGet]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Gerente)]
    public async Task<IActionResult> Listar(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser || HttpContext.Items["CurrentGateway"] is not null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var tokens = await gatewayTokenAB.ListAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<List<GatewayTokenDto>> { Success = true, Message = "Tokens do Gateway.", Data = tokens });
    }

    [HttpPost("{id}/revogar")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Gerente)]
    public async Task<IActionResult> Revogar(string id, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser || HttpContext.Items["CurrentGateway"] is not null)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var revoked = await gatewayTokenAB.RevokeAsync(currentUser.CompanyId, id, currentUser.Name, cancellationToken);
        return revoked
            ? Ok(new ApiResponse<object> { Success = true, Message = "Token revogado. O Gateway que usava esse token perde o acesso." })
            : NotFound(new ApiResponse<object> { Success = false, Message = "Token não encontrado." });
    }

    /// <summary>O Gateway confirma que o token é válido e descobre de qual empresa/loja ele é.</summary>
    [HttpGet("ping")]
    public IActionResult Ping()
    {
        if (HttpContext.Items["CurrentGateway"] is not GatewayTokenValidation gateway)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Use o token do Gateway (Authorization: Bearer qgw_...)." });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Token do Gateway válido.",
            Data = new
            {
                companyId = gateway.CompanyId,
                storeId = gateway.StoreId,
                tokenId = gateway.TokenId,
                serverTime = DateTimeOffset.UtcNow.ToString("o")
            }
        });
    }
}

public class GerarGatewayTokenRequest
{
    public string Nome { get; set; } = string.Empty;
    public string? StoreId { get; set; }
}
