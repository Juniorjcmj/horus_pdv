/**
 * Arquivo: API/NETCORE/Controllers/HistoricoVendas/HistoricoVendasController.cs
 * Objetivo: expõe endpoints HTTP de histórico de vendas e recibos e padroniza respostas para o frontend.
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras para serviços/repositórios.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Caixa;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.HistoricoVendas;

[ApiController]
[Route("api/[controller]")]
public class HistoricoVendasController(
    HistoricoVendasAB historicoVendasAB,
    HorusCaixaService caixaService,
    DocumentoFiscalAB documentoFiscalAB,
    ILogger<HistoricoVendasController> logger) : ControllerBase
{
    [HttpGet]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente")]
    public async Task<IActionResult> Listar()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        var rows = await historicoVendasAB.ListarAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Historico de vendas obtido com sucesso.",
            Data = rows
        });
    }

    [HttpPost]
    public async Task<IActionResult> Registrar([FromBody] VendaRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (request.Items.Count == 0)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Venda sem itens." });
        }

        try
        {
            bool isOfflineSync = !string.IsNullOrWhiteSpace(request.EventId) || request.OccurredAt.HasValue;
            if (!isOfflineSync)
            {
                caixaService.EnsureVendaPermitida(currentUser);
            }
            else
            {
                try
                {
                    caixaService.EnsureVendaPermitida(currentUser);
                }
                catch (Exception ex)
                {
                    logger.LogWarning("Venda sincronizada offline com aviso de caixa ({Message}). EventId: {EventId}", ex.Message, request.EventId);
                }
            }

            var result = await historicoVendasAB.RegistrarAsync(currentUser.CompanyId, request);

            if (result.IsReplay)
            {
                logger.LogInformation("Venda retornada via replay idempotente. EventId: {EventId}, SaleNumber: {SaleNumber}", request.EventId, result.SaleNumber);
                return Ok(new ApiResponse<object>
                {
                    Success = true,
                    Message = "Venda já processada anteriormente (idempotente).",
                    Data = new
                    {
                        saleNumber = result.SaleNumber,
                        clientSaleId = result.ClientSaleId ?? request.ClientSaleId,
                        vendaId = result.VendaId,
                        rows = result.Rows,
                        fiscalQueued = false,
                        isReplay = true
                    }
                });
            }

            // A nota fiscal é enfileirada fora da transação da venda — a venda já está
            // confirmada e liberada para o caixa; a emissão em si acontece em segundo plano
            // pelo NfceOutboxWorker. Falha aqui não pode derrubar uma venda já registrada.
            var fiscalQueued = true;
            try
            {
                await documentoFiscalAB.EnfileirarAsync(currentUser.CompanyId, result.VendaId);
            }
            catch (Exception ex)
            {
                fiscalQueued = false;
                logger.LogError(ex, "Falha ao enfileirar NFC-e da venda {VendaId}.", result.VendaId);
            }

            return StatusCode(StatusCodes.Status201Created, new ApiResponse<object>
            {
                Success = true,
                Message = "Venda registrada com sucesso.",
                Data = new
                {
                    saleNumber = result.SaleNumber,
                    clientSaleId = result.ClientSaleId ?? request.ClientSaleId,
                    vendaId = result.VendaId,
                    rows = result.Rows,
                    fiscalQueued,
                    isReplay = false,
                    warnings = result.Warnings
                }
            });
        }
        catch (IdempotencyConflictException ex)
        {
            logger.LogWarning(ex, "Conflito de idempotência no registro de venda. EventId: {EventId}", request.EventId);
            return StatusCode(StatusCodes.Status409Conflict, new ApiResponse<object>
            {
                Success = false,
                Message = ex.Message
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    [HttpPost("{saleNumber}/imprimir")]
    public async Task<IActionResult> Imprimir(string saleNumber)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });
        var saleRows = await historicoVendasAB.ListarAsync(currentUser.CompanyId, saleNumber);
        if (saleRows.Count == 0)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Venda não encontrada." });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Impressao enviada para processamento.",
            Data = new
            {
                saleNumber,
                printedAt = DateTime.Now.ToString("dd/MM/yyyy HH:mm:ss"),
                items = saleRows.Count,
                rows = saleRows
            }
        });
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
