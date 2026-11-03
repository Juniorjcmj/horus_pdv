package br.com.quack.pdv.produto.lote;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes;
import br.com.quack.pdv.produto.lote.app.LoteOperacoes.ItemVenda;
import br.com.quack.pdv.produto.lote.app.RetentativaBaixaLotes;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tarefas 1.4 e 1.5: origem provisoria vinculada ao numero da venda, baixa FEFO e estorno por lote da venda
 * (REQUIRES_NEW, sem excecao) e nova tentativa da baixa que falhou (D13). Seed: pr-ct-val (12 un.) com os lotes
 * lote-ct-vencido 3, lote-ct-alerta 4 e lote-ct-ok 5.
 */
@SpringBootTest
@ActiveProfiles("it")
class BaixaLotesDeVendaIT {

    private static final String EMPRESA = "empresa-principal";
    private static final String PRODUTO = "pr-ct-val";

    static {
        BancoIT.criarBanco("baixalotes");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("baixalotes"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
        r.add("quack.lote.retentativa.ativa", () -> "false"); // o teste dirige a rotina
        r.add("quack.lote.retentativa.max-tentativas", () -> "3");
        r.add("quack.lote.retentativa.base-segundos", () -> "1");
    }

    @Autowired LoteOperacoes lotes;
    @Autowired RegistradorEstoque registrador;
    @Autowired RetentativaBaixaLotes retentativa;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private String codigo;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("baixalotes"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
        jdbc.update("DELETE FROM LoteBaixaPendencias");
        codigo = jdbc.queryForObject("SELECT ProductCode FROM Produtos WHERE Id = ?", String.class, PRODUTO);
    }

    @AfterEach
    void limpar() {
        jdbc.execute("DROP TRIGGER IF EXISTS dbo.trg_falha_consumo");
        jdbc.execute("DROP TRIGGER IF EXISTS dbo.trg_falha_estorno");
    }

    private BigDecimal saldoLote(String lote) {
        return jdbc.queryForObject("SELECT QtdAtual FROM ProdutoLotes WHERE Id = ?", BigDecimal.class, lote);
    }

    private BigDecimal saldoProduto() {
        return jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = ?", BigDecimal.class, PRODUTO);
    }

    private int consumos(String venda) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM LoteConsumos WHERE RefId = ? AND Origem = N'venda'", Integer.class, venda);
    }

    private String estadoPendencia(String venda) {
        return jdbc.queryForObject("SELECT Estado FROM LoteBaixaPendencias WHERE VendaId = ?", String.class, venda);
    }

    private void vencerPendencias() {
        jdbc.update("UPDATE LoteBaixaPendencias SET ProximaTentativaEm = DATEADD(MINUTE, -1, SYSDATETIMEOFFSET())"
                + " WHERE Estado = N'pendente'");
    }

    // ------------------------------------------------------------------ 1.4: origem provisoria

