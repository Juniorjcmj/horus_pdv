/*
 * Arquivo: Services/GatewayIdentity.cs
 * Objetivo: identidade resolvida do Gateway (GatewayId/CompanyId/StoreId), disponível por DI.
 *           GatewayId é gerado e estabilizado no boot quando não configurado.
 */
using HorusGateway.Configuration;
using Microsoft.Extensions.Options;

namespace HorusGateway.Services;

public sealed class GatewayIdentity
{
    public string GatewayId { get; }
    public string CompanyId { get; }
    public string StoreId { get; }

    public GatewayIdentity(IOptions<GatewayOptions> options)
    {
        var opts = options.Value;
        GatewayId = string.IsNullOrWhiteSpace(opts.GatewayId)
            ? $"gw_{Guid.NewGuid():N}"
            : opts.GatewayId.Trim();
        CompanyId = opts.CompanyId.Trim();
        StoreId = opts.StoreId.Trim();
    }

    /// <summary>True se o Gateway está vinculado a uma empresa (config obrigatória em produção).</summary>
    public bool IsBound => !string.IsNullOrWhiteSpace(CompanyId);

    /// <summary>Valida se um evento pertence à empresa deste Gateway (isolamento multi-tenant).</summary>
    public bool Accepts(string? companyId)
        => IsBound && string.Equals(companyId?.Trim(), CompanyId, StringComparison.Ordinal);
}
