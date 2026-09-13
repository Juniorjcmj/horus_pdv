/**
 * Arquivo: API/NETCORE/Controllers/Promocoes/PromocaoController.cs
 * Objetivo: expõe endpoints REST para gerenciamento de promoções e preços dinâmicos.
 */
using HORUSPDV_API.Models.Promocoes;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Promocoes;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Promocoes;

[ApiController]
[Route("api/[controller]")]
public class PromocaoController(IPromocaoService promocaoService) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType(typeof(ApiResponse<List<PromocaoModel>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> Listar()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<PromocaoModel>> { Success = false, Message = "Sessão não encontrada." });

        var data = await promocaoService.ListarAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<List<PromocaoModel>>
        {
            Success = true,
            Message = "Promoções obtidas com sucesso.",
            Data = data
        });
    }

    [HttpGet("ativas")]
    [ProducesResponseType(typeof(ApiResponse<List<PromocaoModel>>), StatusCodes.Status200OK)]
    public async Task<IActionResult> ListarAtivas()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<List<PromocaoModel>> { Success = false, Message = "Sessão não encontrada." });

        var data = await promocaoService.ListarAtivasAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<List<PromocaoModel>>
        {
            Success = true,
            Message = "Promoções ativas obtidas com sucesso.",
            Data = data
        });
    }

    [HttpGet("{id}")]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> ObterPorId(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<PromocaoModel> { Success = false, Message = "Sessão não encontrada." });

        var item = await promocaoService.ObterPorIdAsync(currentUser.CompanyId, id);
        if (item is null)
        {
            return NotFound(new ApiResponse<PromocaoModel> { Success = false, Message = "Promoção não encontrada." });
        }

        return Ok(new ApiResponse<PromocaoModel>
        {
            Success = true,
            Message = "Promoção obtida com sucesso.",
            Data = item
        });
    }

    [HttpGet("{id}/resultado")]
    [ProducesResponseType(typeof(ApiResponse<PromocaoResultadoModel>), StatusCodes.Status200OK)]
    public async Task<IActionResult> ObterResultado(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<PromocaoResultadoModel> { Success = false, Message = "Sessão não encontrada." });

        var item = await promocaoService.ObterResultadoAsync(currentUser.CompanyId, id);
        return Ok(new ApiResponse<PromocaoResultadoModel>
        {
            Success = true,
            Message = "Resultado da promoção obtido com sucesso.",
            Data = item
        });
    }

    [HttpPost]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status201Created)]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Criar([FromBody] PromocaoRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<PromocaoModel> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var created = await promocaoService.CriarAsync(currentUser.CompanyId, currentUser.Name, request);
            return StatusCode(StatusCodes.Status201Created, new ApiResponse<PromocaoModel>
            {
                Success = true,
                Message = "Promoção criada com sucesso.",
                Data = created
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<PromocaoModel> { Success = false, Message = ex.Message });
        }
    }

    [HttpPut("{id}")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status400BadRequest)]
    [ProducesResponseType(typeof(ApiResponse<PromocaoModel>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Atualizar(string id, [FromBody] PromocaoRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<PromocaoModel> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            var updated = await promocaoService.AtualizarAsync(currentUser.CompanyId, id, request);
            return Ok(new ApiResponse<PromocaoModel>
            {
                Success = true,
                Message = "Promoção atualizada com sucesso.",
                Data = updated
            });
        }
        catch (KeyNotFoundException)
        {
            return NotFound(new ApiResponse<PromocaoModel> { Success = false, Message = "Promoção não encontrada." });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<PromocaoModel> { Success = false, Message = ex.Message });
        }
    }

    [HttpPatch("{id}/status")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> AtivarDesativar(string id, [FromBody] PromocaoStatusRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            await promocaoService.AtivarDesativarAsync(currentUser.CompanyId, id, request.Ativa);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = request.Ativa ? "Promoção ativada com sucesso." : "Promoção desativada com sucesso."
            });
        }
        catch (KeyNotFoundException)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Promoção não encontrada." });
        }
    }

    [HttpDelete("{id}")]
    [HorusAuthorizeRoles("administrador", "gerente")]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status200OK)]
    [ProducesResponseType(typeof(ApiResponse<object>), StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Excluir(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            await promocaoService.ExcluirAsync(currentUser.CompanyId, id);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Promoção excluída com sucesso."
            });
        }
        catch (KeyNotFoundException)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Promoção não encontrada." });
        }
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
