package br.com.quack.pdv.produto.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import br.com.quack.pdv.ApiIT;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** Importacao de produtos por planilha contra o SQL Server real (Testcontainers), pela API inteira. */
class ImportacaoProdutosIT extends ApiIT {

    private static final Logger LOG = LoggerFactory.getLogger(ImportacaoProdutosIT.class);
    private static final String SIMULAR = "/api/Produto/importacao/simular";
    private static final String CONFIRMAR = "/api/Produto/importacao/confirmar";

    private String empresa;

    @BeforeEach
    void limpar() throws Exception {
        empresa = jdbc.queryForObject(
                "SELECT CompanyId FROM Usuarios WHERE Email = 'gerente@contrato.quack'", String.class);
        limparImportados();
    }

    @AfterEach
    void depois() {
        jdbc.execute("IF OBJECT_ID('dbo.TR_FalharImportacao') IS NOT NULL DROP TRIGGER dbo.TR_FalharImportacao");
        limparImportados();
    }

    private void limparImportados() {
        String filtro = "ProductCode LIKE '9[0-9][0-9][0-9]' OR ProductCode LIKE '1[0-9][0-9][0-9][0-9][0-9]'";
        jdbc.update("DELETE FROM EstoqueMovimentacoes WHERE CompanyId = ? AND OrigemTipo = 'importacao'", empresa);
        jdbc.update("DELETE FROM ImportacoesProduto WHERE CompanyId = ?", empresa);
        jdbc.update("DELETE FROM Produtos WHERE CompanyId = ? AND (" + filtro + ")", empresa);
        jdbc.update("DELETE FROM Categorias WHERE CompanyId = ? AND CategoriaPaiId IS NOT NULL AND Nome IN "
                + "(SELECT Nome FROM Categorias WHERE CompanyId = ? AND (Nome LIKE 'GRUPO %' OR Nome LIKE 'SUB %' "
                + "OR Nome = 'ACESSORIOS'))", empresa, empresa);
        jdbc.update("DELETE FROM Categorias WHERE CompanyId = ? AND (Nome LIKE 'GRUPO %' OR Nome LIKE 'SUB %' "
                + "OR Nome = 'ACESSORIOS')", empresa);
        jdbc.update("DELETE FROM Categorias WHERE CompanyId = ? AND (Nome LIKE 'CLASSE %' OR Nome LIKE 'classe %')", empresa);
    }

    private MvcResult enviar(String rota, byte[] bytes, String usuario) throws Exception {
        return mvc.perform(multipart(rota)
                        .file(new MockMultipartFile("arquivo", "p.csv", "text/csv", bytes))
                        .header("Authorization", bearer(usuario)))
                .andReturn();
    }

    private JsonNode dados(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return corpo(r).get("data");
    }

    private int contar(String sql) {
        return jdbc.queryForObject(sql, Integer.class, empresa);
    }

    private int produtosDaPlanilha() {
        return contar("SELECT COUNT(*) FROM Produtos WHERE CompanyId = ? AND ProductCode LIKE '9[0-9][0-9][0-9]'");
    }

