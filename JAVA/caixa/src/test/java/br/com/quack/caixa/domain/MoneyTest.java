package br.com.quack.caixa.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void parseBrFormatos() {
        assertEquals(0, new BigDecimal("1234.56").compareTo(Money.parseBr("1.234,56")));
        assertEquals(0, new BigDecimal("12.9").compareTo(Money.parseBr("12.9")));
        assertEquals(0, new BigDecimal("5").compareTo(Money.parseBr("R$ 5,00")));
        assertThrows(DomainException.class, () -> Money.parseBr("abc"));
    }

    @Test
    void format() {
        assertEquals("1.234,56", Money.formatBr(new BigDecimal("1234.56")));
        assertEquals("0,50", Money.formatBr(new BigDecimal("0.5")));
        assertEquals("0,452", Money.formatQty(new BigDecimal("0.4520")));
        assertEquals("3", Money.formatQty(new BigDecimal("3.0000")));
    }

    @Test
    void productMoneyArredondaUmaVez() {
        assertEquals(new BigDecimal("5.83"), Money.productMoney(new BigDecimal("0.452"), new BigDecimal("12.90")));
    }

    @Test
    void itemNet() {
        assertEquals(new BigDecimal("8.00"),
                Money.itemNet(new BigDecimal("2"), new BigDecimal("5.00"), new BigDecimal("2.00")));
        assertThrows(DomainException.class,
                () -> Money.itemNet(new BigDecimal("1"), new BigDecimal("5.00"), new BigDecimal("6.00")));
    }

    @Test
    void jsNumber() {
        assertEquals("10.5", Money.jsNumber(new BigDecimal("10.50")));
        assertEquals("10", Money.jsNumber(new BigDecimal("10.00")));
        assertEquals("100", Money.jsNumber(new BigDecimal("1E+2")));
    }
}
