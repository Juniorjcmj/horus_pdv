/*
 * Arquivo: Controllers/GatewayController.cs
 * Objetivo: API local do Gateway — status/descoberta, ingestão de eventos (Terminal → Gateway) e
 *           recuperação incremental de eventos (terminal que ficou offline). Após persistir um evento,
 *           publica-o em tempo real (SignalR) para os demais terminais da mesma empresa.
 */
using HorusGateway.Hubs;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;

namespace HorusGateway.Controllers;

[ApiController]
[Route("api/gateway")]
public sealed class GatewayController : ControllerBase
{
    private const int MaxRecoveryLimit = 500;

    private readonly GatewayIdentity _identity;
    private readonly IEventStore _eventStore;
    private readonly IHubContext<EventsHub> _hub;
    private readonly ILogger<GatewayController> _logger;

    public GatewayController(
        GatewayIdentity identity,
        IEventStore eventStore,
        IHubContext<EventsHub> hub,
        ILogger<GatewayController> logger)
    {
        _identity = identity;
        _eventStore = eventStore;
        _hub = hub;
        _logger = logger;
    }

    /// <summary>Descoberta/status — o terminal usa para confirmar que achou o Gateway certo (empresa/loja).</summary>
    [HttpGet("status")]
    public IActionResult Status() => Ok(new
    {
        service = "HorusGateway",
        gatewayId = _identity.GatewayId,
        companyId = _identity.CompanyId,
        storeId = _identity.StoreId,
        bound = _identity.IsBound,
        realtimeHub = "/hubs/events",
        serverTime = DateTimeOffset.UtcNow.ToString("o")
    });

    /// <summary>Ingestão de evento (Terminal → Gateway). Idempotente e isolada por empresa.</summary>
    [HttpPost("events")]
    public async Task<IActionResult> Ingest([FromBody] IngestEventRequest request, CancellationToken cancellationToken)
    {
        if (request is null)
        {
            return BadRequest(new { error = "corpo do evento ausente." });
        }

        // Isolamento multi-tenant: nunca aceitar evento de outra empresa.
        if (!_identity.IsBound)
        {
            _logger.LogError("Gateway sem CompanyId configurado — recusando ingestão.");
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa (CompanyId ausente)." });
        }

        if (!_identity.Accepts(request.CompanyId))
        {
            _logger.LogWarning("Ingestão recusada: CompanyId {Received} ≠ {Expected}", request.CompanyId, _identity.CompanyId);
            return StatusCode(403, new { error = "CompanyId não pertence a este Gateway." });
        }

        var result = await _eventStore.AppendAsync(request, cancellationToken);

        switch (result.Outcome)
        {
            case IngestOutcome.Invalid:
                return BadRequest(new { error = result.Message });

            case IngestOutcome.Conflict:
                return Conflict(new { error = result.Message, eventId = request.EventId });

            case IngestOutcome.Replay:
                // Replay legítimo: ACK idempotente, sem republicar.
                return Ok(new { status = "replay", ack = true, event_ = DtoOf(result) });

            case IngestOutcome.Accepted:
                var dto = DtoOf(result)!;
                // Publica para os demais terminais da mesma empresa (tempo real).
                await _hub.Clients.Group(EventsHub.GroupFor(_identity.CompanyId))
                    .SendAsync("eventReceived", dto, cancellationToken);
                return Ok(new { status = "accepted", ack = true, event_ = dto });

            default:
                return StatusCode(500, new { error = "desfecho de ingestão desconhecido." });
        }
    }

    /// <summary>Recuperação incremental — terminal que ficou offline puxa eventos com Seq &gt; cursor.</summary>
    [HttpGet("events")]
    public async Task<IActionResult> GetEvents(
        [FromQuery] string? companyId,
        [FromQuery] long after = 0,
        [FromQuery] int limit = 100,
        CancellationToken cancellationToken = default)
    {
        var company = string.IsNullOrWhiteSpace(companyId) ? _identity.CompanyId : companyId.Trim();

        if (!_identity.IsBound || !string.Equals(company, _identity.CompanyId, StringComparison.Ordinal))
        {
            return StatusCode(403, new { error = "CompanyId não pertence a este Gateway." });
        }

        var effectiveLimit = Math.Clamp(limit, 1, MaxRecoveryLimit);
        var events = await _eventStore.GetEventsAsync(company, after, effectiveLimit, cancellationToken);
        var items = events.Select(GatewayEventDto.From).ToList();
        var nextCursor = items.Count > 0 ? items[^1].Seq : after;

        return Ok(new
        {
            companyId = company,
            after,
            count = items.Count,
            nextCursor,
            events = items
        });
    }

    private static GatewayEventDto? DtoOf(IngestResult result)
        => result.Event is null ? null : GatewayEventDto.From(result.Event);
}
