package br.com.quack.caixa.domain;

import static br.com.quack.caixa.domain.TestData.customer;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentPlanTest {
    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void pagamentoRapidoComTroco() {
        PaymentPlan plan = new PaymentPlan(bd("37.50"));
        plan.finalizeWith(PaymentType.DINHEIRO, bd("50"), bd("50"), null);
        assertEquals(1, plan.parts().size());
        assertEquals(bd("37.50"), plan.parts().get(0).amount());
        assertEquals(bd("12.50"), plan.parts().get(0).change());
        assertEquals("dinheiro", plan.headerPaymentType());
    }

    @Test
    void parcelaAcimaDoSaldo() {
        PaymentPlan plan = new PaymentPlan(bd("30.00"));
        plan.addPart(PaymentType.PIX, bd("10"), null, null);
        DomainException e = assertThrows(DomainException.class,
                () -> plan.addPart(PaymentType.PIX, bd("25"), null, null));
        assertTrue(e.getMessage().contains("20,00"));
    }

    @Test
    void trocoSoEmDinheiro() {
        PaymentPlan plan = new PaymentPlan(bd("30.00"));
        PaymentPart pix = plan.addPart(PaymentType.PIX, bd("10"), bd("100"), null);
        assertEquals(0, pix.change().signum());
        assertEquals(bd("10.00"), pix.cashGiven());
        assertThrows(DomainException.class, () -> plan.addPart(PaymentType.DINHEIRO, bd("10"), bd("5"), null));
    }

    @Test
    void fiadoSemCliente() {
        PaymentPlan plan = new PaymentPlan(bd("30.00"));
        assertThrows(DomainException.class, () -> plan.addPart(PaymentType.FIADO, bd("30"), null, null));
    }

    @Test
    void fiadoAcimaDoLimite() {
        PaymentPlan plan = new PaymentPlan(bd("30.00"));
        DomainException e = assertThrows(DomainException.class,
                () -> plan.addPart(PaymentType.FIADO, bd("30"), null, customer("100", "80")));
        assertTrue(e.getMessage().startsWith("Limite de crédito insuficiente para Maria!"));
        assertTrue(e.getMessage().contains("Disponível: R$ 20,00"));
        plan.addPart(PaymentType.FIADO, bd("20"), null, customer("100", "80"));
    }

    @Test
    void faltandoValor() {
        PaymentPlan plan = new PaymentPlan(bd("30.00"));
        plan.addPart(PaymentType.PIX, bd("10"), null, null);
        DomainException e = assertThrows(DomainException.class,
                () -> plan.finalizeWith(PaymentType.DINHEIRO, bd("5"), bd("5"), null));
        assertEquals("Faltam R$ 20,00 para liquidar a venda.", e.getMessage());
    }

    @Test
    void multiplo() {
        PaymentPlan plan = new PaymentPlan(bd("30.00"));
        plan.addPart(PaymentType.PIX, bd("10"), null, null);
        plan.finalizeWith(PaymentType.DINHEIRO, bd("20"), bd("20"), null);
        assertEquals("Múltiplo", plan.headerPaymentType());
        assertEquals(0, plan.remaining().signum());
    }
}