    @Test
    void movimentoComOrigemProvisoriaFicaVinculadoAoNumeroDaVenda() {
        String provisoria = RegistradorEstoque.novaOrigemProvisoria();
        assertThat(provisoria).matches("pend-[0-9a-f]{32}");

        tx.executeWithoutResult(s -> {
            registrador.aplicar(EMPRESA, PRODUTO, new BigDecimal("-2"), EstoqueTipos.SAIDA_VENDA, null,
                    new MovimentoOrigem("venda", provisoria, "Venda", "usr-ct-admin", "Admin Contrato"));
            int vinculadas = registrador.vincularOrigem(EMPRESA, provisoria, "15043", "Venda #15043");
            assertThat(vinculadas).isEqualTo(1);
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE OrigemId = ?", Integer.class,
                provisoria)).isZero();
        assertThat(jdbc.queryForMap("SELECT OrigemId, Motivo, Tipo FROM EstoqueMovimentacoes"
                        + " WHERE OrigemId = N'15043' AND ProdutoId = ?", PRODUTO))
                .containsEntry("Motivo", "Venda #15043")
                .containsEntry("Tipo", EstoqueTipos.SAIDA_VENDA);
        assertThat(saldoProduto()).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ 1.4: FEFO e estorno

    @Test
    void consumoDaVendaAtravessaDoisLotesPelaValidadeMaisProxima() {
        lotes.consumirVendaSeguro(EMPRESA, "sale-it-1", List.of(new ItemVenda(codigo, new BigDecimal("5"))));

        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("0");
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("2");
        assertThat(saldoLote("lote-ct-ok")).isEqualByComparingTo("5");
        assertThat(consumos("sale-it-1")).isEqualTo(2);
    }

    @Test
    void itensDoMesmoProdutoSaoSomadosSemDiferencaDeCaixa() {
        lotes.consumirVendaSeguro(EMPRESA, "sale-it-2", List.of(
                new ItemVenda(codigo, new BigDecimal("1")), new ItemVenda(codigo.toLowerCase(), new BigDecimal("2"))));

        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("0");
        assertThat(consumos("sale-it-2")).isEqualTo(1);
    }

    @Test
    void estornoDevolveAosLotesDeOrigemEEIdempotente() {
        lotes.consumirVendaSeguro(EMPRESA, "sale-it-3", List.of(new ItemVenda(codigo, new BigDecimal("5"))));

        lotes.estornarVendaSeguro(EMPRESA, "sale-it-3");
        lotes.estornarVendaSeguro(EMPRESA, "sale-it-3");

        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("3");
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("4");
        assertThat(saldoLote("lote-ct-ok")).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM LoteConsumos WHERE RefId = N'sale-it-3'"
                + " AND Estornado = 0", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ 1.4 e 1.5: falha e nova tentativa

    @Test
    void falhaNoConsumoNaoDesfazAOperacaoPrincipalEANovaTentativaConcluiSemConsumirEmDobro() {
        tx.executeWithoutResult(s -> registrador.aplicar(EMPRESA, PRODUTO, new BigDecimal("-5"),
                EstoqueTipos.SAIDA_VENDA, null, MovimentoOrigem.sistema("venda", "sale-it-4", "Venda")));
        jdbc.execute("CREATE TRIGGER dbo.trg_falha_consumo ON dbo.LoteConsumos AFTER INSERT AS"
                + " THROW 50001, N'falha simulada no consumo', 1;");

        lotes.consumirVendaSeguro(EMPRESA, "sale-it-4", List.of(new ItemVenda(codigo, new BigDecimal("5"))));

        // a venda (saldo do produto) continua gravada; nada foi baixado dos lotes; a pendencia ficou registrada
        assertThat(saldoProduto()).isEqualByComparingTo("7");
        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("3");
        assertThat(consumos("sale-it-4")).isZero();
        assertThat(estadoPendencia("sale-it-4")).isEqualTo("pendente");

        // ainda nao venceu: a rotina nao mexe
        jdbc.update("UPDATE LoteBaixaPendencias SET ProximaTentativaEm = DATEADD(HOUR, 1, SYSDATETIMEOFFSET())");
        assertThat(retentativa.processarPendentes()).isZero();

        jdbc.execute("DROP TRIGGER dbo.trg_falha_consumo");
        vencerPendencias();
        assertThat(retentativa.processarPendentes()).isEqualTo(1);

        assertThat(estadoPendencia("sale-it-4")).isEqualTo("concluida");
        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("0");
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("2");
        assertThat(consumos("sale-it-4")).isEqualTo(2);

        // nada em dobro: nem a rotina, nem uma chamada repetida
        assertThat(retentativa.processarPendentes()).isZero();
        lotes.consumirVendaSeguro(EMPRESA, "sale-it-4", List.of(new ItemVenda(codigo, new BigDecimal("5"))));
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("2");
        assertThat(consumos("sale-it-4")).isEqualTo(2);
    }

    @Test
    void falhaNoEstornoViraPendenciaEConcluiNaNovaTentativa() {
        lotes.consumirVendaSeguro(EMPRESA, "sale-it-5", List.of(new ItemVenda(codigo, new BigDecimal("5"))));
        jdbc.execute("CREATE TRIGGER dbo.trg_falha_estorno ON dbo.LoteConsumos AFTER UPDATE AS"
                + " THROW 50002, N'falha simulada no estorno', 1;");

        lotes.estornarVendaSeguro(EMPRESA, "sale-it-5");

        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("0");
        assertThat(estadoPendencia("sale-it-5")).isEqualTo("pendente");

        jdbc.execute("DROP TRIGGER dbo.trg_falha_estorno");
        vencerPendencias();
        assertThat(retentativa.processarPendentes()).isEqualTo(1);

        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("3");
        assertThat(saldoLote("lote-ct-alerta")).isEqualByComparingTo("4");
        assertThat(estadoPendencia("sale-it-5")).isEqualTo("concluida");
        assertThat(retentativa.processarPendentes()).isZero();
        assertThat(saldoLote("lote-ct-vencido")).isEqualByComparingTo("3");
    }

    @Test
    void passadoOLimiteDeTentativasAPendenciaVaiParaRevisao() {
        jdbc.execute("CREATE TRIGGER dbo.trg_falha_consumo ON dbo.LoteConsumos AFTER INSERT AS"
                + " THROW 50001, N'falha simulada no consumo', 1;");
        lotes.consumirVendaSeguro(EMPRESA, "sale-it-6", List.of(new ItemVenda(codigo, new BigDecimal("1"))));

        for (int i = 0; i < 3; i++) {
            assertThat(estadoPendencia("sale-it-6")).isEqualTo("pendente");
            vencerPendencias();
            assertThat(retentativa.processarPendentes()).isZero();
        }

        assertThat(estadoPendencia("sale-it-6")).isEqualTo("revisao");
        assertThat(jdbc.queryForObject("SELECT Tentativas FROM LoteBaixaPendencias WHERE VendaId = N'sale-it-6'",
                Integer.class)).isEqualTo(3);
        vencerPendencias();
        assertThat(retentativa.processarPendentes()).isZero(); // em revisao nao e mais tentada
        assertThat(jdbc.queryForObject("SELECT UltimoErro FROM LoteBaixaPendencias WHERE VendaId = N'sale-it-6'",
                String.class)).contains("falha simulada");
    }
}
