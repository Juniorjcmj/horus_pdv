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
            var cloudEnabled = _options.CloudSyncConfigured;
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

    /// <summary>
    /// Prontidão para atualização controlada (CHANGE GATEWAY 08): antes de parar/atualizar o Gateway,
    /// o operador confirma que não há eventos ainda não sincronizados com a cloud — evitando perder
    /// dados presos apenas no SQLite local. É seguro quando não há pendências (nada a drenar).
    /// </summary>
    [HttpGet("/health/update-readiness")]
    public async Task<IActionResult> UpdateReadiness()
    {
        try
        {
            var pending = _identity.IsBound ? await _eventStore.CountPendingCloudAsync(_identity.CompanyId) : 0;
            var cloudEnabled = _options.CloudSyncConfigured;
            var cloud = !cloudEnabled ? "disabled" : (_cloudSync.Online ? "online" : "offline");
            var safe = pending == 0;

            string reason;
            if (safe && !cloudEnabled)
            {
                reason = "Seguro: modo LAN-only (sync desligado) e sem eventos pendentes locais.";
            }
            else if (safe)
            {
                reason = "Seguro: todos os eventos já foram sincronizados com a cloud.";
            }
            else if (cloud == "online")
            {
                reason = $"Aguarde: {pending} evento(s) ainda drenando para a cloud. Atualize quando zerar.";
            }
            else
            {
                reason = $"NÃO atualize: {pending} evento(s) presos localmente e a cloud está {cloud}. " +
                         "Restaure a conexão e aguarde a sincronização antes de parar o Gateway.";
            }

            return Ok(new
            {
                safeToUpdate = safe,
                pendingEvents = pending,
                cloud,
                reason,
                checkedAt = DateTimeOffset.UtcNow.ToString("o")
            });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Update-readiness falhou.");
            return StatusCode(503, new { safeToUpdate = false, reason = "Falha ao consultar o storage local." });
        }
    }
}
