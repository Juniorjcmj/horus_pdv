package br.com.quack.pdv.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/**
 * Conversao e formatacao monetaria/quantidade pt-BR equivalente ao HorusMoneyFormat da API .NET,
 * mantendo o formatar(BigDecimal) com prefixo "R$ " usado pelas Fases 1 e 2.
 */
public final class Dinheiro {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private Dinheiro() {}

    public static String formatar(BigDecimal valor) {
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        formato.setRoundingMode(RoundingMode.HALF_EVEN);
        return "R$ " + formato.format(normalizarZero(valor, 2, RoundingMode.HALF_EVEN));
    }

    /** Equivale a HorusMoneyFormat.Format(decimal): pt-BR com 2 casas e separador de milhar ("1.234,56"). */
    public static String formatarSemSimbolo(BigDecimal valor) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(normalizarZero(v, 2, RoundingMode.HALF_UP));
    }

    /** Equivale a HorusMoneyFormat.FormatUnitPrice(decimal): "0.00##" em pt-BR (sem milhar, 2 a 4 casas). */
    public static String formatarPrecoUnitario(BigDecimal valor) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
        DecimalFormat formato = new DecimalFormat("0.00##", DecimalFormatSymbols.getInstance(PT_BR));
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(normalizarZero(v, 4, RoundingMode.HALF_UP));
    }

    /** Equivale a HorusMoneyFormat.FormatQuantity(decimal): "0.####" com virgula decimal, sem milhar. */
    public static String formatarQuantidade(BigDecimal valor) {
        return formatarInvarianteAte4Casas(valor).replace('.', ',');
    }

    /** Equivale a decimal.ToString("0.####", InvariantCulture): ate 4 casas com ponto decimal, sem zeros finais. */
    public static String formatarInvarianteAte4Casas(BigDecimal valor) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor.setScale(4, RoundingMode.HALF_UP);
        if (v.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return v.stripTrailingZeros().toPlainString();
    }

    /** Equivale a HorusMoneyFormat.TryParseDecimal(string?, out decimal). */
    public static Optional<BigDecimal> tentarLer(String valor) {
        if (valor == null || valor.isBlank()) {
            return Optional.empty();
        }
        String norm = valor.trim()
                .replaceAll("(?i)R\\$", "")
                .replace(" ", "");
        if (norm.isEmpty() || "-".equals(norm) || "+".equals(norm)) {
            return Optional.empty();
        }
        if (norm.indexOf('e') >= 0 || norm.indexOf('E') >= 0) {
            return Optional.empty();
        }
        if (norm.contains(",")) {
            norm = norm.replace(".", "").replace(",", ".");
        }
        try {
            return Optional.of(new BigDecimal(norm));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** Equivale a HorusMoneyFormat.ParseDecimal(string?, 0m). */
    public static BigDecimal ler(String valor) {
        return ler(valor, BigDecimal.ZERO);
    }

    /** Equivale a HorusMoneyFormat.ParseDecimal(string?, fallback). */
    public static BigDecimal ler(String valor, BigDecimal padrao) {
        return tentarLer(valor).orElse(padrao);
    }

    /** Equivale a HorusMoneyFormat.ParseDecimalStrict(string?, fieldName). */
    public static BigDecimal lerEstrito(String valor, String nomeCampo) {
        return tentarLer(valor).orElseThrow(() -> new IllegalArgumentException(
                "O campo '" + nomeCampo + "' possui valor numérico/monetário inválido: '" + valor + "'."));
    }

    private static BigDecimal normalizarZero(BigDecimal valor, int escala, RoundingMode modo) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
        if (v.setScale(escala, modo).compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return v;
    }
}
