package br.com.quack.pdv.estoque.registrador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("it")
class RegistradorEstoqueIT {

    static {
        BancoIT.criarBanco("cadastros");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("cadastros"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired RegistradorEstoque registrador;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("cadastros"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    @Test
    void aplicarComIdGravaOIdDadoEOAnteriorENovoDaLoja() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> registrador.aplicarComId(
                "empresa-principal", "pr-ct-inv", new BigDecimal("4"), EstoqueTipos.ENTRADA, null,
                MovimentoOrigem.sistema("nfe", "n1", "Entrada da nuvem"), "loja", "mov-id-da-nuvem"));

        var m = jdbc.queryForMap("SELECT EstoqueAnterior, EstoqueNovo, Quantidade FROM EstoqueMovimentacoes WHERE Id = 'mov-id-da-nuvem'");
        assertThat((BigDecimal) m.get("EstoqueAnterior")).isEqualByComparingTo("10");
        assertThat((BigDecimal) m.get("EstoqueNovo")).isEqualByComparingTo("14");
        assertThat((BigDecimal) m.get("Quantidade")).isEqualByComparingTo("4");
    }

    @Test
    void exigeTransacaoAbertaMandatory() {
        assertThatThrownBy(() -> registrador.aplicar(
                        "empresa-principal",
                        "pr-ct-inv",
                        BigDecimal.ONE,
                        EstoqueTipos.ENTRADA,
                        null,
                        MovimentoOrigem.sistema("teste", "1", "Sem tx")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void duasThreadsTirandoUmDeSaldoDezGeramSaldoOitoEMovimentacoesEncadeadas() throws Exception {
        // pr-ct-inv comeca no seed com ProductQnt = 10.0000
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch prontas = new CountDownLatch(2);
        CountDownLatch largada = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Runnable tarefa = () -> {
                prontas.countDown();
                try {
                    largada.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                tx.executeWithoutResult(s -> registrador.aplicar(
                        "empresa-principal",
                        "pr-ct-inv",
                        new BigDecimal("-1"),
                        EstoqueTipos.SAIDA,
                        null,
                        new MovimentoOrigem("ajuste", "conc", "Saida concorrente", "usr-ct-admin", "Admin Contrato")));
            };

            Future<?> f1 = pool.submit(tarefa);
            Future<?> f2 = pool.submit(tarefa);
            assertThat(prontas.await(10, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            f1.get(15, TimeUnit.SECONDS);
            f2.get(15, TimeUnit.SECONDS);
        }

        BigDecimal saldoFinal = jdbc.queryForObject(
                "SELECT ProductQnt FROM Produtos WHERE Id='pr-ct-inv'", BigDecimal.class);
        assertThat(saldoFinal).isEqualByComparingTo("8");

        List<String> pares = jdbc.query(
                "SELECT EstoqueAnterior, EstoqueNovo FROM EstoqueMovimentacoes WHERE ProdutoId='pr-ct-inv' ORDER BY EstoqueAnterior DESC",
                (rs, i) -> rs.getBigDecimal(1).stripTrailingZeros().toPlainString()
                        + "->" + rs.getBigDecimal(2).stripTrailingZeros().toPlainString());
        assertThat(pares).containsExactly("10->9", "9->8");
    }

    @Test
    void falhaDepoisDoRegistradorDesfazSaldoEMovimentacao() {
        TransactionTemplate tx = new TransactionTemplate(txManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    registrador.aplicarPorCodigo(
                            "empresa-principal",
                            "CT007",
                            new BigDecimal("5"),
                            EstoqueTipos.ENTRADA,
                            new BigDecimal("4.50"),
                            MovimentoOrigem.sistema("teste", "rb-1", "Deve sofrer rollback"));
                    throw new IllegalStateException("falha posterior forcada");
                }))
                .hasMessage("falha posterior forcada");

        BigDecimal saldo = jdbc.queryForObject(
                "SELECT ProductQnt FROM Produtos WHERE Id='pr-ct-inv'", BigDecimal.class);
        Integer movs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE ProdutoId='pr-ct-inv'", Integer.class);
        assertThat(saldo).isEqualByComparingTo("10");
        assertThat(movs).isZero();
    }

    @Test
    void deltaZeroNaoGravaMovimentacaoENaoVazaNocount() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        var saldos = tx.execute(s -> registrador.aplicar(
                "empresa-principal",
                "pr-ct-inv",
                BigDecimal.ZERO,
                EstoqueTipos.ENTRADA,
                null,
                MovimentoOrigem.sistema("cadastro", "pr-ct-inv", "Sem variacao")));

        assertThat(saldos).isPresent();
        assertThat(saldos.get().anterior()).isEqualByComparingTo("10");
        assertThat(saldos.get().novo()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE ProdutoId='pr-ct-inv'", Integer.class))
                .isZero();

        int linhasAfetadas = jdbc.update(
                "UPDATE Produtos SET ProductName = ProductName WHERE Id = 'pr-ct-inv'");
        assertThat(linhasAfetadas).isEqualTo(1);
    }
}
