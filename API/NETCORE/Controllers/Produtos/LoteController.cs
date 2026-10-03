/**
 * Arquivo: API/NETCORE/Controllers/Produtos/LoteController.cs
 * Objetivo: expõe endpoints HTTP do controle de validade por lote (alertas, lotes de um produto,
 *           registro manual e prazos por categoria).
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras ao LoteService.
 *
 * Fica sob api/Produto/lotes para o frontend reaproveitar a URL base de produto já configurada.
 */
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Produtos;

[ApiController]
[Route("api/Produto/lotes")]
public class LoteController(LoteService loteService) : ControllerBase
{
    [HttpGet("alertas")]
    [ProducesResponseType(typeof(ApiResponse<LoteAlertasResumoModel>), StatusCodes.Status200OK)]
    public async Task<IActionResult> Alertas(CancellationToken cancellationToken)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<LoteAlertasResumoModel> { Success = false, Message = "Sessão não encontrada." });

        var data = await loteService.ListarAlertasAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<LoteAlertasResumoModel>
        {
            Success = true,
            Message = "Alertas de validade por lote obtidos com sucesso.",
            Data = data
        });
    }

    [HttpGet("consulta")]
    [ProducesResponseType(typeof(ApiResponse<LoteConsultaModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<LoteConsultaModel>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Consulta([FromQuery] LoteConsultaFiltro filtro, CancellationToken cancellationToken)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<LoteConsultaModel> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var data = await loteService.ConsultarAsync(currentUser.CompanyId, filtro, cancellationToken);
            return Ok(new ApiResponse<LoteConsultaModel>
            {
                Success = true,
                Message = "Lotes obtidos com sucesso.",
                Data = data
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<LoteConsultaModel> { Success = false, Message = ex.Message });
        }
    }

    [HttpGet("produto/{produtoId}")]
    [ProducesResponseType(typeof(ApiResponse<List<LoteModel>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> PorProduto(string produtoId, CancellationToken cancellationToken)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<LoteModel>> { Success = false, Message = "Sessão não encontrada." });

        var data = await loteService.ListarPorProdutoAsync(currentUser.CompanyId, produtoId, cancellationToken);
        return Ok(new ApiResponse<List<LoteModel>>
        {
            Success = true,
            Message = "Lotes do produto obtidos com sucesso.",
            Data = data
        });
    }

    [HttpPost("registrar")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente")]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status201Created)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Registrar([FromBody] RegistrarLoteRequest request, CancellationToken cancellationToken)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            await loteService.RegistrarManualAsync(currentUser, request, cancellationToken);
            return StatusCode(StatusCodes.Status201Created, new ApiResponse<object> { Success = true, Message = "Lote registrado com sucesso." });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpGet("categorias")]
    [ProducesResponseType(typeof(ApiResponse<List<CategoriaValidadeModel>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> Categorias(CancellationToken cancellationToken)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<CategoriaValidadeModel>> { Success = false, Message = "Sessão não encontrada." });

        var data = await loteService.ListarCategoriasAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<List<CategoriaValidadeModel>>
        {
            Success = true,
            Message = "Prazos de validade por categoria obtidos com sucesso.",
            Data = data
        });
    }

    [HttpPut("categorias/{categoriaId}")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> SalvarCategoria(string categoriaId, [FromBody] CategoriaValidadeRequest request, CancellationToken cancellationToken)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var updated = await loteService.SalvarCategoriaAsync(currentUser.CompanyId, categoriaId, request, cancellationToken);
            if (!updated)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Categoria não encontrada." });
            }

            return Ok(new ApiResponse<object> { Success = true, Message = "Prazo de validade da categoria atualizado." });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
