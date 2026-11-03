package br.com.quack.caixa.domain;

import static br.com.quack.caixa.domain.TestData.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CartTest {
    private final Cart cart = new Cart(() -> Instant.parse("2026-06-01T12:00:00Z"));

    @Test
    void somaQuantidadeDoMesmoProduto() {
        Product p = product("P1", "5.00", "10", "UN");
        cart.add(p, BigDecimal.ONE);
        cart.add(p, new BigDecimal("2"));
        assertEquals(1, cart.pricedItems().size());
        assertEquals(0, new BigDecimal("3").compareTo(cart.pricedItems().get(0).quantity()));
        assertEquals(new BigDecimal("15.00"), cart.total());
    }

    @Test
    void estoqueInsuficiente() {
        Product p = product("P1", "5.00", "2", "UN");
        cart.add(p, new BigDecimal("2"));
        DomainException e = assertThrows(DomainException.class, () -> cart.add(p, BigDecimal.ONE));
        assertTrue(e.getMessage().startsWith("Estoque insuficiente"));
    }

    @Test
    void fracaoEmUnRecusada() {
        Product p = product("P1", "5.00", "10", "UN");
        assertThrows(DomainException.class, () -> cart.add(p, new BigDecimal("1.5")));
    }

    @Test
    void fracaoEmKgAceita() {
        Product p = product("P2", "12.90", "10", "KG");
        cart.add(p, new BigDecimal("0.452"));
        assertEquals(new BigDecimal("5.83"), cart.total());
    }

    @Test
    void totalComPromocao() {
        Product p = product("P1", "5.00", "10", "UN");
        cart.setPromotions(List.of(promo("leve_x_pague_y", null, null, "3", "2", null)));
        cart.add(p, new BigDecimal("3"));
        assertEquals(new BigDecimal("10.00"), cart.total());
        assertEquals(new BigDecimal("5.00"), cart.pricedItems().get(0).desconto());
    }
}