    @Test
    void simulacaoNaoGravaNada() throws Exception {
        int categoriasAntes = contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ?");
        int movAntes = contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?");

        JsonNode d = dados(enviar(SIMULAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        assertThat(d.get("totais").get("novos").asInt()).isEqualTo(36);
        assertThat(d.get("totais").get("recusados").asInt()).isEqualTo(5);
        assertThat(produtosDaPlanilha()).isZero();
        assertThat(contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ?")).isEqualTo(categoriasAntes);
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?")).isEqualTo(movAntes);
        assertThat(contar("SELECT COUNT(*) FROM ImportacoesProduto WHERE CompanyId = ?")).isZero();
    }

    @Test
    void atendenteRecebe403() throws Exception {
        assertThat(enviar(SIMULAR, PlanilhaSintetica.amostra(), "atendente@contrato.quack").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "atendente@contrato.quack").getResponse().getStatus())
                .isEqualTo(403);
        assertThat(produtosDaPlanilha()).isZero();
    }

    @Test
    void arquivoAcimaDoLimiteResponde400() throws Exception {
        MvcResult r = enviar(SIMULAR, new byte[LeitorCsvProdutos.MAX_BYTES + 1], "gerente@contrato.quack");

        assertThat(r.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void simularEConfirmarDevolvemOsMesmosTotais() throws Exception {
        JsonNode simulado = dados(enviar(SIMULAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));
        JsonNode confirmado = dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        assertThat(confirmado.get("totais")).isEqualTo(simulado.get("totais"));
        assertThat(confirmado.get("resumoFiscal")).isEqualTo(simulado.get("resumoFiscal"));
        assertThat(confirmado.get("gravadoEm").asText()).isNotBlank();
        assertThat(simulado.get("gravadoEm").isNull()).isTrue();
    }

    @Test
    void entradaInicialGravaProdutoCategoriaEMovimentacao() throws Exception {
        dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        Map<String, Object> p = jdbc.queryForMap(
                "SELECT ProductQnt, ProductSalePrice, ProductUnitPrice, Gtin, CategoriaId, Cfop FROM Produtos "
                        + "WHERE CompanyId = ? AND ProductCode = '9001'", empresa);
        assertThat((BigDecimal) p.get("ProductQnt")).isEqualByComparingTo("24");
        assertThat((BigDecimal) p.get("ProductSalePrice")).isEqualByComparingTo("6.90");
        assertThat((BigDecimal) p.get("ProductUnitPrice")).isEqualByComparingTo("1.33");
        assertThat(p.get("Gtin")).isEqualTo("SEM GTIN");
        assertThat(p.get("CategoriaId")).isNotNull();

        Map<String, Object> mov = jdbc.queryForMap(
                "SELECT m.Tipo, m.Quantidade, m.CustoUnitario, m.OrigemTipo FROM EstoqueMovimentacoes m "
                        + "JOIN Produtos p ON p.Id = m.ProdutoId WHERE p.CompanyId = ? AND p.ProductCode = '9001'", empresa);
        assertThat(mov.get("Tipo")).isEqualTo("entrada");
        assertThat((BigDecimal) mov.get("Quantidade")).isEqualByComparingTo("24");
        assertThat((BigDecimal) mov.get("CustoUnitario")).isEqualByComparingTo("1.33");
        assertThat(mov.get("OrigemTipo")).isEqualTo("importacao");

        assertThat(jdbc.queryForObject("SELECT ProductName FROM Produtos WHERE CompanyId = ? AND ProductCode = '9013'",
                        String.class, empresa))
                .isEqualTo("BOCAL RECEPTACULO DE LOUÇA E-27");
        assertThat(jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = '9007'",
                        BigDecimal.class, empresa))
                .isEqualByComparingTo("0.5");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ? AND OrigemTipo = 'importacao'"))
                .isEqualTo(26);
        assertThat(produtosDaPlanilha()).isEqualTo(36);
    }

    @Test
    void saldoNegativoEntraComoZeroSemMovimentacao() throws Exception {
        dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        assertThat(jdbc.queryForObject("SELECT ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = '9006'",
                        BigDecimal.class, empresa))
                .isEqualByComparingTo("0");
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes m JOIN Produtos p ON p.Id = m.ProdutoId "
                        + "WHERE p.CompanyId = ? AND p.ProductCode = '9006'"))
                .isZero();
    }

    @Test
    void categoriasEmTresNiveisSemDuplicar() throws Exception {
        dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        assertThat(contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ? AND Nome = 'CLASSE TESTE A' "
                        + "AND CategoriaPaiId IS NULL"))
                .isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ? AND Nome = 'ACESSORIOS'")).isEqualTo(2);
        List<String> caminho = jdbc.queryForList(
                "SELECT s.Nome + '/' + g.Nome + '/' + c.Nome FROM Produtos p "
                        + "JOIN Categorias s ON s.Id = p.CategoriaId JOIN Categorias g ON g.Id = s.CategoriaPaiId "
                        + "JOIN Categorias c ON c.Id = g.CategoriaPaiId WHERE p.CompanyId = ? AND p.ProductCode = '9001'",
                String.class, empresa);
        assertThat(caminho).containsExactly("SUB TESTE A1X/GRUPO TESTE A1/CLASSE TESTE A");
    }

