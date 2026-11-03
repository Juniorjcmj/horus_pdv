package br.com.quack.estoque.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ConversaoUnidadeTest {

    @Test
    void caixaComDoze() {
        // 5 CX x 12 = 60 UN; caixa a R$ 50,00 -> R$ 4,1667 por unidade
        BigDecimal qtd = ConversaoUnidade.quantidadeEstoque(new BigDecimal("5"), new BigDecimal("12"));
        assertEquals(0, new BigDecimal("60").compareTo(qtd));
        assertEquals(new BigDecimal("4.1667"), ConversaoUnidade.custoUnitario(new BigDecimal("50.00"), new BigDecimal("12")));
        assertEquals(new BigDecimal("250.00"), ConversaoUnidade.totalLinha(new BigDecimal("5"), new BigDecimal("50.00")));
    }

    @Test
    void fatorUmNaoMudaNada() {
        assertEquals(0, new BigDecimal("3").compareTo(ConversaoUnidade.quantidadeEstoque(new BigDecimal("3"), BigDecimal.ONE)));
        assertEquals(new BigDecimal("2.5000"), ConversaoUnidade.custoUnitario(new BigDecimal("2.50"), BigDecimal.ONE));
    }

    @Test
    void fracaoDeKg() {
        assertEquals(0, new BigDecimal("12.5").compareTo(
                ConversaoUnidade.quantidadeEstoque(new BigDecimal("2.5"), new BigDecimal("5"))));
    }

    @Test
    void fatorInvalidoRecusado() {
        assertThrows(DomainException.class, () -> ConversaoUnidade.quantidadeEstoque(BigDecimal.ONE, BigDecimal.ZERO));
        assertThrows(DomainException.class, () -> ConversaoUnidade.custoUnitario(BigDecimal.ONE, new BigDecimal("-2")));
        assertThrows(DomainException.class, () -> ConversaoUnidade.custoUnitario(BigDecimal.ONE, null));
    }
}
