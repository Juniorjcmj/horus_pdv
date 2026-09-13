/**
 * Arquivo: API/NETCORE/Controllers/Categorias/CategoriaController.cs
 * Objetivo: expõe endpoints REST para gerenciamento de categorias e departamentos de produtos.
 */
using HORUSPDV_API.Models.Categorias;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Categorias;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Categorias;

[ApiController]
[Route("api/[controller]")]
public class CategoriaController(ICategoriaService categoriaService) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType(typeof(ApiResponse<List<CategoriaModel>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> Listar([FromQuery] bool plana = false, [FromQuery] bool apenasAtivas = false)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<CategoriaModel>> { Success = false, Message = "Sessão não encontrada." });

        var data = plana
            ? await categoriaService.ListarTodasAsync(currentUser.CompanyId, apenasAtivas)
            : await categoriaService.ListarArvoreAsync(currentUser.CompanyId, apenasAtivas);

        return Ok(new ApiResponse<List<CategoriaModel>>
        {
            Success = true,
            Message = "Categorias obtidas com sucesso.",
            Data = data
        });
    }

    [HttpGet("{id}")]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> ObterPorId(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<CategoriaModel> { Success = false, Message = "Sessão não encontrada." });

        var item = await categoriaService.ObterPorIdAsync(currentUser.CompanyId, id);
        if (item is null)
        {
            return NotFound(new ApiResponse<CategoriaModel> { Success = false, Message = "Categoria não encontrada." });
        }

        return Ok(new ApiResponse<CategoriaModel>
        {
            Success = true,
            Message = "Categoria obtida com sucesso.",
            Data = item
        });
    }

    [HttpPost]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status201Created)]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Criar([FromBody] CategoriaRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<CategoriaModel> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var created = await categoriaService.CriarAsync(currentUser.CompanyId, request);
            return StatusCode(StatusCodes.Status201Created, new ApiResponse<CategoriaModel>
            {
                Success = true,
                Message = "Categoria criada com sucesso.",
                Data = created
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<CategoriaModel> { Success = false, Message = ex.Message });
        }
    }

    [HttpPut("{id}")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status400BadRequest)]
    [ProducesResponseType(typeof(ApiResponse<CategoriaModel>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Atualizar(string id, [FromBody] CategoriaRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<CategoriaModel> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var updated = await categoriaService.AtualizarAsync(currentUser.CompanyId, id, request);
            if (updated is null)
            {
                return NotFound(new ApiResponse<CategoriaModel> { Success = false, Message = "Categoria não encontrada." });
            }

            return Ok(new ApiResponse<CategoriaModel>
            {
                Success = true,
                Message = "Categoria atualizada com sucesso.",
                Data = updated
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<CategoriaModel> { Success = false, Message = ex.Message });
        }
    }

    [HttpPatch("{id}/status")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> AtivarDesativar(string id, [FromBody] CategoriaStatusRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var success = await categoriaService.AtivarDesativarAsync(currentUser.CompanyId, id, request.Ativa);
        if (!success)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Categoria não encontrada." });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = request.Ativa ? "Categoria ativada com sucesso." : "Categoria desativada com sucesso."
        });
    }

    [HttpDelete("{id}")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status400BadRequest)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Excluir(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var success = await categoriaService.ExcluirAsync(currentUser.CompanyId, id);
            if (!success)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Categoria não encontrada." });
            }

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Categoria removida com sucesso."
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
