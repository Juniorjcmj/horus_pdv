package br.com.quack.caixa.domain;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.dto.VendaPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Preço alterado com senha de gerente: vale o preço autorizado, sem promoção, e dá para restaurar. */
class CartPriceOverrideTest {

    private static final Product REFRI = new Product("p2", "2002", "Refrigerante Lata", "789", new BigDecimal("5.00"),
            new BigDecimal("100"), "UN", null, null, false, null, 15);

    private static Promotion leve3Pague2() {
        return new Promotion("promo1", "Leve 3 pague 2", "leve_x_pague_y", null, null, new BigDecimal("3"),
                new BigDecimal("2"), null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"), true, null,
                List.of("p2"));
    }

    @Test
    void precoAutorizadoNaoRecebePromocaoERestaura() {
        Cart cart = new Cart(() -> Instant.parse("2026-10-31T12:00:00Z"));
        cart.setPromotions(List.of(leve3Pague2()));
        cart.add(REFRI, new BigDecimal("3"));
        assertEquals(0, new BigDecimal("10.00").compareTo(cart.total()), "promoção normal: 3 por 10");

        cart.applyAuthorizedPrice(0, new BigDecimal("4.00"), "aut-1", "Gerente Bia");
        CartItem it = cart.pricedItems().get(0);
        assertTrue(it.priceOverridden());
        assertEquals(0, new BigDecimal("4.00").compareTo(it.unitPrice()));
        assertEquals(0, BigDecimal.ZERO.compareTo(it.desconto()), "item com preço alterado não recebe promoção");
        assertNull(it.promocaoId());
        assertEquals(0, new BigDecimal("12.00").compareTo(cart.total()));
        assertEquals("Gerente Bia", it.precoAutorizadoPor());
        assertEquals(0, new BigDecimal("5.00").compareTo(it.precoCadastro()));

        // Somar mais unidades mantém o preço autorizado.
        cart.add(REFRI, BigDecimal.ONE);
        assertEquals(0, new BigDecimal("16.00").compareTo(cart.total()));

        // O payload leva a autorização no item.
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.PIX, null, null, null);
        VendaPayload p = SalePayloadFactory.create(cart.pricedItems(), plan, null, null, "Ana", "op1", "dev-1",
                Instant.parse("2026-10-31T12:00:00Z"));
        assertEquals("aut-1", p.items().get(0).autorizacaoPrecoId());

        cart.restoreCatalogPrice(0);
        CartItem back = cart.pricedItems().get(0);
        assertFalse(back.priceOverridden());
        assertEquals(0, new BigDecimal("5.00").compareTo(back.unitPrice()));
        assertEquals("promo1", back.promocaoId(), "leve 3 pague 2 volta a valer");
    }
}
