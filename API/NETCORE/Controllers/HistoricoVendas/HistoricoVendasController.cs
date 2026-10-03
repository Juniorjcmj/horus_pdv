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
    RegrasEmissaoNfceAB regrasEmissaoNfceAB,
    HorusSecurityStore securityStore,
    ILogger<HistoricoVendasController> logger) : ControllerBase
{
    [HttpGet]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
    public async Task<IActionResult> Listar([FromQuery] string? desde = null)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        DateTimeOffset? dtDesde = null;
        if (!string.IsNullOrWhiteSpace(desde) && DateTimeOffset.TryParse(desde, out var parsedDesde))
        {
            dtDesde = parsedDesde;
        }

        var rows = await historicoVendasAB.ListarAsync(currentUser.CompanyId, desde: dtDesde);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Historico de vendas obtido com sucesso.",
            Data = rows
        });
    }

    [HttpGet("{saleNumber}")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
    public async Task<IActionResult> ObterDetalhes(string saleNumber)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var venda = await historicoVendasAB.ObterDetalheCompletoAsync(currentUser.CompanyId, saleNumber);
        if (venda is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Venda não encontrada." });
        }

        var docFiscal = await documentoFiscalAB.ObterDetalhePorCodigoAsync(currentUser.CompanyId, saleNumber);

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Detalhes da venda obtidos com sucesso.",
            Data = new
            {
                venda.VendaId,
                venda.SaleNumber,
                venda.CustomerName,
                venda.CustomerCpf,
                venda.PaymentType,
                venda.TotalAmount,
                venda.OperatorName,
                venda.SaleDate,
                venda.ClientSaleId,
                venda.OfflineReference,
                venda.Items,
                venda.Payments,
                DocumentoFiscal = docFiscal
            }
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

            // Multi-caixa: vincula a venda à sessão de caixa do operador (null se offline ou sem caixa)
            string? caixaSessaoId = null;
            try
            {
                var sessaoAberta = await caixaService.ObterSessaoAbertaDoOperadorAsync(currentUser.CompanyId, currentUser.Id);
                caixaSessaoId = sessaoAberta?.Id;
            }
            catch { /* venda offline ou caixa indisponível — segue sem vínculo */ }

            var result = await historicoVendasAB.RegistrarAsync(currentUser.CompanyId, request, caixaSessaoId);

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

            // Avalia se esta venda deve emitir NFC-e conforme as regras da empresa (formas de pagamento e intervalo de notas)
            var listaFormas = request.Payments.Select(p => p.PaymentType).Where(p => !string.IsNullOrWhiteSpace(p)).ToList();
            if (listaFormas.Count == 0 && !string.IsNullOrWhiteSpace(request.PaymentType))
            {
                listaFormas.Add(request.PaymentType);
            }

            var deveEmitirFiscal = await regrasEmissaoNfceAB.AvaliarEmissaoEIncrementarAsync(
                currentUser.CompanyId,
                request.PaymentType,
                listaFormas,
                request.CustomerCpf);

            var fiscalQueued = false;
            if (deveEmitirFiscal)
            {
                try
                {
                    await documentoFiscalAB.EnfileirarAsync(currentUser.CompanyId, result.VendaId);
                    fiscalQueued = true;
                }
                catch (Exception ex)
                {
                    fiscalQueued = false;
                    logger.LogError(ex, "Falha ao enfileirar NFC-e da venda {VendaId}.", result.VendaId);
                }
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
                    emitirFiscal = deveEmitirFiscal,
                    isReplay = false,
                    warnings = result.Warnings,
                    fiadoSaldoAtual = result.FiadoSaldoAtual,
                    fiadoClienteId = result.FiadoClienteId
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
        catch (ArgumentException ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
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

    [HttpPost("{saleNumber}/cancelar")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
    public async Task<IActionResult> CancelarComSupervisor(string saleNumber, [FromBody] CancelamentoVendaComSupervisorRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.Justificativa) || request.Justificativa.Trim().Length < 5)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Informe uma justificativa de no mínimo 5 caracteres." });
        }

        // Valida credenciais do supervisor
        var (valido, msgSupervisor, supervisor) = securityStore.ValidateSupervisorCredentials(
            request.SupervisorId,
            request.SupervisorPassword,
            currentUser.CompanyId);

        if (!valido || supervisor is null)
        {
            return StatusCode(StatusCodes.Status403Forbidden, new ApiResponse<object>
            {
                Success = false,
                Message = msgSupervisor
            });
        }

        var (sucesso, mensagem, itensEstornados, canceladoEm) = await historicoVendasAB.CancelarVendaComSupervisorAsync(
            currentUser.CompanyId,
            saleNumber,
            supervisor.Id,
            supervisor.Name,
            currentUser.Id,
            currentUser.Name,
            request.Justificativa.Trim());

        if (!sucesso)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = mensagem });
        }

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = mensagem,
            Data = new
            {
                saleNumber,
                canceladoEm,
                supervisorNome = supervisor.Name,
                itensEstornados
            }
        });
    }

    [HttpGet("cancelamentos")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
    public async Task<IActionResult> ListarCancelamentos([FromQuery] string? de = null, [FromQuery] string? ate = null)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        DateTimeOffset? dtDe = null;
        DateTimeOffset? dtAte = null;
        if (!string.IsNullOrWhiteSpace(de) && DateTimeOffset.TryParse(de, out var pDe)) dtDe = pDe;
        if (!string.IsNullOrWhiteSpace(ate) && DateTimeOffset.TryParse(ate, out var pAte)) dtAte = pAte.AddDays(1);

        var cancelamentos = await historicoVendasAB.ListarCancelamentosAsync(currentUser.CompanyId, dtDe, dtAte);
        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "Cancelamentos obtidos com sucesso.",
            Data = cancelamentos
        });
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
