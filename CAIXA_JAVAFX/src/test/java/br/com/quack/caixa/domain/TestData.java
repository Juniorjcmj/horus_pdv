package br.com.quack.caixa.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class TestData {
    private TestData() {
    }

    static Product product(String code, String price, String stock, String unit) {
        return new Product("id-" + code, code, "Produto " + code, "", new BigDecimal(price), new BigDecimal(stock),
                unit, "cat1", null, false, null, 15);
    }

    static CartItem item(String code, String qty, String price) {
        return CartItem.raw(product(code, price, "1000", "UN"), new BigDecimal(qty));
    }

    static Promotion promo(String tipo, String valor, String precoFixo, String leva, String paga, String min) {
        return new Promotion("promo-" + tipo, "Promo " + tipo, tipo, bd(valor), bd(precoFixo), bd(leva), bd(paga),
                bd(min), Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z"), true, null,
                List.of("id-P1"));
    }

    static BigDecimal bd(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    static Customer customer(String limit, String debt) {
        return new Customer("c1", "Maria", "12345678900", new BigDecimal(limit), new BigDecimal(debt));
    }
}