    @Test
    void reimportacaoNaoDuplicaEstoqueNemProdutos() throws Exception {
        dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));
        int mov = contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?");
        int categorias = contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ?");

        JsonNode d = dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        assertThat(d.get("totais").get("novos").asInt()).isZero();
        assertThat(d.get("totais").get("atualizados").asInt()).isEqualTo(36);
        assertThat(d.get("totais").get("produtosComSaldo").asInt()).isZero();
        assertThat(d.get("totais").get("categoriasNovas").asInt()).isZero();
        assertThat(produtosDaPlanilha()).isEqualTo(36);
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ?")).isEqualTo(mov);
        assertThat(contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ?")).isEqualTo(categorias);
    }

    @Test
    void precoDeProdutoExistenteEAtualizadoMantendoOSaldo() throws Exception {
        dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));
        jdbc.update("UPDATE Produtos SET ProductSalePrice = 6.50 WHERE CompanyId = ? AND ProductCode = '9001'", empresa);
        jdbc.update("UPDATE Produtos SET ProductQnt = 20 WHERE CompanyId = ? AND ProductCode = '9001'", empresa);

        JsonNode d = dados(enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack"));

        assertThat(d.get("totais").get("atualizados").asInt()).isEqualTo(36);
        Map<String, Object> p = jdbc.queryForMap(
                "SELECT ProductSalePrice, ProductQnt FROM Produtos WHERE CompanyId = ? AND ProductCode = '9001'", empresa);
        assertThat((BigDecimal) p.get("ProductSalePrice")).isEqualByComparingTo("6.90");
        assertThat((BigDecimal) p.get("ProductQnt")).isEqualByComparingTo("20");
    }

    @Test
    void falhaNoMeioNaoDeixaNadaGravado() throws Exception {
        jdbc.execute("CREATE TRIGGER dbo.TR_FalharImportacao ON dbo.Produtos AFTER INSERT AS "
                + "IF EXISTS (SELECT 1 FROM inserted WHERE ProductCode = '9020') THROW 51000, 'Falha forcada', 1;");
        int categoriasAntes = contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ?");

        MvcResult r = enviar(CONFIRMAR, PlanilhaSintetica.amostra(), "gerente@contrato.quack");

        assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(produtosDaPlanilha()).isZero();
        assertThat(contar("SELECT COUNT(*) FROM Categorias WHERE CompanyId = ?")).isEqualTo(categoriasAntes);
        assertThat(contar("SELECT COUNT(*) FROM EstoqueMovimentacoes WHERE CompanyId = ? AND OrigemTipo = 'importacao'"))
                .isZero();
        assertThat(contar("SELECT COUNT(*) FROM ImportacoesProduto WHERE CompanyId = ?")).isZero();
    }

    @Test
    void volumeDe3300LinhasConfirmaEmMenosDe60Segundos() throws Exception {
        byte[] planilha = PlanilhaSintetica.gerar(3300);

        long inicio = System.nanoTime();
        JsonNode d = dados(enviar(CONFIRMAR, planilha, "gerente@contrato.quack"));
        long segundos = (System.nanoTime() - inicio) / 1_000_000_000L;

        LOG.info("Importacao de 3300 linhas confirmada em {} s", segundos);
        assertThat(d.get("totais").get("novos").asInt()).isEqualTo(3300);
        assertThat(d.get("totais").get("recusados").asInt()).isZero();
        assertThat(contar("SELECT COUNT(*) FROM Produtos WHERE CompanyId = ? AND ProductCode LIKE '1[0-9][0-9][0-9][0-9][0-9]'"))
                .isEqualTo(3300);
        assertThat(segundos).isLessThan(60);
    }
}
