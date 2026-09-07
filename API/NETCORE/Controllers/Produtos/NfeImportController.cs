/**
 * Arquivo: API/NETCORE/Controllers/Produtos/NfeImportController.cs
 * Objetivo: expõe endpoints HTTP de importação de produtos a partir de XML de NF-e de compra.
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras ao NfeImportService.
 */
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Produtos;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente")]
public class NfeImportController(NfeImportService nfeImportService) : ControllerBase
{
    [HttpPost("preview")]
    [ProducesResponseType(typeof(ApiResponse<NfeImportPreviewModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<NfeImportPreviewModel>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Preview([FromBody] NfeImportPreviewRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<NfeImportPreviewModel> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var preview = await nfeImportService.PreVisualizarAsync(currentUser.CompanyId, request);
            return Ok(new ApiResponse<NfeImportPreviewModel>
            {
                Success = true,
                Message = "XML lido com sucesso.",
                Data = preview
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<NfeImportPreviewModel> { Success = false, Message = ex.Message });
        }
    }

    [HttpPost("confirmar")]
    [ProducesResponseType(typeof(ApiResponse<NfeImportResultModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<NfeImportResultModel>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Confirmar([FromBody] NfeImportConfirmRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<NfeImportResultModel> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var resultado = await nfeImportService.ConfirmarAsync(currentUser.CompanyId, request);
            return Ok(new ApiResponse<NfeImportResultModel>
            {
                Success = true,
                Message = "Importação concluída com sucesso.",
                Data = resultado
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<NfeImportResultModel> { Success = false, Message = ex.Message });
        }
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
