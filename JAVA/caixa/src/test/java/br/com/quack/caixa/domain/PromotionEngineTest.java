package br.com.quack.caixa.domain;

import static br.com.quack.caixa.domain.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionEngineTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private CartItem apply(CartItem item, Promotion... promos) {
        return PromotionEngine.apply(List.of(item), List.of(promos), NOW).get(0);
    }

    private CartItem p1(String qty, String price) {
        return CartItem.raw(product("P1", price, "1000", "UN"), new BigDecimal(qty));
    }

    @Test
    void percentual() {
        CartItem r = apply(p1("2", "10.00"), promo("desconto_percentual", "10", null, null, null, null));
        assertEquals(new BigDecimal("2.00"), r.desconto());
        assertEquals(new BigDecimal("18.00"), r.total());
        assertEquals("promo-desconto_percentual", r.promocaoId());
    }

    @Test
    void valorLimitadoAoBruto() {
        CartItem r = apply(p1("1", "5.00"), promo("desconto_valor", "9", null, null, null, null));
        assertEquals(new BigDecimal("5.00"), r.desconto());
        assertEquals(new BigDecimal("0.00"), r.total());
    }

    @Test
    void precoFixo() {
        CartItem r = apply(p1("3", "10.00"), promo("preco_fixo", null, "8", null, null, null));
        assertEquals(new BigDecimal("6.00"), r.desconto());
    }

    @Test
    void leve3Pague2() {
        CartItem r = apply(p1("3", "5.00"), promo("leve_x_pague_y", null, null, "3", "2", null));
        assertEquals(new BigDecimal("5.00"), r.desconto());
        assertEquals(new BigDecimal("10.00"), r.total());
        CartItem menos = apply(p1("2", "5.00"), promo("leve_x_pague_y", null, null, "3", "2", null));
        assertEquals(0, menos.desconto().signum());
    }

    @Test
    void comboQuantidade() {
        CartItem r = apply(p1("4", "5.00"), promo("combo_quantidade", null, "8", null, null, "2"));
        // 2 grupos: 2*2*5=20 - 2*8=16 -> 4
        assertEquals(new BigDecimal("4.00"), r.desconto());
    }

    @Test
    void precoAtacado() {
        CartItem r = apply(p1("10", "5.00"), promo("preco_atacado", null, "4", null, null, "10"));
        assertEquals(new BigDecimal("10.00"), r.desconto());
        CartItem abaixo = apply(p1("9", "5.00"), promo("preco_atacado", null, "4", null, null, "10"));
        assertEquals(0, abaixo.desconto().signum());
    }

    @Test
    void vigenciaEAtiva() {
        Promotion expirada = new Promotion("x", "x", "desconto_percentual", new BigDecimal("50"), null, null, null,
                null, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z"), true, null,
                List.of("id-P1"));
        assertEquals(0, apply(p1("1", "10.00"), expirada).desconto().signum());
        Promotion inativa = new Promotion("y", "y", "desconto_percentual", new BigDecimal("50"), null, null, null,
                null, null, null, false, null, List.of("id-P1"));
        assertEquals(0, apply(p1("1", "10.00"), inativa).desconto().signum());
    }

    @Test
    void porCategoria() {
        Promotion cat = new Promotion("c", "Cat", "desconto_percentual", new BigDecimal("10"), null, null, null, null,
                null, null, true, "cat1", List.of());
        assertEquals(new BigDecimal("1.00"), apply(p1("1", "10.00"), cat).desconto());
    }

    @Test
    void melhorDescontoSemEmpilhar() {
        Promotion a = promo("desconto_percentual", "10", null, null, null, null);
        Promotion b = promo("desconto_valor", "3", null, null, null, null);
        CartItem r = apply(p1("1", "10.00"), a, b);
        assertEquals(new BigDecimal("3.00"), r.desconto());
        assertEquals("promo-desconto_valor", r.promocaoId());
    }
}
