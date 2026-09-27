/*
 * Arquivo: Controllers/TerminalsController.cs
 * Objetivo: registro/autorização de terminais na LAN (rede não confiável), heartbeat e listagem
 *           para o dashboard. O Gateway autentica o TERMINAL ("pertence a esta loja?"), nunca o
 *           usuário — a autenticação de usuário continua na cloud.
 */
using HorusGateway.Configuration;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
[Route("api/gateway")]
public sealed class TerminalsController : ControllerBase
{
    private readonly GatewayIdentity _identity;
    private readonly ITerminalStore _terminals;
    private readonly IEventStore _eventStore;
    private readonly GatewayOptions _options;
    private readonly ILogger<TerminalsController> _logger;

    public TerminalsController(
        GatewayIdentity identity,
        ITerminalStore terminals,
        IEventStore eventStore,
        IOptions<GatewayOptions> options,
        ILogger<TerminalsController> logger)
    {
        _identity = identity;
        _terminals = terminals;
        _eventStore = eventStore;
        _options = options.Value;
        _logger = logger;
    }

    /// <summary>Registro/autorização do terminal. Requer o token compartilhado provisionado pelo admin.</summary>
    [HttpPost("register")]
    public async Task<IActionResult> Register([FromBody] RegisterTerminalRequest request, CancellationToken cancellationToken)
    {
        if (request is null || string.IsNullOrWhiteSpace(request.TerminalId))
        {
            return BadRequest(new { error = "terminalId é obrigatório." });
        }

        if (!_identity.IsBound)
        {
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa (CompanyId ausente)." });
        }

        // Registro na LAN exige token compartilhado; sem ele configurado, o registro fica indisponível.
        if (string.IsNullOrWhiteSpace(_options.RegistrationToken))
        {
            _logger.LogError("Registro recusado: RegistrationToken não configurado no Gateway.");
            return StatusCode(503, new { error = "Registro de terminais indisponível (token não configurado)." });
        }

        if (!FixedTimeEquals(request.RegistrationToken, _options.RegistrationToken))
        {
            _logger.LogWarning("Registro recusado: token inválido para TerminalId={TerminalId}", request.TerminalId);
            return Unauthorized(new { error = "Token de registro inválido." });
        }

        if (!_identity.Accepts(request.CompanyId))
        {
            _logger.LogWarning("Registro recusado: CompanyId {Received} ≠ {Expected}", request.CompanyId, _identity.CompanyId);
            return StatusCode(403, new { error = "CompanyId não pertence a este Gateway." });
        }

        var result = await _terminals.RegisterAsync(request, _identity.GatewayId, cancellationToken);
        return Ok(result);
    }

    /// <summary>Heartbeat do terminal (a cada 10–30s) — atualiza LastSeenAt e devolve pendências.</summary>
    [HttpPost("heartbeat")]
    public async Task<IActionResult> Heartbeat(CancellationToken cancellationToken)
    {
        var auth = await _terminals.AuthenticateAsync(TerminalIdHeader, ApiKeyHeader, cancellationToken);
        if (!auth.Authenticated)
        {
            return Unauthorized(new { error = "Credencial de terminal inválida.", reason = auth.Outcome.ToString() });
        }

        await _terminals.TouchAsync(_identity.CompanyId, auth.Terminal!.TerminalId, cancellationToken);
        var pending = await _eventStore.CountPendingCloudAsync(_identity.CompanyId, cancellationToken);

        return Ok(new
        {
            status = "ok",
            terminalId = auth.Terminal.TerminalId,
            serverTime = DateTimeOffset.UtcNow.ToString("o"),
            pendingEvents = pending
        });
    }

    /// <summary>Lista terminais da empresa (dashboard) — nunca expõe credenciais.</summary>
    [HttpGet("terminals")]
    public async Task<IActionResult> List(CancellationToken cancellationToken)
    {
        if (!_identity.IsBound)
        {
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa." });
        }

        var terminals = await _terminals.ListAsync(_identity.CompanyId, cancellationToken);
        return Ok(new { companyId = _identity.CompanyId, count = terminals.Count, terminals });
    }

    private string? TerminalIdHeader => Request.Headers["X-Terminal-Id"].FirstOrDefault();
    private string? ApiKeyHeader => Request.Headers["X-Terminal-Key"].FirstOrDefault();

    private static bool FixedTimeEquals(string? a, string? b)
    {
        if (a is null || b is null) return false;
        var ba = System.Text.Encoding.UTF8.GetBytes(a);
        var bb = System.Text.Encoding.UTF8.GetBytes(b);
        return ba.Length == bb.Length &&
               System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(ba, bb);
    }
}
