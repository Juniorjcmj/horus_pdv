package br.com.quack.pdv.produto.lote.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Testes de mesa do LoteEstimador (distribuicao FEFO e saldo real) e das faixas do LoteService. */
class LoteEstimadorTest {

    private static LoteLinha lote(String id, String validade, String inicial, String atual, String estoque) {
        return new LoteLinha(id, "p1", "L" + id, LocalDate.parse(validade), new BigDecimal(inicial),
                new BigDecimal(atual), "manual", false, OffsetDateTime.parse("2026-01-15T09:00:00-03:00"),
                "C1", "Produto", new BigDecimal(estoque), BigDecimal.ONE, BigDecimal.TEN, 0, "", "", "", null);
    }

    @Test
    void distribuiOEstoqueNosLotesDeValidadeMaisDistanteEPrimeiro() {
        List<LoteLinha> lotes = List.of(
                lote("a", "2026-11-01", "3", "3", "8"),
                lote("b", "2026-12-01", "4", "4", "8"),
                lote("c", "2027-06-01", "5", "5", "8"));
        Map<String, BigDecimal> saldos = LoteEstimador.distribuir(lotes);
        assertThat(saldos.get("c")).isEqualByComparingTo("5");
        assertThat(saldos.get("b")).isEqualByComparingTo("3");
        assertThat(saldos.get("a")).isEqualByComparingTo("0");
    }

    @Test
    void estoqueNegativoOuZeroNaoAlocaNada() {
        List<LoteLinha> lotes = List.of(lote("a", "2026-11-01", "3", "3", "-2"));
        assertThat(LoteEstimador.distribuir(lotes).get("a")).isEqualByComparingTo("0");
    }

    @Test
    void estoqueMaiorQueATotalDosLotesLimitaPelaQuantidadeInicial() {
        List<LoteLinha> lotes = List.of(lote("a", "2026-11-01", "3", "3", "50"), lote("b", "2026-12-01", "2", "2", "50"));
        Map<String, BigDecimal> saldos = LoteEstimador.distribuir(lotes);
        assertThat(saldos.get("a")).isEqualByComparingTo("3");
        assertThat(saldos.get("b")).isEqualByComparingTo("2");
    }

    @Test
    void mesmaValidadeDesempataPeloMaisNovoPrimeiro() {
        LoteLinha velho = lote("v", "2026-12-01", "5", "5", "5");
        LoteLinha novo = new LoteLinha("n", "p1", "Ln", LocalDate.parse("2026-12-01"), new BigDecimal("5"),
                new BigDecimal("5"), "manual", false, OffsetDateTime.parse("2026-02-01T09:00:00-03:00"),
                "C1", "Produto", new BigDecimal("5"), BigDecimal.ONE, BigDecimal.TEN, 0, "", "", "", null);
        Map<String, BigDecimal> saldos = LoteEstimador.distribuir(List.of(velho, novo));
        assertThat(saldos.get("n")).isEqualByComparingTo("5");
        assertThat(saldos.get("v")).isEqualByComparingTo("0");
    }

    @Test
    void modoAtivoUsaOSaldoRealSemNegativos() {
        List<LoteLinha> lotes = List.of(lote("a", "2026-11-01", "3", "1.5", "8"), lote("b", "2026-12-01", "4", "-1", "8"));
        Map<String, BigDecimal> saldos = LoteEstimador.saldos(lotes, true);
        assertThat(saldos.get("a")).isEqualByComparingTo("1.5");
        assertThat(saldos.get("b")).isEqualByComparingTo("0");
    }

    @Test
    void modoNaoAtivoUsaOEstimado() {
        List<LoteLinha> lotes = List.of(lote("a", "2026-11-01", "3", "0", "2"));
        assertThat(LoteEstimador.saldos(lotes, false).get("a")).isEqualByComparingTo("2");
    }

    @Test
    void faixasDeAlerta() {
        BigDecimal um = BigDecimal.ONE;
        assertThat(LoteService.faixa(BigDecimal.ZERO, 3, 15)).isEqualTo("esgotado");
        assertThat(LoteService.faixa(um, -1, 15)).isEqualTo("vencido");
        assertThat(LoteService.faixa(um, 16, 15)).isEqualTo("ok");
        assertThat(LoteService.faixa(um, 15, 15)).isEqualTo("atencao");
        assertThat(LoteService.faixa(um, 5, 15)).isEqualTo("critico");
        assertThat(LoteService.faixa(um, 6, 15)).isEqualTo("atencao");
        assertThat(LoteService.faixa(um, 2, 3)).isEqualTo("critico");
        assertThat(LoteService.faixa(um, 0, 0)).isEqualTo("critico");
    }
}
