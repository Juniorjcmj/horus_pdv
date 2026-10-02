/**
 * Arquivo: API/NETCORE/Controllers/Gateway/GatewayConfigController.cs
 * Objetivo: expõe o endereço LAN do Local Gateway (Quack Gateway) da empresa.
 *   - GET  /api/gateway-config  → terminal autenticado lê o endereço da SUA empresa (aprende online).
 *   - PUT  /api/gateway-config  → admin/gerente cadastra/edita/desabilita o endereço.
 * Isolamento: CompanyId sempre derivado do usuário autenticado; nunca por parâmetro livre.
 * Aditivo: nenhum endpoint existente é alterado; a Cloud continua a autoridade.
 */
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Gateway;

[ApiController]
[Route("api/gateway-config")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
public class GatewayConfigController(LojaGatewayConfigAB gatewayConfigAB) : ControllerBase
{
    /// <summary>Terminal autenticado obtém o endereço do Gateway da sua empresa (ou nulo se não houver).</summary>
    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var config = await gatewayConfigAB.GetByCompanyAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = config is null ? "Nenhum Gateway cadastrado para a empresa." : "Endereço do Gateway.",
            Data = config is null ? null : new
            {
                gatewayUrl = config.GatewayUrl,
                enabled = config.Enabled,
                storeId = config.StoreId,
                updatedAt = config.UpdatedAt.ToString("o")
            }
        });
    }

    /// <summary>Admin/gerente cadastra ou edita o endereço do Gateway da empresa.</summary>
    [HttpPut]
    [HorusAuthorizeRoles("administrador", "gerente")]
    public async Task<IActionResult> Put([FromBody] GatewayConfigRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        if (request is null || string.IsNullOrWhiteSpace(request.GatewayUrl))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "gatewayUrl é obrigatório." });
        }

        if (!IsValidGatewayUrl(request.GatewayUrl))
        {
            return BadRequest(new ApiResponse<object>
            {
                Success = false,
                Message = "gatewayUrl inválido. Use http(s)://host-ou-ip:porta (ex.: https://quack-gateway.local:5443)."
            });
        }

        var saved = await gatewayConfigAB.UpsertAsync(
            currentUser.CompanyId,
            request.GatewayUrl.Trim(),
            request.Enabled,
            string.IsNullOrWhiteSpace(request.StoreId) ? null : request.StoreId!.Trim(),
            currentUser.Id,
            cancellationToken);

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Endereço do Gateway salvo.",
            Data = new
            {
                gatewayUrl = saved.GatewayUrl,
                enabled = saved.Enabled,
                storeId = saved.StoreId,
                updatedAt = saved.UpdatedAt.ToString("o")
            }
        });
    }

    /// <summary>Aceita apenas URLs absolutas http/https com host (host DNS/mDNS ou IP) e porta opcional.</summary>
    private static bool IsValidGatewayUrl(string url)
    {
        if (!Uri.TryCreate(url.Trim(), UriKind.Absolute, out var uri))
        {
            return false;
        }
        return (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps)
            && !string.IsNullOrWhiteSpace(uri.Host);
    }
}

public class GatewayConfigRequest
{
    public string GatewayUrl { get; set; } = string.Empty;
    public bool Enabled { get; set; } = true;
    public string? StoreId { get; set; }
}
