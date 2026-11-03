package br.com.quack.pdv.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DinheiroTest {

    @Test
    void formataMoedaPtBrComESemSimbolo() {
        assertThat(Dinheiro.formatar(new BigDecimal("1234.5"))).isEqualTo("R$ 1.234,50");
        assertThat(Dinheiro.formatarSemSimbolo(new BigDecimal("1234.5"))).isEqualTo("1.234,50");
        assertThat(Dinheiro.formatarSemSimbolo(new BigDecimal("28.125"))).isEqualTo("28,13");
        assertThat(Dinheiro.formatarSemSimbolo(new BigDecimal("-0.001"))).isEqualTo("0,00");
    }

    @Test
    void formataPrecoUnitarioEQuantidadeEInvariante() {
        assertThat(Dinheiro.formatarPrecoUnitario(new BigDecimal("3.4567"))).isEqualTo("3,4567");
        assertThat(Dinheiro.formatarPrecoUnitario(new BigDecimal("3.4"))).isEqualTo("3,40");
        assertThat(Dinheiro.formatarQuantidade(new BigDecimal("10.0000"))).isEqualTo("10");
        assertThat(Dinheiro.formatarQuantidade(new BigDecimal("0.4520"))).isEqualTo("0,452");
        assertThat(Dinheiro.formatarInvarianteAte4Casas(new BigDecimal("28.1250"))).isEqualTo("28.125");
        assertThat(Dinheiro.formatarInvarianteAte4Casas(BigDecimal.ZERO)).isEqualTo("0");
    }

    @Test
    void leStringsPtBrEInvariantesERecusaNotacaoCientifica() {
        assertThat(Dinheiro.ler("R$ 1.234,56")).isEqualByComparingTo("1234.56");
        assertThat(Dinheiro.ler("28.125")).isEqualByComparingTo("28.125");
        assertThat(Dinheiro.ler("   ")).isEqualByComparingTo("0");
        assertThat(Dinheiro.tentarLer("1e5")).isEmpty();
        assertThat(Dinheiro.tentarLer("-")).isEmpty();
        assertThatThrownBy(() -> Dinheiro.lerEstrito("abc", "precoVenda"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("precoVenda");
    }
}
