package br.com.quack.pdv.produto.lote;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Consumo FEFO nos lotes do seed de contrato (pr-ct-val: L-VENC 3, L-ALERTA 4, L-FUTURO 5). */
@SpringBootTest
@ActiveProfiles("it")
class LoteOperacoesIT {

    static {
        BancoIT.criarBanco("lotes");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("lotes"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired LoteOperacoes lotes;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("lotes"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    private BigDecimal saldo(String lote) {
        return jdbc.queryForObject("SELECT QtdAtual FROM ProdutoLotes WHERE Id = ?", BigDecimal.class, lote);
    }

    @Test
    void consumoAtravessaOsLotesPelaValidadeMaisProximaEGravaOsConsumos() {
        Boolean ok = tx.execute(s ->
                lotes.consumirNosLotes("empresa-principal", "pr-ct-val", new BigDecimal("5"), "ajuste", "aj-it-1"));

        assertThat(ok).isTrue();
        assertThat(saldo("lote-ct-vencido")).isEqualByComparingTo("0");
        assertThat(saldo("lote-ct-alerta")).isEqualByComparingTo("2");
        assertThat(saldo("lote-ct-ok")).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM LoteConsumos WHERE RefId = 'aj-it-1'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void consumoRepetidoParaAMesmaOrigemENoOp() {
        tx.execute(s -> lotes.consumirNosLotes("empresa-principal", "pr-ct-val", BigDecimal.ONE, "ajuste", "aj-it-2"));
        Boolean segundo = tx.execute(s ->
                lotes.consumirNosLotes("empresa-principal", "pr-ct-val", BigDecimal.ONE, "ajuste", "aj-it-2"));

        assertThat(segundo).isFalse();
        assertThat(saldo("lote-ct-vencido")).isEqualByComparingTo("2");
    }

    @Test
    void quantidadeAlemDosLotesGravaOExcedenteSemLote() {
        tx.execute(s -> lotes.consumirNosLotes("empresa-principal", "pr-ct-val", new BigDecimal("20"), "ajuste", "aj-it-3"));

        assertThat(jdbc.queryForObject(
                        "SELECT Quantidade FROM LoteConsumos WHERE RefId = 'aj-it-3' AND LoteId IS NULL",
                        BigDecimal.class))
                .isEqualByComparingTo("8");
    }

    @Test
    void produtoSemLoteNaoConsomeNada() {
        Boolean ok = tx.execute(s ->
                lotes.consumirNosLotes("empresa-principal", "pr-sem-lote-inexistente", BigDecimal.ONE, "ajuste", "aj-it-4"));
        assertThat(ok).isFalse();
    }
}
