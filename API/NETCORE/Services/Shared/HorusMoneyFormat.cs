/**
 * Arquivo: API/NETCORE/Services/Shared/HorusMoneyFormat.cs
 * Objetivo: centraliza a conversão entre o formato monetário/quantidade pt-BR trafegado no
 *           contrato JSON (ex.: "1.234,56") e o `decimal` nativo usado nas colunas do banco.
 * Entradas esperadas: recebe strings pt-BR vindas do frontend ou `decimal` lidos do SQL Server.
 *
 * Substitui as quatro implementações antes duplicadas da mesma normalização
 * (ProdutoAB.CalculateTotal, ProdutoService.ParseMoney, HistoricoVendasAB.CalculateTotal,
 * RelatorioAB.ParseMoney). O contrato JSON com o frontend não muda — só o meio de campo
 * (leitura/escrita no banco) passa a usar `decimal` nativo em vez de round-trip de string.
 */
using System.Globalization;

namespace HORUSPDV_API.Services.Shared;

public static class HorusMoneyFormat
{
    private static readonly CultureInfo PtBr = new("pt-BR");

    /// <summary>Tenta converter um valor monetário/quantidade pt-BR ou invariante para decimal.</summary>
    public static bool TryParseDecimal(string? value, out decimal result)
    {
        result = 0m;
        if (string.IsNullOrWhiteSpace(value)) return false;

        var normalized = value
            .Trim()
            .Replace("R$", string.Empty, StringComparison.OrdinalIgnoreCase)
            .Replace(" ", string.Empty);

        if (normalized is "" or "-") return false;

        // Se tem vírgula, formato pt-BR: remove pontos de milhar e substitui vírgula por ponto
        if (normalized.Contains(','))
        {
            normalized = normalized.Replace(".", string.Empty).Replace(",", ".");
        }

        return decimal.TryParse(normalized, NumberStyles.Number, CultureInfo.InvariantCulture, out result);
    }

    /// <summary>
    /// Converte estritamente um valor monetário/quantidade para decimal.
    /// Lança ArgumentException se o valor for nulo, vazio ou inválido (nunca mascara como 0 silenciosamente).
    /// </summary>
    public static decimal ParseDecimalStrict(string? value, string fieldName = "valor")
    {
        if (TryParseDecimal(value, out var result))
        {
            return result;
        }

        throw new ArgumentException($"O campo '{fieldName}' possui valor numérico/monetário inválido: '{value}'.");
    }

    /// <summary>Converte um valor monetário/quantidade pt-BR ("1.234,56") para decimal com fallback opcional.</summary>
    public static decimal ParseDecimal(string? value, decimal fallback = 0m)
        => TryParseDecimal(value, out var parsed) ? parsed : fallback;

    /// <summary>Formata um valor monetário como pt-BR com 2 casas ("1.234,56").</summary>
    public static string Format(decimal value) => value.ToString("N2", PtBr);

    /// <summary>
    /// Formata quantidade pt-BR com até 4 casas, sem zeros à direita (10 -> "10", 0,4520 kg -> "0,452").
    /// Usada onde a quantidade pode ser fracionada (produto vendido por peso/volume).
    /// </summary>
    public static string FormatQuantity(decimal value)
        => value.ToString("0.####", CultureInfo.InvariantCulture).Replace('.', ',');
}
