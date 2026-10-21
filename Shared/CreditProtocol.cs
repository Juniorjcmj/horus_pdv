using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Horus.Credit;

// Protocol v1: amounts are integral cents; identities are scoped by authenticated company/store.
public sealed record CreditGrant(int Version, string GrantId, string CompanyId, string CustomerId,
    string CustomerDocument, string StoreId, string GatewayId, string Epoch, long AmountCents,
    DateTimeOffset ExpiresAt, string PolicyVersion);
public sealed record CreditReservation(int Version, string AuthorizationId, string SaleEventId,
    string GrantId, string CustomerId, long AmountCents, string OperatorId, string OperatorPermit = "");
public sealed record CreditOperatorPermit(string OperatorId,string TerminalId,string PermitHash,DateTimeOffset ExpiresAt);
public sealed record CreditProof(int Version, string AuthorizationId, string GrantId, string GatewayId,
    string Epoch, string StoreId, string CustomerId, long AmountCents, string SaleHash);
public sealed record CreditDecision(string State, string AuthorizationId, string SaleEventId,
    long AmountCents, DateTimeOffset ExpiresAt, string? SaleHash = null);
public sealed record CreditAuditEntry(long Seq, string AuthorizationId, string TerminalId,
    string State, long AmountCents, string OccurredAt);

public static class CreditProtocol
{
    public const int Version = 1;
    public static long Cents(decimal amount)
    {
        if (amount < 0 || decimal.Round(amount, 2) != amount) throw new InvalidOperationException("INVALID_MONEY");
        return checked((long)(amount * 100));
    }
    public static string Hash(JsonElement value)
    {
        var text = new StringBuilder();
        Write(value, text);
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(text.ToString()))).ToLowerInvariant();
    }
    public static string SaleHash(JsonElement sale)
    {
        string S(JsonElement e, string key) => e.TryGetProperty(key, out var p) && p.ValueKind != JsonValueKind.Null ? p.ToString().Trim() : "";
        decimal N(JsonElement e, string key, bool required = true)
        {
            var text = S(e,key);
            if(text.Length==0 && !required)return 0;
            if(e.TryGetProperty(key,out var value) && value.ValueKind==JsonValueKind.Number)return value.GetDecimal();
            if(text.Contains(','))text=text.Replace(".","").Replace(',', '.');
            if(!decimal.TryParse(text,System.Globalization.NumberStyles.AllowLeadingSign|System.Globalization.NumberStyles.AllowDecimalPoint,System.Globalization.CultureInfo.InvariantCulture,out var amount))
                throw new InvalidOperationException("INVALID_SALE_MONEY_"+key);
            return amount;
        }
        var canonical = new {
            customerCpf = new string(S(sale, "customerCpf").Where(char.IsDigit).ToArray()),
            clientSaleId = S(sale, "clientSaleId"), eventId = S(sale, "eventId"), orderNumber = S(sale,"orderNumber"),
            total = N(sale, "totalAmount"),
            items = sale.GetProperty("items").EnumerateArray().Select(i => new {
                code = S(i, "productCode"), quantity = N(i, "quantity"), price = N(i, "unitPrice"), discount = N(i, "desconto",false) }).ToArray(),
            payments = sale.GetProperty("payments").EnumerateArray().Select(p => new {
                type = S(p, "paymentType").ToLowerInvariant(), amount = N(p, "amount"), cash = N(p, "cashGiven",false), change = N(p, "changeAmount",false) }).ToArray()
        };
        return Hash(JsonSerializer.SerializeToElement(canonical));
    }
    private static void Write(JsonElement value, StringBuilder text)
    {
        if (value.ValueKind == JsonValueKind.Object)
        {
            text.Append('{'); var separator = "";
            foreach (var p in value.EnumerateObject().Where(p => p.Name is not ("creditAuthorization" or "payloadHash" or "reenvioOffline" or "xmlNfceContingencia" or "gatewayFiscalAttempt")).OrderBy(p => p.Name, StringComparer.Ordinal))
            { text.Append(separator).Append(JsonSerializer.Serialize(p.Name)).Append(':'); Write(p.Value, text); separator = ","; }
            text.Append('}');
        }
        else if (value.ValueKind == JsonValueKind.Array)
        { text.Append('['); var separator = ""; foreach (var item in value.EnumerateArray()) { text.Append(separator); Write(item, text); separator = ","; } text.Append(']'); }
        else if (value.ValueKind == JsonValueKind.Number) text.Append(value.GetDecimal().ToString("G29", System.Globalization.CultureInfo.InvariantCulture));
        else text.Append(value.GetRawText());
    }
}
