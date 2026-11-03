package br.com.quack.pdv.estoque.inventario;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.estoque.inventario.app.InventarioAtor;
import br.com.quack.pdv.estoque.inventario.app.InventarioResultado;
import br.com.quack.pdv.estoque.inventario.app.InventarioService;
import br.com.quack.pdv.estoque.registrador.EstoqueTipos;
import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Aplicacao do inventario sobre o banco do seed: saldo final, movimentacao e preservacao de movimentos posteriores. */
@SpringBootTest
@ActiveProfiles("it")
class InventarioServiceIT {

    static {
        BancoIT.criarBanco("cadastros");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("cadastros"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    private static final String EMPRESA = "empresa-principal";
    private static final String INVENTARIO = "inv-ct-aberto";
    private static final String PRODUTO = "pr-ct-inv";
    private static final InventarioAtor ATOR = new InventarioAtor("usr-ct-gerente", "Gil Gerente", "127.0.0.1", null);

    @Autowired InventarioService service;
    @Autowired RegistradorEstoque registrador;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("cadastros"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    private BigDecimal saldo() {
        return jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id = ?", BigDecimal.class, PRODUTO);
    }

    private InventarioResultado aplicar() {
        return service.aplicar(EMPRESA, INVENTARIO, ATOR, "usr-ct-gerente", "Gil Gerente", "Contagem conferida", false);
    }

    private void contar(int quantidade) {
        InventarioResultado r = service.contar(EMPRESA, INVENTARIO, ATOR, PRODUTO, null, BigDecimal.valueOf(quantidade), null);
        assertThat(r.sucesso()).isTrue();
    }

    @Test
    void aplicaADiferencaEGravaMovimentacaoDoTipoInventario() {
        contar(7);

        InventarioResultado r = aplicar();

        assertThat(r.sucesso()).isTrue();
        assertThat(saldo()).isEqualByComparingTo("7");
        List<Map<String, Object>> movs = jdbc.queryForList(
                "SELECT Tipo, Quantidade, EstoqueAnterior, EstoqueNovo, OrigemTipo, OrigemId FROM EstoqueMovimentacoes"
                        + " WHERE ProdutoId = ? AND OrigemTipo = 'inventario'", PRODUTO);
        assertThat(movs).hasSize(1);
        assertThat(movs.getFirst().get("Tipo")).isEqualTo(EstoqueTipos.INVENTARIO);
        assertThat((BigDecimal) movs.getFirst().get("Quantidade")).isEqualByComparingTo("3");
        assertThat((BigDecimal) movs.getFirst().get("EstoqueAnterior")).isEqualByComparingTo("10");
        assertThat((BigDecimal) movs.getFirst().get("EstoqueNovo")).isEqualByComparingTo("7");
        assertThat(movs.getFirst().get("OrigemId")).isEqualTo(INVENTARIO);
        assertThat(jdbc.queryForObject("SELECT Status FROM Inventarios WHERE Id = ?", String.class, INVENTARIO))
                .isEqualTo("aplicado");
    }

    @Test
    void preservaSaidaPosteriorAContagem() {
        contar(7);
        new TransactionTemplate(tm).executeWithoutResult(s -> registrador.aplicar(EMPRESA, PRODUTO,
                BigDecimal.valueOf(-2), EstoqueTipos.SAIDA, null, MovimentoOrigem.sistema("teste", "1", "Venda")));

        assertThat(aplicar().sucesso()).isTrue();

        assertThat(saldo()).isEqualByComparingTo("5");
    }

    @Test
    void naoPassaDeZeroERegistraOPiso() {
        contar(7);
        new TransactionTemplate(tm).executeWithoutResult(s -> registrador.aplicar(EMPRESA, PRODUTO,
                BigDecimal.valueOf(-9), EstoqueTipos.SAIDA, null, MovimentoOrigem.sistema("teste", "1", "Saida")));

        assertThat(aplicar().sucesso()).isTrue();

        assertThat(saldo()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT PisoAplicado FROM InventarioItens WHERE InventarioId = ? AND ProdutoId = ?",
                BigDecimal.class, INVENTARIO, PRODUTO)).isEqualByComparingTo("2");
    }

    @Test
    void aplicarDuasVezesRecusaSemMudarOEstoque() {
        contar(7);
        assertThat(aplicar().sucesso()).isTrue();

        InventarioResultado segunda = aplicar();

        assertThat(segunda.sucesso()).isFalse();
        assertThat(segunda.codigo()).isEqualTo("JA_APLICADO");
        assertThat(saldo()).isEqualByComparingTo("7");
    }

    @Test
    void contagemAnuladaDeixaOItemSemContagem() {
        InventarioResultado c = service.contar(EMPRESA, INVENTARIO, ATOR, PRODUTO, null, BigDecimal.valueOf(4), null);
        @SuppressWarnings("unchecked")
        String contagemId = (String) ((Map<String, Object>) c.dados()).get("contagemId");

        InventarioResultado a = service.anularContagem(EMPRESA, INVENTARIO, contagemId, ATOR, "Erro de digitação", false);

        assertThat(a.sucesso()).isTrue();
        assertThat(jdbc.queryForObject("SELECT Contado FROM InventarioItens WHERE InventarioId = ? AND ProdutoId = ?",
                BigDecimal.class, INVENTARIO, PRODUTO)).isNull();
    }
}
