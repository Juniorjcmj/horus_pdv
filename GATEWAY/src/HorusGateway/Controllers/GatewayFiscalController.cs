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
    private static readonly SemaphoreSlim EmissionGate = new(1, 1);
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
            request.Itens is null || request.Itens.Count == 0)
        {
            return BadRequest(new LocalNfceContingenciaResponse
            {
                Success = false,
                Message = "VendaId, TerminalId e ao menos um item são obrigatórios."
            });
        }

        // Validação de terminal se exigido
        if (_options.RequireTerminalAuth || request.Emitente is not null)
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

        await EmissionGate.WaitAsync(ct);
        try
        {
        var saved = _store.Find(_options.CompanyId, request.TerminalId, request.VendaId);
        if (saved.HasValue)
        {
            if (saved.Value.Hash is not null && saved.Value.Hash != LocalFiscalStore.RequestHash(request))
                return Conflict(new LocalNfceContingenciaResponse { Success = false, Message = "Esta venda já possui uma NFC-e local com dados diferentes. Recupere o documento original." });
            return Ok(saved.Value.Response);
        }
        var serie = _options.SerieNfceContingencia;
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
        // A disconnected browser must not cancel persistence after the XML has been signed.
        await _store.PersistirEDespacharAsync(_options.CompanyId, resultado, request, CancellationToken.None);

        _logger.LogInformation(
            "NFC-e contingência emitida e persistida com sucesso na LAN. Chave: {Chave}", resultado.ChaveAcesso);

        return Ok(resultado);
        }
        finally { EmissionGate.Release(); }
    }

    /// <summary>Resolve an uncertain POST without allocating another fiscal number. Terminal identity is mandatory.</summary>
    [HttpGet("nfce/contingencia/{vendaId}")]
    public async Task<IActionResult> ObterContingencia(string vendaId, CancellationToken ct = default)
    {
        var terminalId = Request.Headers["X-Terminal-Id"].FirstOrDefault();
        var auth = await _terminalStore.AuthenticateAsync(terminalId, Request.Headers["X-Terminal-Key"].FirstOrDefault(), ct);
        if (!auth.Authenticated) return Unauthorized(new { success = false, message = "Terminal não autorizado para recuperar o documento fiscal." });
        var saved = _store.Find(_options.CompanyId, terminalId!, vendaId);
        return saved.HasValue ? Ok(saved.Value.Response) : NotFound(new { success = false, message = "Não há NFC-e local armazenada para esta venda e terminal." });
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
