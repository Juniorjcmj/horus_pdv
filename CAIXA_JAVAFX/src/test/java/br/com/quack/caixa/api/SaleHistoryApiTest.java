package br.com.quack.caixa.api;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.dto.VendaPayload;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cupom de venda vinda do servidor: recebido/troco inconsistentes não podem ir para o papel. */
class SaleHistoryApiTest {

    private static SaleHistoryApi.SaleDetail venda(SaleHistoryApi.Payment p) {
        return new SaleHistoryApi.SaleDetail("v1", "25", "finalizada", "Consumidor", "-", "dinheiro", "18,90",
                "Ana", "31/10/2026 08:37:06",
                List.of(new SaleHistoryApi.Item("1001", "Cafe", BigDecimal.ONE, "18,90", BigDecimal.ZERO, "18,90", null)),
                List.of(p));
    }

    @Test
    void vendaCorrigidaAntigaSaiComRecebidoIgualAoValor() {
        // Gravado pelo servidor antigo: valor 18,90, recebido proporcional 23,72 e troco 0 (não fecha).
        VendaPayload.Payment p = SaleHistoryApi.toOutcome(venda(new SaleHistoryApi.Payment("dinheiro",
                new BigDecimal("18.90"), new BigDecimal("23.72"), BigDecimal.ZERO)), false).payload().payments().get(0);
        assertEquals(0, new BigDecimal("18.90").compareTo(p.cashGiven()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.changeAmount()));
    }

    @Test
    void vendaNormalMantemRecebidoETroco() {
        VendaPayload.Payment p = SaleHistoryApi.toOutcome(venda(new SaleHistoryApi.Payment("dinheiro",
                new BigDecimal("18.90"), new BigDecimal("50.00"), new BigDecimal("31.10"))), false).payload()
                .payments().get(0);
        assertEquals(0, new BigDecimal("50.00").compareTo(p.cashGiven()));
        assertEquals(0, new BigDecimal("31.10").compareTo(p.changeAmount()));
    }

    @Test
    void dataDoHistoricoViraIso() {
        assertEquals("2026-10-31T11:37:06Z", SaleHistoryApi.isoFromSaleDate("31/10/2026 08:37:06"));
    }
}
