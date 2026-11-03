package br.com.quack.caixa.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Porta de balancaBarcode.ts (EAN-13 de peso/preço variável, prefixo 2). */
public final class BalancaBarcode {

    public enum Mode { PRICE, WEIGHT }

    /** totalPrice pode ser null. */
    public record Result(String productCode, BigDecimal weightKg, BigDecimal totalPrice, Mode mode) {
    }

    private BalancaBarcode() {
    }

    public static boolean isValidEan13(String d) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = d.charAt(i) - '0';
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return (10 - (sum % 10)) % 10 == d.charAt(12) - '0';
    }

    public static Result parse(String value, List<Product> known) {
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
            Product p4 = find(known, c4);
            if (p4 != null) {
                BigDecimal total = cents(d.substring(5, 12));
                return new Result(p4.code(), weightFromPrice(total, p4.price()), total, Mode.PRICE);
            }
            String c5 = d.substring(1, 6);
            Product p5 = find(known, c5);
            if (p5 != null) {
                long priceCents = Long.parseLong(d.substring(6, 12));
                long grams = Long.parseLong(d.substring(6, 11));
                if (p5.price().signum() > 0 && priceCents > 0) {
                    BigDecimal w = cents(d.substring(6, 12)).divide(p5.price(), 3, RoundingMode.HALF_UP);
                    if (w.signum() > 0 && w.compareTo(new BigDecimal("50")) <= 0) {
                        return new Result(p5.code(), w, cents(d.substring(6, 12)), Mode.PRICE);
                    }
                }
                if (grams > 0) {
                    BigDecimal kg = BigDecimal.valueOf(grams).movePointLeft(3);
                    BigDecimal tp = p5.price().signum() > 0 ? Money.round2(kg.multiply(p5.price())) : null;
                    return new Result(p5.code(), kg, tp, Mode.WEIGHT);
                }
            }
            String c6 = d.substring(1, 7);
            Product p6 = find(known, c6);
            if (p6 != null) {
                BigDecimal total = cents(d.substring(7, 12));
                return new Result(p6.code(), weightFromPrice(total, p6.price()), total, Mode.PRICE);
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
    public static Product find(List<Product> products, String code) {
        for (Product p : products) {
            if (sameCode(p.code(), code)) {
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
