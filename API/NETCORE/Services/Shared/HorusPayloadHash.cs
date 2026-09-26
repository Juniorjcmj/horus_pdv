/**
 * Arquivo: API/NETCORE/Services/Shared/HorusPayloadHash.cs
 * Objetivo: calcula hash SHA-256 canônico e determinístico de um payload de venda
 *           para validação de idempotência (diferenciar retries legítimos de adulterações).
 */
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using HORUSPDV_API.Models.Requests;

namespace HORUSPDV_API.Services.Shared;

public static class HorusPayloadHash
{
    public static string ComputeHash(VendaRequest request)
    {
        var canonical = new
        {
            customerName = request.CustomerName?.Trim() ?? string.Empty,
            customerCpf = request.CustomerCpf?.Trim() ?? string.Empty,
            paymentType = request.PaymentType?.Trim() ?? string.Empty,
            totalAmount = HorusMoneyFormat.ParseDecimal(request.TotalAmount),
            operatorName = request.OperatorName?.Trim() ?? string.Empty,
            items = (request.Items ?? [])
                .OrderBy(i => i.ProductCode, StringComparer.OrdinalIgnoreCase)
                .Select(i => new
                {
                    productCode = i.ProductCode?.Trim() ?? string.Empty,
                    quantity = Math.Round(i.Quantity, 4),
                    unitPrice = Math.Round(i.UnitPrice, 2),
                    desconto = Math.Round(i.Desconto, 2),
                    promocaoId = i.PromocaoId ?? string.Empty
                })
                .ToList(),
            payments = (request.Payments ?? [])
                .OrderBy(p => p.PaymentType, StringComparer.OrdinalIgnoreCase)
                .Select(p => new
                {
                    paymentType = p.PaymentType?.Trim() ?? string.Empty,
                    amount = Math.Round(p.Amount, 2),
                    cashGiven = Math.Round(p.CashGiven, 2),
                    changeAmount = Math.Round(p.ChangeAmount, 2)
                })
                .ToList()
        };

        var json = JsonSerializer.Serialize(canonical);
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(json));
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }
}
