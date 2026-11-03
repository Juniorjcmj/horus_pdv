package br.com.quack.estoque.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Etiqueta de balança (EAN-13 de peso/preço variável, prefixo 2). Cópia da regra do caixa
 * (JAVA/caixa/.../BalancaBarcode.java, porta de balancaBarcode.ts), adaptada ao {@link Produto}.
 */
public final class BalancaBarcode {

    public enum Mode { PRICE, WEIGHT }

    /** totalPrice pode ser null. */
    public record Result(String productCode, BigDecimal weightKg, BigDecimal totalPrice, Mode mode) {
    }

    private BalancaBarcode() {
    }

    public static boolean isValidEan13(String d) {
        if (d == null || d.length() != 13 || !d.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = d.charAt(i) - '0';
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return (10 - (sum % 10)) % 10 == d.charAt(12) - '0';
    }

    public static Result parse(String value, List<Produto> known) {
        if (value == null) {
            return null;
        }
        String d = value.trim();
        if (d.length() != 13 || !d.chars().allMatch(c -> c >= '0' && c <= '9') || d.charAt(0) != '2'
                || !isValidEan13(d)) {
            return null;
        }
        if (known != null && !known.isEmpty()) {
            String c4 = d.substring(1, 5);
            Produto p4 = find(known, c4);
            if (p4 != null) {
                BigDecimal total = cents(d.substring(5, 12));
                return new Result(p4.codigo(), weightFromPrice(total, p4.preco()), total, Mode.PRICE);
            }
            String c5 = d.substring(1, 6);
            Produto p5 = find(known, c5);
            if (p5 != null) {
                long priceCents = Long.parseLong(d.substring(6, 12));
                long grams = Long.parseLong(d.substring(6, 11));
                if (p5.preco().signum() > 0 && priceCents > 0) {
                    BigDecimal w = cents(d.substring(6, 12)).divide(p5.preco(), 3, RoundingMode.HALF_UP);
                    if (w.signum() > 0 && w.compareTo(new BigDecimal("50")) <= 0) {
                        return new Result(p5.codigo(), w, cents(d.substring(6, 12)), Mode.PRICE);
                    }
                }
                if (grams > 0) {
                    BigDecimal kg = BigDecimal.valueOf(grams).movePointLeft(3);
                    BigDecimal tp = p5.preco().signum() > 0
                            ? kg.multiply(p5.preco()).setScale(2, RoundingMode.HALF_UP) : null;
                    return new Result(p5.codigo(), kg, tp, Mode.WEIGHT);
                }
            }
            String c6 = d.substring(1, 7);
            Produto p6 = find(known, c6);
            if (p6 != null) {
                BigDecimal total = cents(d.substring(7, 12));
                return new Result(p6.codigo(), weightFromPrice(total, p6.preco()), total, Mode.PRICE);
            }
        }
        if (d.substring(5, 8).equals("000")) {
            return new Result(d.substring(1, 5), BigDecimal.ONE, cents(d.substring(5, 12)), Mode.PRICE);
        }
        long grams = Long.parseLong(d.substring(6, 11));
        BigDecimal kg = grams > 0 ? BigDecimal.valueOf(grams).movePointLeft(3) : BigDecimal.ONE;
        return new Result(d.substring(1, 6), kg, null, Mode.WEIGHT);
    }

    private static BigDecimal cents(String digits) {
        return new BigDecimal(digits).movePointLeft(2);
    }

    private static BigDecimal weightFromPrice(BigDecimal total, BigDecimal price) {
        return price.signum() > 0 ? total.divide(price, 3, RoundingMode.HALF_UP) : BigDecimal.ONE;
    }

    /** Casa o PLU por texto igual ou valor numérico igual. */
    public static Produto find(List<Produto> products, String code) {
        for (Produto p : products) {
            if (sameCode(p.codigo(), code)) {
                return p;
            }
        }
        return null;
    }

    public static boolean sameCode(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
