/**
 * Arquivo: API/NETCORE/Services/Caixa/CaixaClientSession.cs
 * Objetivo: validações dos campos de sessão/horário enviados pelo cliente em abrir/fechar caixa
 *           (eventos gerados offline no PDV/Gateway e reenviados depois).
 */
using System.Globalization;
using System.Text.RegularExpressions;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Caixa;

public static partial class CaixaClientSession
{
    public static readonly TimeSpan MaxPast = TimeSpan.FromDays(30);
    public static readonly TimeSpan MaxFuture = TimeSpan.FromMinutes(5);

    [GeneratedRegex("^cx-[A-Za-z0-9-]{1,36}$")]
    private static partial Regex SessionIdRegex();

    /// <summary>Retorna o id normalizado (trim) ou null se vazio. Lança se o formato for inválido.</summary>
    public static string? NormalizeSessionId(string? value)
    {
        var id = value?.Trim();
        if (string.IsNullOrEmpty(id))
        {
            return null;
        }

        if (!SessionIdRegex().IsMatch(id))
        {
            throw new InvalidOperationException("Identificador de sessão de caixa inválido.");
        }

        return id;
    }

    /// <summary>
    /// Interpreta o horário informado pelo cliente. Retorna <paramref name="now"/> quando ausente, inválido ou
    /// fora da janela (agora-30 dias ... agora+5 min); <paramref name="outOfWindow"/> indica que foi descartado.
    /// </summary>
    public static DateTimeOffset ResolveTimestamp(string? value, DateTimeOffset now, out bool outOfWindow)
    {
        outOfWindow = false;
        if (string.IsNullOrWhiteSpace(value))
        {
            return now;
        }

        if (!DateTimeOffset.TryParse(value, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var parsed))
        {
            outOfWindow = true;
            return now;
        }

        if (parsed < now - MaxPast || parsed > now + MaxFuture)
        {
            outOfWindow = true;
            return now;
        }

        return TimeZoneInfo.ConvertTime(parsed, HorusDateTime.TimeZone);
    }
}
