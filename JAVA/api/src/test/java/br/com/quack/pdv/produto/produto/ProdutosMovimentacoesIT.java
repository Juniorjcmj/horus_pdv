package br.com.quack.pdv.produto.produto;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.produto.produto.app.ProdutoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("it")
class ProdutosMovimentacoesIT {

    static {
        BancoIT.criarBanco("cadastros");
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> BancoIT.urlDoBanco("cadastros"));
        r.add("spring.datasource.username", BancoIT.SQL::getUsername);
        r.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired ProdutoService produtos;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(BancoIT.urlDoBanco("cadastros"), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .restaurar();
    }

    @Test
    void importarCargaLegadaPopulaSomenteEmpresaAlvo() throws Exception {
        String empresaTeste = "emp-it-carga-legado";
        jdbc.update(
                "INSERT INTO Empresas (Id, FantasyName, CorporateName, Cnpj, Status) VALUES (?, ?, ?, ?, 'aprovada')",
                empresaTeste, "Loja Legado IT", "Loja Legado IT Ltda", "99887766000155");
        try {
            int antesPrincipal = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM Produtos WHERE CompanyId = 'empresa-principal'", Integer.class);

            produtos.importarCargaLegado(empresaTeste);

            int totalImportado = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM Produtos WHERE CompanyId = ?", Integer.class, empresaTeste);
            int depoisPrincipal = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM Produtos WHERE CompanyId = 'empresa-principal'", Integer.class);

            assertThat(totalImportado).isGreaterThanOrEqualTo(4700);
            assertThat(depoisPrincipal).isEqualTo(antesPrincipal);
        } finally {
            jdbc.update("DELETE FROM Produtos WHERE CompanyId = ?", empresaTeste);
            jdbc.update("DELETE FROM Categorias WHERE CompanyId = ? AND CategoriaPaiId IS NOT NULL", empresaTeste);
            jdbc.update("DELETE FROM Categorias WHERE CompanyId = ?", empresaTeste);
            jdbc.update("DELETE FROM Empresas WHERE Id = ?", empresaTeste);
        }
    }

    @Test
    void falhaEmProdutoLotesNaoDesfazSaldoNemMovimentacaoDoAjuste() {
        jdbc.execute(
                "CREATE TRIGGER CT_FalharLotes ON ProdutoLotes INSTEAD OF INSERT, UPDATE AS THROW 51002, 'Falha forcada em ProdutoLotes', 1;");
        try {
            boolean entradaOk = produtos.ajustarEstoque(
                    "empresa-principal",
                    "pr-ct-val",
                    "entrada",
                    new BigDecimal("4"),
                    "Entrada com lote quebrado",
                    "usr-ct-estoq",
                    "Edu Estoquista",
                    "2027-06-01",
                    "L-FALHA");
            assertThat(entradaOk).isTrue();
            assertThat(jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id='pr-ct-val'", BigDecimal.class))
                    .isEqualByComparingTo("16");

            boolean perdaOk = produtos.ajustarEstoque(
                    "empresa-principal",
                    "pr-ct-val",
                    "perda",
                    new BigDecimal("3"),
                    "Perda com consumo FEFO quebrado",
                    "usr-ct-estoq",
                    "Edu Estoquista",
                    null,
                    null);
            assertThat(perdaOk).isTrue();
            assertThat(jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE Id='pr-ct-val'", BigDecimal.class))
                    .isEqualByComparingTo("13");

            Integer movsNovos = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE ProdutoId='pr-ct-val' AND Motivo LIKE '%quebrado%'",
                    Integer.class);
            assertThat(movsNovos).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER CT_FalharLotes");
        }
    }
}
