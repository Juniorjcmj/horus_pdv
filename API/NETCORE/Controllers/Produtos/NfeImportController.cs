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
public class NfeImportController(NfeImportService nfeImportService, NotaEntradaArquivoService arquivoService) : ControllerBase
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

    [HttpPost("buscar-sefaz")]
    [ProducesResponseType(typeof(ApiResponse<NfeImportPreviewModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<NfeImportPreviewModel>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> BuscarSefaz([FromBody] NfeImportChaveRequest request, CancellationToken ct)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<NfeImportPreviewModel> { Success = false, Message = "Sessão não encontrada." });
        }

        try
        {
            var preview = await nfeImportService.PreVisualizarPorChaveSefazAsync(currentUser.CompanyId, request.ChaveAcesso, ct);
            return Ok(new ApiResponse<NfeImportPreviewModel>
            {
                Success = true,
                Message = "NF-e baixada diretamente da SEFAZ com sucesso.",
                Data = preview
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<NfeImportPreviewModel> { Success = false, Message = ex.Message });
        }
        catch (Exception ex)
        {
            return BadRequest(new ApiResponse<NfeImportPreviewModel> { Success = false, Message = $"Falha ao consultar SEFAZ: {ex.Message}" });
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
            var resultado = await nfeImportService.ConfirmarAsync(currentUser.CompanyId, request, currentUser.Id, currentUser.Name);
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

    [HttpGet("notas-entrada")]
    public async Task<IActionResult> ListarNotas([FromQuery] string? busca, [FromQuery] int pagina = 1, [FromQuery] int tamanhoPagina = 20)
    {
        var user = GetCurrentUser();
        if (user is null) return Unauthorized();
        return Ok(new ApiResponse<NotaEntradaPagina> { Success = true, Data = await arquivoService.ListarAsync(user.CompanyId, busca, pagina, tamanhoPagina) });
    }

    [HttpGet("notas-entrada/{id}")]
    public async Task<IActionResult> ObterNota(string id)
    {
        var user = GetCurrentUser();
        if (user is null) return Unauthorized();
        var nota = await arquivoService.ObterAsync(user.CompanyId, id);
        return nota is null ? NotFound() : Ok(new ApiResponse<NotaEntradaDetalhe> { Success = true, Data = nota });
    }

    [HttpGet("notas-entrada/{id}/xml")]
    public async Task<IActionResult> DownloadXml(string id)
    {
        var user = GetCurrentUser();
        if (user is null) return Unauthorized();
        var xml = await arquivoService.ObterXmlAsync(user.CompanyId, id);
        if (xml is null) return NotFound(new { message = "XML não disponível para esta entrada." });
        Response.Headers.CacheControl = "no-store";
        return File(xml, "application/xml", $"nota-entrada-{id}.xml");
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
