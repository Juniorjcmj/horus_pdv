/*
 * Arquivo: Controllers/OrdersController.cs
 * Objetivo: consulta de pedidos pelo caixa (read model da projeção). As mudanças de estado
 *           (aceitar/cancelar/finalizar) acontecem via eventos ORDER_* na ingestão, não aqui —
 *           nunca se altera o estado sem registrar a transição.
 */
using HorusGateway.Configuration;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
[Route("api/gateway/orders")]
public sealed class OrdersController : ControllerBase
{
    private readonly GatewayIdentity _identity;
    private readonly IOrderStore _orders;
    private readonly ITerminalStore _terminals;
    private readonly GatewayOptions _options;

    public OrdersController(
        GatewayIdentity identity,
        IOrderStore orders,
        ITerminalStore terminals,
        IOptions<GatewayOptions> options)
    {
        _identity = identity;
        _orders = orders;
        _terminals = terminals;
        _options = options.Value;
    }

    /// <summary>Lista pedidos da empresa (opcionalmente por status) — visão do caixa.</summary>
    [HttpGet]
    public async Task<IActionResult> List([FromQuery] string? status, CancellationToken cancellationToken)
    {
        if (!_identity.IsBound)
        {
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa." });
        }
        if (await Denied(cancellationToken)) return Unauthorized(new { error = "Credencial de terminal inválida." });

        var orders = await _orders.ListAsync(_identity.CompanyId, status, cancellationToken);
        return Ok(new { companyId = _identity.CompanyId, count = orders.Count, orders });
    }

    [HttpGet("{orderNumber}")]
    public async Task<IActionResult> Get(string orderNumber, CancellationToken cancellationToken)
    {
        if (!_identity.IsBound)
        {
            return StatusCode(503, new { error = "Gateway não vinculado a uma empresa." });
        }
        if (await Denied(cancellationToken)) return Unauthorized(new { error = "Credencial de terminal inválida." });

        var order = await _orders.GetAsync(_identity.CompanyId, orderNumber, cancellationToken);
        return order is null ? NotFound(new { error = "pedido não encontrado.", orderNumber }) : Ok(order);
    }

    private async Task<bool> Denied(CancellationToken cancellationToken)
    {
        if (!_options.RequireTerminalAuth) return false;
        var auth = await _terminals.AuthenticateAsync(
            Request.Headers["X-Terminal-Id"].FirstOrDefault(),
            Request.Headers["X-Terminal-Key"].FirstOrDefault(),
            cancellationToken);
        return !auth.Authenticated;
    }
}
