/*
 * Arquivo: Controllers/CloudConnectionController.cs
 * Objetivo: GET /api/gateway/cloud — resultado do último teste do token da loja com a nuvem
 *           (configurado, conectado, empresa do token, divergência de empresa, último erro).
 *           Nunca devolve o token.
 */
using HorusGateway.Configuration;
using HorusGateway.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace HorusGateway.Controllers;

[ApiController]
public sealed class CloudConnectionController(
    CloudConnectionState state,
    GatewayIdentity identity,
    IOptions<GatewayOptions> options) : ControllerBase
{
    [HttpGet("/api/gateway/cloud")]
    public IActionResult Get() => Ok(new
    {
        configured = state.Configured,
        connected = state.Connected,
        cloudApiBaseUrl = options.Value.CloudApiBaseUrl,
        tokenConfigured = !string.IsNullOrWhiteSpace(options.Value.CloudSyncToken),
        localCompanyId = identity.CompanyId,
        cloudCompanyId = state.CloudCompanyId,
        cloudStoreId = state.CloudStoreId,
        companyMismatch = state.CompanyMismatch,
        lastCheckAt = state.LastCheckAt?.ToString("o"),
        lastSuccessAt = state.LastSuccessAt?.ToString("o"),
        lastError = state.LastError
    });
}
