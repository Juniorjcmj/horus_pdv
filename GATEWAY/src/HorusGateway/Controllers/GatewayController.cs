/*
 * Arquivo: Controllers/GatewayController.cs
 * Objetivo: API local do Gateway — status/descoberta, ingestão de eventos (Terminal → Gateway) e
 *           recuperação incremental de eventos (terminal que ficou offline). Após persistir um evento,
 *           publica-o em tempo real (SignalR) para os demais terminais da mesma empresa.
 */
using HorusGateway.Configuration;
using HorusGateway.Hubs;
using HorusGateway.Models;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
[Route("api/gateway")]
public sealed class GatewayController : ControllerBase
{
    private const int MaxRecoveryLimit = 500;

    private readonly GatewayIdentity _identity;
    private readonly IEventStore _eventStore;
    private readonly ITerminalStore _terminals;
    private readonly IOrderStore _orders;
    private readonly IHubContext<EventsHub> _hub;
    private readonly GatewayOptions _options;
    private readonly ILogger<GatewayController> _logger;

    public GatewayController(
        GatewayIdentity identity,
        IEventStore eventStore,
        ITerminalStore terminals,
        IOrderStore orders,
        IHubContext<EventsHub> hub,
        IOptions<GatewayOptions> options,
        ILogger<GatewayController> logger)
    {
        _identity = identity;
        _eventStore = eventStore;
        _terminals = terminals;
        _orders = orders;
        _hub = hub;
        _options = options.Value;
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
        registrationRequired = true,
        terminalAuthRequired = _options.RequireTerminalAuth,
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

        // Autenticação de terminal (LAN não confiável): a credencial deve ser válida e o terminal
        // autenticado precisa coincidir com o terminalId do evento (evita spoofing).
        if (_options.RequireTerminalAuth)
        {
            var auth = await AuthenticateTerminalAsync(cancellationToken);
            if (!auth.Authenticated)
            {
                return Unauthorized(new { error = "Credencial de terminal inválida.", reason = auth.Outcome.ToString() });
            }
            if (!string.Equals(auth.Terminal!.TerminalId, request.TerminalId?.Trim(), StringComparison.Ordinal))
            {
                return StatusCode(403, new { error = "terminalId do evento não corresponde ao terminal autenticado." });
            }
        }

        // Pré-validação da transição de pedido: rejeita transição inválida ANTES de gravar o evento.
        // Só para eventos NOVOS — um EventId já conhecido é replay idempotente e não revalida transição.
        if (OrderEventType.IsOrderEvent(request.EventType))
        {
            var orderNumber = IOrderStore.ReadOrderNumber(request.Payload.ValueKind == System.Text.Json.JsonValueKind.Undefined ? "{}" : request.Payload.GetRawText());
            if (string.IsNullOrWhiteSpace(orderNumber))
            {
                return BadRequest(new { error = "evento ORDER_* requer orderNumber/orderId no payload." });
            }

            var alreadyKnown = await _eventStore.ExistsAsync(_identity.CompanyId, request.EventId!, cancellationToken);
            if (!alreadyKnown)
            {
                var current = await _orders.GetStatusAsync(_identity.CompanyId, orderNumber, cancellationToken);
                if (!OrderStateMachine.TryNext(current, request.EventType!, out _))
                {
                    return Conflict(new
                    {
                        error = "transição de pedido inválida.",
                        orderNumber,
                        currentStatus = current,
                        eventType = request.EventType
                    });
                }
            }
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
                // Publica o evento para os demais terminais da mesma empresa (tempo real).
                await _hub.Clients.Group(EventsHub.GroupFor(_identity.CompanyId))
                    .SendAsync("eventReceived", dto, cancellationToken);

                // Atualiza a projeção de pedidos e notifica o caixa/terminais do estado atual.
                OrderView? order = null;
                if (OrderEventType.IsOrderEvent(request.EventType) && result.Event is not null)
                {
                    var applied = await _orders.ApplyAsync(result.Event, cancellationToken);
                    if (applied.Ok)
                    {
                        order = applied.Order;
                        await _hub.Clients.Group(EventsHub.GroupFor(_identity.CompanyId))
                            .SendAsync("orderUpdated", order, cancellationToken);
                    }
                    else
                    {
                        // Pré-checagem passou mas a aplicação falhou (raro; ex.: corrida). Mantém o evento como auditoria.
                        _logger.LogWarning("Evento ORDER aceito, mas projeção não aplicou: {Reason}", applied.Message);
                    }
                }

                return Ok(new { status = "accepted", ack = true, event_ = dto, order });

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

        if (_options.RequireTerminalAuth)
        {
            var auth = await AuthenticateTerminalAsync(cancellationToken);
            if (!auth.Authenticated)
            {
                return Unauthorized(new { error = "Credencial de terminal inválida.", reason = auth.Outcome.ToString() });
            }
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

    private Task<TerminalAuthResult> AuthenticateTerminalAsync(CancellationToken cancellationToken)
    {
        var terminalId = Request.Headers["X-Terminal-Id"].FirstOrDefault();
        var apiKey = Request.Headers["X-Terminal-Key"].FirstOrDefault();
        return _terminals.AuthenticateAsync(terminalId, apiKey, cancellationToken);
    }

    private static GatewayEventDto? DtoOf(IngestResult result)
        => result.Event is null ? null : GatewayEventDto.From(result.Event);
}
