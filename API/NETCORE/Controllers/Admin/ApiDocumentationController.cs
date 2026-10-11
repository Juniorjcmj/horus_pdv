using System.Text;
using System.Text.Json;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Admin;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;
using Microsoft.OpenApi;
using Swashbuckle.AspNetCore.Swagger;

namespace HORUSPDV_API.Controllers.Admin;

[ApiController]
[Route("api/Admin/Empresas/documentacao-api")]
[HorusAuthorizeRoles("administrador")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class ApiDocumentationController(ISwaggerProvider documents) : ControllerBase
{
    /// <summary>Baixa o contrato OpenAPI completo da versão da API em execução.</summary>
    /// <remarks>Restrito ao administrador da empresa-principal. Gera somente documentação dos endpoints e modelos.</remarks>
    [HttpGet]
    [Produces("application/json")]
    [ProducesResponseType(typeof(JsonElement), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status401Unauthorized)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status403Forbidden)]
    public async Task<IActionResult> Download(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser user)
            return Unauthorized(new ApiResponse<object> { Message = "Sessão não encontrada." });
        if (!string.Equals(user.CompanyId, "empresa-principal", StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(user.Role, "administrador", StringComparison.OrdinalIgnoreCase))
            return StatusCode(403, new ApiResponse<object> { Message = "Documentação da API restrita ao Administrador Geral da Plataforma." });

        var document = documents.GetSwagger(ApiDocumentation.DocumentName);
        document.Servers = [new OpenApiServer { Url = $"{Request.Scheme}://{Request.Host}{Request.PathBase}" }];
        var json = await document.SerializeAsJsonAsync(OpenApiSpecVersion.OpenApi3_0, cancellationToken);
        return File(Encoding.UTF8.GetBytes(json), "application/json; charset=utf-8", ApiDocumentation.FileName);
    }
}
