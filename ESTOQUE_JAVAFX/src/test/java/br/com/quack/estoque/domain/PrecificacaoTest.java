package br.com.quack.estoque.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PrecificacaoTest {
    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static void igual(String esperado, BigDecimal valor) {
        assertEquals(0, d(esperado).compareTo(valor), "esperado " + esperado + ", veio " + valor);
    }

    @Test
    void derivadosDoPreco() {
        // custo 3, preço 5
        igual("2.00", Precificacao.lucro(d("3"), d("5")));
        igual("40.00", Precificacao.margem(d("3"), d("5")));
        igual("66.67", Precificacao.markup(d("3"), d("5")));
        assertNull(Precificacao.markup(BigDecimal.ZERO, d("5")), "sem custo não há markup");
        assertNull(Precificacao.margem(d("3"), BigDecimal.ZERO));
    }

    @Test
    void precoPorCadaUm() {
        igual("5.00", Precificacao.precoPorLucro(d("3"), d("2")));
        igual("5.00", Precificacao.precoPorMargem(d("3"), d("40")));
        igual("5.00", Precificacao.precoPorMarkup(d("3"), d("66.6667")));
        igual("4.50", Precificacao.precoPorMarkup(d("3"), d("50")));
        // ida e volta: markup 50 -> preço 4,50 -> margem 33,33 e lucro 1,50
        igual("33.33", Precificacao.margem(d("3"), d("4.50")));
        igual("1.50", Precificacao.lucro(d("3"), d("4.50")));
    }

    @Test
    void recusaContasInvalidas() {
        assertThrows(DomainException.class, () -> Precificacao.precoPorMargem(d("3"), d("100")));
        assertThrows(DomainException.class, () -> Precificacao.precoPorLucro(d("3"), d("-3")));
        assertThrows(DomainException.class, () -> Precificacao.precoPorMarkup(BigDecimal.ZERO, d("50")));
        assertThrows(DomainException.class, () -> Precificacao.precoPorMarkup(d("3"), d("-100")));
    }
}
