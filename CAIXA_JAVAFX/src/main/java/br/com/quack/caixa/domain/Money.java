package br.com.quack.caixa.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Dinheiro e quantidades em BigDecimal, com a mesma aritmética de centavos do caixa web. */
public final class Money {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final Locale PT_BR = Locale.of("pt", "BR");

    private Money() {
    }

    public static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal round4(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(double v) {
        return round2(BigDecimal.valueOf(v));
    }

    /** Aceita "1.234,56", "12.9", "R$ 5,00". Inválido lança DomainException. */
    public static BigDecimal parseBr(String text) {
        if (text == null) {
            throw new DomainException("Valor inválido.");
        }
        String s = text.replace("R$", "").replaceAll("\s", "");
        if (s.isEmpty()) {
            throw new DomainException("Valor inválido.");
        }
        if (s.contains(",")) {
            s = s.replace(".", "").replace(",", ".");
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new DomainException("Valor inválido: " + text);
        }
    }

    /** Versão tolerante: vazio/inválido vira zero. */
    public static BigDecimal parseBrOrZero(String text) {
        try {
            return parseBr(text);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    public static String formatBr(BigDecimal v) {
        DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(PT_BR));
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(v == null ? BigDecimal.ZERO : v);
    }

    public static String formatQty(BigDecimal q) {
        if (q == null) {
            return "0";
        }
        BigDecimal r = q.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        if (r.scale() < 0) {
            r = r.setScale(0);
        }
        return r.toPlainString().replace('.', ',');
    }

    /** round2(q × p), arredondando uma única vez. */
    public static BigDecimal productMoney(BigDecimal q, BigDecimal p) {
        return round2(q.multiply(p));
    }

    /** bruto − desconto; valida q > 0, p ≥ 0, d ≥ 0 e d ≤ bruto. */
    public static BigDecimal itemNet(BigDecimal q, BigDecimal p, BigDecimal d) {
        if (q.signum() <= 0) {
            throw new DomainException("Quantidade inválida.");
        }
        if (p.signum() < 0) {
            throw new DomainException("Preço unitário inválido.");
        }
        if (d.signum() < 0) {
            throw new DomainException("Desconto inválido.");
        }
        BigDecimal gross = productMoney(q, p);
        BigDecimal disc = round2(d);
        if (disc.compareTo(gross) > 0) {
            throw new DomainException("Desconto maior que o valor do item.");
        }
        return gross.subtract(disc);
    }

    /** Serialização numérica igual ao JSON.stringify do JS (10.50 -> 10.5, 10.00 -> 10). */
    public static String jsNumber(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) {
            s = s.setScale(0);
        }
        return s.toPlainString();
    }
}
