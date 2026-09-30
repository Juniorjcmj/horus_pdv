/*
 * Arquivo: Controllers/GatewayFiscalController.cs
 * Objetivo: expõe endpoints locais na LAN para emissão de NFC-e em contingência offline (tpEmis = 9)
 *           e diagnóstico de certificado digital A1 quando a loja estiver sem conexão com a internet.
 */
using HorusGateway.Configuration;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
[Route("api/gateway/fiscal")]
public sealed class GatewayFiscalController : ControllerBase
{
    private readonly LocalFiscalSigner _signer;
    private readonly LocalFiscalStore _store;
    private readonly GatewayCertificateService _certificateService;
    private readonly ITerminalStore _terminalStore;
    private readonly GatewayOptions _options;
    private readonly ILogger<GatewayFiscalController> _logger;

    public GatewayFiscalController(
        LocalFiscalSigner signer,
        LocalFiscalStore store,
        GatewayCertificateService certificateService,
        ITerminalStore terminalStore,
        IOptions<GatewayOptions> options,
        ILogger<GatewayFiscalController> logger)
    {
        _signer = signer;
        _store = store;
        _certificateService = certificateService;
        _terminalStore = terminalStore;
        _options = options.Value;
        _logger = logger;
    }

    /// <summary>
    /// Emite e assina digitalmente uma NFC-e em contingência offline (tpEmis = 9) na LAN,
    /// persistindo no SQLite local e retornando o XML assinado + dados do DANFE térmico para o PDV.
    /// </summary>
    [HttpPost("nfce/contingencia")]
    public async Task<IActionResult> EmitirContingencia(
        [FromBody] LocalNfceContingenciaRequest request,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(request.VendaId) ||
            string.IsNullOrWhiteSpace(request.TerminalId) ||
            request.Itens.Count == 0)
        {
            return BadRequest(new LocalNfceContingenciaResponse
            {
                Success = false,
                Message = "VendaId, TerminalId e ao menos um item são obrigatórios."
            });
        }

        // Validação de terminal se exigido
        if (_options.RequireTerminalAuth)
        {
            var headerAuth = Request.Headers["X-Terminal-Key"].FirstOrDefault();
            var auth = await _terminalStore.AuthenticateAsync(request.TerminalId, headerAuth, ct);
            if (!auth.Authenticated)
            {
                return Unauthorized(new LocalNfceContingenciaResponse
                {
                    Success = false,
                    Message = $"Terminal não autorizado ({auth.Outcome}). Credencial inválida para este Gateway."
                });
            }
        }

        var serie = _options.SerieNfceContingencia > 0 ? _options.SerieNfceContingencia : 900;
        var numeroNf = _store.AlocarProximoNumero(_options.CompanyId, serie);
        var dhContingencia = DateTimeOffset.UtcNow;

        _logger.LogInformation(
            "Iniciando emissão offline de NFC-e na LAN. Venda: {VendaId}, Terminal: {TerminalId}, Série: {Serie}, Nº: {NumeroNf}",
            request.VendaId, request.TerminalId, serie, numeroNf);

        var resultado = _signer.EmitirContingencia(request, numeroNf, serie, dhContingencia);
        if (!resultado.Success)
        {
            _logger.LogError("Falha ao assinar NFC-e offline no Gateway: {Motivo}", resultado.Message);
            return BadRequest(resultado);
        }

        // Persiste duravelmente e despacha evento para sincronização com a Cloud
        await _store.PersistirEDespacharAsync(_options.CompanyId, resultado, request, ct);

        _logger.LogInformation(
            "NFC-e contingência emitida e persistida com sucesso na LAN. Chave: {Chave}", resultado.ChaveAcesso);

        return Ok(resultado);
    }

    /// <summary>Retorna diagnóstico do módulo fiscal do Gateway (certificado A1, CSC e total de notas offline).</summary>
    [HttpGet("status")]
    public IActionResult ObterStatus()
    {
        var serie = _options.SerieNfceContingencia > 0 ? _options.SerieNfceContingencia : 900;
        var (ultimoNumero, totalNotas) = _store.ObterEstatisticas(_options.CompanyId, serie);
        var status = _certificateService.ObterStatus(ultimoNumero, totalNotas);
        return Ok(status);
    }
}
