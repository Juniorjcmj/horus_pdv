/**
 * Arquivo: API/NETCORE/Controllers/Pedidos/PedidoController.cs
 * Objetivo: expõe endpoints HTTP de criação, consulta, finalização e cancelamento de pedidos
 *           (vendedor monta o pedido, caixa localiza pelo número e finaliza como venda).
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras para
 *           PedidoAB / HistoricoVendasAB / DocumentoFiscalAB.
 */
using HORUSPDV_API.Models.Pedidos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Caixa;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Pedidos;

[ApiController]
[Route("api/[controller]")]
public class PedidoController(
    PedidoAB pedidoAB,
    HistoricoVendasAB historicoVendasAB,
    DocumentoFiscalAB documentoFiscalAB,
    HorusCaixaService caixaService,
    ILogger<PedidoController> logger) : ControllerBase
{
    [HttpPost]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente")]
    public async Task<IActionResult> Criar([FromBody] CriarPedidoRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (request.Items.Count == 0)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Pedido sem itens." });
        }

        try
        {
            var pedido = await pedidoAB.CriarAsync(
                currentUser.CompanyId, currentUser.Id, currentUser.Name, request.CustomerName, request.CustomerCpf, request.Items);

            return StatusCode(StatusCodes.Status201Created, new ApiResponse<PedidoModel>
            {
                Success = true,
                Message = "Pedido criado com sucesso.",
                Data = ToModel(pedido)
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpGet]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente")]
    public async Task<IActionResult> ListarAbertos()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var pedidos = await pedidoAB.ListarAbertosAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<List<PedidoModel>>
        {
            Success = true,
            Message = "Pedidos obtidos com sucesso.",
            Data = pedidos.Select(ToModel).ToList()
        });
    }

    [HttpGet("{orderNumber}")]
    public async Task<IActionResult> Obter(string orderNumber)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var pedido = await pedidoAB.ObterPorNumeroAsync(currentUser.CompanyId, orderNumber);
        if (pedido is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Pedido não encontrado." });
        }

        return Ok(new ApiResponse<PedidoModel>
        {
            Success = true,
            Message = "Pedido obtido com sucesso.",
            Data = ToModel(pedido)
        });
    }

    [HttpPost("{orderNumber}/finalizar")]
    public async Task<IActionResult> Finalizar(string orderNumber, [FromBody] FinalizarPedidoRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        try
        {
            caixaService.EnsureVendaPermitida(currentUser);

            var pedido = await pedidoAB.ObterPorNumeroAsync(currentUser.CompanyId, orderNumber);
            if (pedido is null)
            {
                return NotFound(new ApiResponse<object> { Success = false, Message = "Pedido não encontrado." });
            }

            if (pedido.Status != PedidoStatus.Aberto)
            {
                var motivo = pedido.Status == PedidoStatus.Finalizado
                    ? "Este pedido já foi pago."
                    : "Este pedido foi cancelado.";
                return BadRequest(new ApiResponse<object> { Success = false, Message = motivo });
            }

            var paymentType = string.IsNullOrWhiteSpace(request.PaymentType) ? "-" : request.PaymentType.Trim();
            var result = await historicoVendasAB.RegistrarComPrecosFixosAsync(
                currentUser.CompanyId, pedido.CustomerName, pedido.CustomerCpf, paymentType, currentUser.Name, pedido.Itens, request.Payments);

            await pedidoAB.MarcarFinalizadoAsync(currentUser.CompanyId, pedido.Id, result.VendaId);

            // Mesma regra da venda direta: falha ao enfileirar não pode derrubar um pedido já pago.
            var fiscalQueued = true;
            try
            {
                await documentoFiscalAB.EnfileirarAsync(currentUser.CompanyId, result.VendaId);
            }
            catch (Exception ex)
            {
                fiscalQueued = false;
                logger.LogError(ex, "Falha ao enfileirar NFC-e do pedido {OrderNumber}.", orderNumber);
            }

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "Pedido finalizado com sucesso.",
                Data = new { saleNumber = result.SaleNumber, rows = result.Rows, fiscalQueued }
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpPost("{orderNumber}/cancelar")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente")]
    public async Task<IActionResult> Cancelar(string orderNumber)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var cancelado = await pedidoAB.CancelarAsync(currentUser.CompanyId, orderNumber);
        return cancelado
            ? Ok(new ApiResponse<object> { Success = true, Message = "Pedido cancelado com sucesso." })
            : NotFound(new ApiResponse<object> { Success = false, Message = "Pedido não encontrado ou não está mais aberto." });
    }

    private static PedidoModel ToModel(PedidoAD source) => new()
    {
        OrderNumber = source.OrderNumber,
        CustomerName = source.CustomerName,
        CustomerCpf = source.CustomerCpf,
        SellerName = source.SellerName,
        Status = source.Status switch
        {
            PedidoStatus.Finalizado => "finalizado",
            PedidoStatus.Cancelado => "cancelado",
            _ => "aberto"
        },
        CreatedAt = HorusDateTime.Format(source.CreatedAt),
        TotalAmount = HorusMoneyFormat.Format(source.Itens.Sum(item => item.ItemTotal)),
        Itens = source.Itens.Select(item => new PedidoItemModel
        {
            ProductCode = item.ProductCode,
            ProductName = item.ProductName,
            Quantity = item.Quantity,
            UnitPrice = HorusMoneyFormat.Format(item.UnitPrice),
            ItemTotal = HorusMoneyFormat.Format(item.ItemTotal)
        }).ToList()
    };

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
