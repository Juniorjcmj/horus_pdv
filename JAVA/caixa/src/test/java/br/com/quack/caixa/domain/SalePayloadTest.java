package br.com.quack.caixa.domain;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.dto.VendaPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalePayloadTest {
    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private VendaPayload sample(List<VendaPayload.Item> items, String total, List<VendaPayload.Payment> pays) {
        return new VendaPayload("c", "e", "SALE_CREATED", "2026-01-01T00:00:00Z", "OFF-X", null, false,
                "Consumidor", "-", "dinheiro", total, "Ana", "op1", items, pays);
    }

    private VendaPayload.Item item(String code, String q, String price, String desc, String total) {
        return new VendaPayload.Item(code, "Nome " + code, bd(q), bd(price), bd(desc), bd(total), null);
    }

    @Test
    void hashDeterministicoEOrdenadoPorCodigo() {
        VendaPayload.Payment pay = new VendaPayload.Payment("dinheiro", bd("30.00"), bd("30.00"), bd("0.00"));
        VendaPayload a = sample(List.of(item("B", "1", "10.00", "0.00", "10.00"),
                item("A", "2", "10.00", "0.00", "20.00")), "30,00", List.of(pay));
        VendaPayload b = sample(List.of(item("A", "2", "10.00", "0.00", "20.00"),
                item("B", "1", "10.00", "0.00", "10.00")), "30,00", List.of(pay));
        assertEquals(PayloadHash.compute(a), PayloadHash.compute(b));
        assertEquals(64, PayloadHash.compute(a).length());
        assertEquals(PayloadHash.compute(a), PayloadHash.compute(a));
    }

    @Test
    void numerosSerializamComoJs() {
        VendaPayload.Payment pay = new VendaPayload.Payment("dinheiro", bd("10.50"), bd("10.50"), bd("0.00"));
        VendaPayload p = sample(List.of(item("A", "1", "10.50", "0.00", "10.50")), "10,50", List.of(pay));
        String json = PayloadHash.canonicalJson(p);
        assertTrue(json.contains("\"totalAmount\":10.5,"), json);
        assertTrue(json.contains("\"unitPrice\":10.5,"), json);
        assertTrue(json.contains("\"desconto\":0,"), json);
        assertTrue(json.contains("\"quantity\":1,"), json);
        assertTrue(json.contains("\"promocaoId\":\"\""), json);
    }

    @Test
    void validatorRecusaSomaDivergente() {
        VendaPayload.Payment pay = new VendaPayload.Payment("dinheiro", bd("30.00"), bd("30.00"), bd("0.00"));
        VendaPayload ok = sample(List.of(item("A", "3", "10.00", "0.00", "30.00")), "30,00", List.of(pay));
        SaleValidator.validate(ok);

        VendaPayload itensDiv = sample(List.of(item("A", "3", "10.00", "0.00", "30.00")), "31,00", List.of(pay));
        assertThrows(DomainException.class, () -> SaleValidator.validate(itensDiv));

        VendaPayload.Payment curta = new VendaPayload.Payment("pix", bd("20.00"), bd("20.00"), bd("0.00"));
        VendaPayload pagDiv = sample(List.of(item("A", "3", "10.00", "0.00", "30.00")), "30,00", List.of(curta));
        assertThrows(DomainException.class, () -> SaleValidator.validate(pagDiv));

        VendaPayload.Payment trocoPix = new VendaPayload.Payment("pix", bd("30.00"), bd("35.00"), bd("5.00"));
        VendaPayload trocoErr = sample(List.of(item("A", "3", "10.00", "0.00", "30.00")), "30,00", List.of(trocoPix));
        assertThrows(DomainException.class, () -> SaleValidator.validate(trocoErr));
    }

    @Test
    void factoryGeraPayloadValidoComHash() {
        Cart cart = new Cart();
        cart.add(new Product("id1", "A1", "Cafe", "", bd("7.30"), bd("10"), "UN", null, null, false, null, 15),
                bd("3"));
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.DINHEIRO, null, bd("50"), null);
        VendaPayload p = SalePayloadFactory.create(cart.pricedItems(), plan, null, null, "Ana", "op1",
                "abcd1234-0000", Instant.parse("2026-06-01T12:00:00Z"));
        SaleValidator.validate(p);
        assertEquals(PayloadHash.compute(p.withHash(null)), p.payloadHash());
        assertEquals("Consumidor", p.customerName());
        assertEquals("-", p.customerCpf());
        assertTrue(p.offlineReference().startsWith("OFF-ABCD-"));
        assertEquals("21,90", p.totalAmount());
    }
}
