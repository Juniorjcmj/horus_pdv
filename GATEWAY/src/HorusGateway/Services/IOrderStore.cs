/*
 * Arquivo: Services/IOrderStore.cs
 * Objetivo: projeção de pedidos derivada dos eventos ORDER_* — aplicação de transições, consulta e listagem.
 */
using HorusGateway.Models;

namespace HorusGateway.Services;

public interface IOrderStore
{
    /// <summary>Estado atual do pedido (null se não existir) — usado para validar a transição antes de gravar o evento.</summary>
    Task<string?> GetStatusAsync(string companyId, string orderNumber, CancellationToken cancellationToken = default);

    /// <summary>Aplica um evento ORDER_* à projeção, respeitando a máquina de estados.</summary>
    Task<OrderApplyResult> ApplyAsync(GatewayEvent ev, CancellationToken cancellationToken = default);

    Task<OrderView?> GetAsync(string companyId, string orderNumber, CancellationToken cancellationToken = default);

    /// <summary>Lista pedidos da empresa (opcionalmente por status), mais recentes primeiro.</summary>
    Task<IReadOnlyList<OrderView>> ListAsync(string companyId, string? status, CancellationToken cancellationToken = default);

    /// <summary>Extrai o número do pedido do payload (aceita "orderNumber" ou "orderId").</summary>
    static string? ReadOrderNumber(string payloadJson)
    {
        try
        {
            using var doc = System.Text.Json.JsonDocument.Parse(string.IsNullOrWhiteSpace(payloadJson) ? "{}" : payloadJson);
            var root = doc.RootElement;
            foreach (var key in new[] { "orderNumber", "orderId" })
            {
                if (root.TryGetProperty(key, out var prop))
                {
                    var value = prop.ValueKind == System.Text.Json.JsonValueKind.String
                        ? prop.GetString()
                        : prop.GetRawText();
                    if (!string.IsNullOrWhiteSpace(value)) return value.Trim();
                }
            }
        }
        catch (System.Text.Json.JsonException)
        {
            // payload inválido → sem número de pedido
        }
        return null;
    }
}
