package br.com.quack.pdv.venda.registro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.venda.registro.app.SaleMoney;
import br.com.quack.pdv.venda.registro.domain.PagamentoNormalizado;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SaleMoneyTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static SaleMoney.Entrada pag(String tipo, String valor, String recebido, String troco) {
        return new SaleMoney.Entrada(tipo, d(valor), recebido == null ? null : d(recebido),
                troco == null ? null : d(troco));
    }

    @Test
    void money_arredondaPontoMedioParaLongeDoZero() {
        assertThat(SaleMoney.money(d("2.345"))).isEqualByComparingTo("2.35");
        assertThat(SaleMoney.money(d("-2.345"))).isEqualByComparingTo("-2.35");
        assertThat(SaleMoney.money(d("2.344"))).isEqualByComparingTo("2.34");
    }

    @Test
    void n2_usaCulturaInvariante() {
        assertThat(SaleMoney.n2(d("1234.5"))).isEqualTo("1,234.50");
        assertThat(SaleMoney.n2(d("0"))).isEqualTo("0.00");
    }

    @Test
    void itemNet_calculaBrutoMenosDesconto() {
        assertThat(SaleMoney.itemNet(d("2.5"), d("3.333"), d("1.005"))).isEqualByComparingTo("7.32");
    }

    @Test
    void itemNet_recusaValoresInvalidosEDescontoMaiorQueOBruto() {
        assertThatThrownBy(() -> SaleMoney.itemNet(d("0"), d("3"), d("0")))
                .hasMessage("Quantidade, preço ou desconto inválido na venda.");
        assertThatThrownBy(() -> SaleMoney.itemNet(d("1"), d("3"), d("3.01")))
                .hasMessage("Desconto superior ao bruto do item.");
    }

    @Test
    void payments_semPagamentosCriaUmaParcelaDoTotal() {
        List<PagamentoNormalizado> r = SaleMoney.payments(d("10"), "pix", List.of());
        assertThat(r).hasSize(1);
        assertThat(r.getFirst().paymentType()).isEqualTo("pix");
        assertThat(r.getFirst().amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void payments_tipoVazioOuTracoViraDinheiro() {
        assertThat(SaleMoney.payments(d("5"), "-", List.of()).getFirst().paymentType()).isEqualTo("dinheiro");
        assertThat(SaleMoney.payments(d("5"), "x", List.of(pag(" ", "5", null, null))).getFirst().paymentType())
                .isEqualTo("dinheiro");
    }

    @Test
    void payments_trocoSomenteSobreExcedenteEmDinheiro() {
        assertThat(SaleMoney.payments(d("6"), "dinheiro", List.of(pag("dinheiro", "6", "10", "4")))).hasSize(1);
        assertThatThrownBy(() -> SaleMoney.payments(d("6"), "pix", List.of(pag("pix", "6", null, "1"))))
                .hasMessage("Troco permitido somente em dinheiro.");
        assertThatThrownBy(() -> SaleMoney.payments(d("6"), "dinheiro", List.of(pag("dinheiro", "6", "10", "3"))))
                .hasMessage("Recebido em dinheiro deve ser igual à parcela mais troco.");
        assertThatThrownBy(() -> SaleMoney.payments(d("6"), "dinheiro", List.of(pag("dinheiro", "6", "10", null))))
                .hasMessage("Recebido em dinheiro deve ser igual à parcela mais troco.");
    }

    @Test
    void payments_somaDiferenteUsaFormatoInvariante() {
        assertThatThrownBy(() -> SaleMoney.payments(d("1234.5"), "dinheiro",
                List.of(pag("dinheiro", "10", null, null))))
                .hasMessage("Soma dos pagamentos difere do total R$ 1,234.50.");
        assertThatThrownBy(() -> SaleMoney.payments(d("0"), "dinheiro", List.of()))
                .hasMessage("Total da venda deve ser maior que zero.");
        assertThatThrownBy(() -> SaleMoney.payments(d("5"), "dinheiro", List.of(pag("dinheiro", "0", null, null))))
                .hasMessage("Pagamento, recebido ou troco inválido.");
    }

    @Test
    void fiado_somaSoAsParcelasDeFiadoSemDiferenciarCaixa() {
        List<PagamentoNormalizado> r = SaleMoney.payments(d("10"), "x",
                List.of(pag("Fiado", "4", null, null), pag("pix", "6", null, null)));
        assertThat(SaleMoney.fiado(r)).isEqualByComparingTo("4");
    }

    @Test
    void credit_recusaSemLimiteOuAcimaDoDisponivel() {
        assertThatThrownBy(() -> SaleMoney.credit(d("5"), d("0"), d("6")))
                .hasMessage("Crédito insuficiente. Disponível: R$ 5.00, solicitado em fiado: R$ 6.00, "
                        + "insuficiência: R$ 1.00. Cliente sem limite não pode comprar fiado.");
        assertThatThrownBy(() -> SaleMoney.credit(d("0"), d("0"), d("1"))).hasMessageContaining("Disponível: R$ 0.00");
        SaleMoney.credit(d("100"), d("40"), d("60"));
    }
}
