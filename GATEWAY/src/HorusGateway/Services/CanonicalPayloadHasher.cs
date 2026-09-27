/*
 * Arquivo: Services/CanonicalPayloadHasher.cs
 * Objetivo: calcular um hash SHA-256 determinístico sobre um payload JSON arbitrário.
 *           A canonicalização ordena chaves de objetos recursivamente para que a mesma
 *           informação lógica produza sempre o mesmo hash (base da idempotência local).
 */
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace HorusGateway.Services;

public static class CanonicalPayloadHasher
{
    public static string Compute(JsonElement payload)
    {
        var canonical = Canonicalize(payload);
        var bytes = Encoding.UTF8.GetBytes(canonical);
        var hash = SHA256.HashData(bytes);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    /// <summary>Serializa o JSON de forma determinística: objetos com chaves ordenadas, arrays na ordem original.</summary>
    private static string Canonicalize(JsonElement element)
    {
        var sb = new StringBuilder();
        Write(element, sb);
        return sb.ToString();
    }

    private static void Write(JsonElement element, StringBuilder sb)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                sb.Append('{');
                var first = true;
                foreach (var prop in element.EnumerateObject().OrderBy(p => p.Name, StringComparer.Ordinal))
                {
                    if (!first) sb.Append(',');
                    first = false;
                    sb.Append(JsonSerializer.Serialize(prop.Name));
                    sb.Append(':');
                    Write(prop.Value, sb);
                }
                sb.Append('}');
                break;

            case JsonValueKind.Array:
                sb.Append('[');
                var firstItem = true;
                foreach (var item in element.EnumerateArray())
                {
                    if (!firstItem) sb.Append(',');
                    firstItem = false;
                    Write(item, sb);
                }
                sb.Append(']');
                break;

            case JsonValueKind.String:
                sb.Append(JsonSerializer.Serialize(element.GetString()));
                break;

            case JsonValueKind.Number:
                sb.Append(element.GetRawText());
                break;

            case JsonValueKind.True:
                sb.Append("true");
                break;

            case JsonValueKind.False:
                sb.Append("false");
                break;

            case JsonValueKind.Null:
            case JsonValueKind.Undefined:
                sb.Append("null");
                break;
        }
    }
}
