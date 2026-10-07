/**
 * Arquivo: API/NETCORE/Controllers/Fiscal/RegrasEmissaoNfceController.cs
 * Objetivo: endpoints para configuração de regras de emissão de NFC-e por forma de pagamento e intervalo de vendas.
 */
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Fiscal;

[ApiController]
[Route("api/regras-emissao-nfce")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
public class RegrasEmissaoNfceController(RegrasEmissaoNfceAB regrasEmissaoNfceAB) : ControllerBase
{
    [HttpGet]
    public async Task<IActionResult> Get(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        var config = await regrasEmissaoNfceAB.GetByCompanyAsync(currentUser.CompanyId, cancellationToken);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Regras de emissão de NFC-e obtidas com sucesso.",
            Data = new
            {
                companyId = config.CompanyId,
                habilitado = config.Habilitado,
                formasPagamentoHabilitadas = config.FormasPagamentoHabilitadas,
                intervaloNotas = config.IntervaloNotas,
                emitirSempreComCpf = config.EmitirSempreComCpf,
                contadorVendas = config.ContadorVendas,
                updatedAt = config.UpdatedAt.ToString("o")
            }
        });
    }

    [HttpPut]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> Put([FromBody] SalvarRegrasEmissaoNfceRequest request, CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        if (request is null)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Dados da requisição inválidos." });
        }

        var saved = await regrasEmissaoNfceAB.UpsertAsync(
            currentUser.CompanyId,
            request.Habilitado,
            request.FormasPagamentoHabilitadas,
            request.IntervaloNotas,
            request.EmitirSempreComCpf,
            currentUser.Id,
            cancellationToken);

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Regras de emissão de NFC-e salvas com sucesso.",
            Data = new
            {
                companyId = saved.CompanyId,
                habilitado = saved.Habilitado,
                formasPagamentoHabilitadas = saved.FormasPagamentoHabilitadas,
                intervaloNotas = saved.IntervaloNotas,
                emitirSempreComCpf = saved.EmitirSempreComCpf,
                contadorVendas = saved.ContadorVendas,
                updatedAt = saved.UpdatedAt.ToString("o")
            }
        });
    }

    [HttpPost("reset-contador")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> ResetContador(CancellationToken cancellationToken)
    {
        if (HttpContext.Items["CurrentUser"] is not AuthenticatedUser currentUser)
        {
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        }

        await regrasEmissaoNfceAB.ResetContadorAsync(currentUser.CompanyId, cancellationToken);
        var config = await regrasEmissaoNfceAB.GetByCompanyAsync(currentUser.CompanyId, cancellationToken);

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Contador de intervalo de vendas zerado com sucesso.",
            Data = new
            {
                contadorVendas = config.ContadorVendas,
                intervaloNotas = config.IntervaloNotas
            }
        });
    }
}

public class SalvarRegrasEmissaoNfceRequest
{
    public bool Habilitado { get; set; } = true;

    /// <summary>
    /// Formas de pagamento que SEMPRE emitem NFC-e (ex.: "pix"). As demais formas seguem o intervalo.
    /// Vazio = nenhuma sempre emite (todas seguem o intervalo).
    /// </summary>
    public string FormasPagamentoHabilitadas { get; set; } = "dinheiro,credito,debito,pix,fiado";
    public int IntervaloNotas { get; set; } = 1;
    public bool EmitirSempreComCpf { get; set; } = true;
}
