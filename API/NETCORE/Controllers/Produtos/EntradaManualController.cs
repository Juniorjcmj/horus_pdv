using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Services.Produtos;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Controllers.Produtos;

[ApiController]
[Route("api/Produto/entrada-manual")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente")]
public sealed class EntradaManualController(EntradaManualService service) : ControllerBase
{
    [HttpPost]
    [RequestSizeLimit(16_000_000)]
    public async Task<IActionResult> Confirmar(EntradaManualRequest request)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser user) return Unauthorized();
        try { return Ok(new ApiResponse<NfeImportResultModel> { Success = true, Message = "Entrada registrada no estoque.", Data = await service.ConfirmarAsync(user.CompanyId, request, user.Id, user.Name) }); }
        catch (InvalidOperationException ex) { return BadRequest(new ApiResponse<NfeImportResultModel> { Success = false, Message = ex.Message }); }
        catch (SqlException ex) when (ex.Number is 2601 or 2627 or 51000) { return Conflict(new ApiResponse<NfeImportResultModel> { Success = false, Message = "Produto ou documento já recebido, ou entrada em processamento. Confira o histórico antes de tentar novamente." }); }
    }
}
