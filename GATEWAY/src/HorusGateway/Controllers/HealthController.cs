/*
 * Arquivo: Controllers/HealthController.cs
 * Objetivo: health checks do Gateway (liveness, readiness e status agregado com pendências de sync).
 */
using HorusGateway.Configuration;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
public sealed class HealthController : ControllerBase
{
    private readonly GatewayIdentity _identity;
    private readonly IEventStore _eventStore;
    private readonly CloudSyncState _cloudSync;
    private readonly GatewayOptions _options;
    private readonly ILogger<HealthController> _logger;

    public HealthController(
        GatewayIdentity identity,
        IEventStore eventStore,
        CloudSyncState cloudSync,
        IOptions<GatewayOptions> options,
        ILogger<HealthController> logger)
    {
        _identity = identity;
        _eventStore = eventStore;
        _cloudSync = cloudSync;
        _options = options.Value;
        _logger = logger;
    }

    /// <summary>Liveness — o processo está de pé.</summary>
    [HttpGet("/health/live")]
    public IActionResult Live() => Ok(new { status = "alive" });

    /// <summary>Readiness — o storage local responde.</summary>
    [HttpGet("/health/ready")]
    public async Task<IActionResult> Ready()
    {
        try
        {
            await _eventStore.CountAsync(_identity.CompanyId);
            return Ok(new { status = "ready", storage = "healthy" });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Readiness falhou: storage indisponível.");
            return StatusCode(503, new { status = "not-ready", storage = "unhealthy" });
        }
    }

    /// <summary>Status agregado do Gateway (usado pelo dashboard local — CHANGE GATEWAY 08).</summary>
    [HttpGet("/health")]
    public async Task<IActionResult> Health()
    {
        try
        {
            var pending = _identity.IsBound ? await _eventStore.CountPendingCloudAsync(_identity.CompanyId) : 0;
            var cloudEnabled = !string.IsNullOrWhiteSpace(_options.CloudSyncUrl);
            var cloud = !cloudEnabled ? "disabled" : (_cloudSync.Online ? "online" : "offline");
            return Ok(new
            {
                gateway = "healthy",
                storage = "healthy",
                cloud,
                pendingEvents = pending,
                cloudSync = _cloudSync.Snapshot()
            });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Health falhou.");
            return StatusCode(503, new { gateway = "unhealthy", storage = "unhealthy" });
        }
    }
}
