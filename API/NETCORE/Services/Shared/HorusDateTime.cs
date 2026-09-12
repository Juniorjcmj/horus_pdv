/**
 * Arquivo: API/NETCORE/Services/Shared/HorusDateTime.cs
 * Objetivo: centraliza a resolução e formatação de data/hora no Fuso Horário Oficial de Brasília
 *           (UTC-3 / America/Sao_Paulo / E. South America Standard Time), garantindo que
 *           emissões fiscais (NFC-e), histórico de vendas e controle de caixa operem com o horário
 *           correto do Brasil, independentemente de o servidor/Docker estar rodando em UTC.
 */
using System.Globalization;

namespace HORUSPDV_API.Services.Shared;

public static class HorusDateTime
{
    private static readonly CultureInfo PtBr = new("pt-BR");

    private static readonly Lazy<TimeZoneInfo> BrasiliaTimeZoneLazy = new(() =>
    {
        try
        {
            return TimeZoneInfo.FindSystemTimeZoneById("America/Sao_Paulo");
        }
        catch (TimeZoneNotFoundException)
        {
            try
            {
                return TimeZoneInfo.FindSystemTimeZoneById("E. South America Standard Time");
            }
            catch (TimeZoneNotFoundException)
            {
                // Fallback caso a base de timezones não contenha nenhuma das chaves: cria fuso fixo UTC-3
                return TimeZoneInfo.CreateCustomTimeZone(
                    "Brasilia_Standard_Time",
                    TimeSpan.FromHours(-3),
                    "(UTC-03:00) Brasilia",
                    "Horario Oficial do Brasil");
            }
        }
    });

    /// <summary>Instância do Fuso Horário Oficial de Brasília / São Paulo.</summary>
    public static TimeZoneInfo TimeZone => BrasiliaTimeZoneLazy.Value;

    /// <summary>
    /// Retorna o DateTimeOffset atual convertido rigorosamente para o Fuso de Brasília (-03:00).
    /// </summary>
    public static DateTimeOffset Now => TimeZoneInfo.ConvertTime(DateTimeOffset.UtcNow, TimeZone);

    /// <summary>
    /// Retorna o DateTime atual (sem offset) no fuso de Brasília.
    /// </summary>
    public static DateTime NowDateTime => Now.DateTime;

    /// <summary>
    /// Converte um DateTimeOffset qualquer para o fuso de Brasília.
    /// </summary>
    public static DateTimeOffset ToBrasilia(DateTimeOffset value) => TimeZoneInfo.ConvertTime(value, TimeZone);

    /// <summary>
    /// Converte um DateTime (UTC ou não especificado) para DateTimeOffset no fuso de Brasília.
    /// </summary>
    public static DateTimeOffset ToBrasilia(DateTime value)
    {
        if (value.Kind == DateTimeKind.Utc)
        {
            return TimeZoneInfo.ConvertTime(new DateTimeOffset(value, TimeSpan.Zero), TimeZone);
        }

        // Se for Local ou Unspecified, converte considerando UTC como base se o servidor for UTC
        var utc = DateTime.SpecifyKind(value, DateTimeKind.Utc);
        return TimeZoneInfo.ConvertTime(new DateTimeOffset(utc, TimeSpan.Zero), TimeZone);
    }

    /// <summary>
    /// Formata no padrão brasileiro completo: "dd/MM/yyyy HH:mm:ss" no horário de Brasília.
    /// </summary>
    public static string Format(DateTimeOffset value) =>
        ToBrasilia(value).ToString("dd/MM/yyyy HH:mm:ss", PtBr);

    /// <summary>
    /// Formata no padrão brasileiro completo para DateTimeOffset anulável.
    /// </summary>
    public static string Format(DateTimeOffset? value, string fallback = "-") =>
        value.HasValue ? Format(value.Value) : fallback;

    /// <summary>
    /// Formata no padrão brasileiro abreviado: "dd/MM/yyyy HH:mm" no horário de Brasília.
    /// </summary>
    public static string FormatShort(DateTimeOffset value) =>
        ToBrasilia(value).ToString("dd/MM/yyyy HH:mm", PtBr);

    /// <summary>
    /// Formata apenas a data brasileira: "dd/MM/yyyy".
    /// </summary>
    public static string FormatDate(DateTimeOffset value) =>
        ToBrasilia(value).ToString("dd/MM/yyyy", PtBr);

    /// <summary>
    /// Formata em ISO 8601 preservando o offset oficial (-03:00).
    /// </summary>
    public static string FormatIso(DateTimeOffset value) =>
        ToBrasilia(value).ToString("o", CultureInfo.InvariantCulture);
}
