package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.falha;
import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.ApiException;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.ProdutoApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Cadastro de produtos, categorias e fornecedores, ajustes avulsos e histórico de movimentações pela camada de serviço. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CadastrosAjustesE2ETest {
    @TempDir
    static Path home;

    E2eSupport e;
    Produto produto;
    String gtinValido;

    @BeforeAll
    void preparar() {
        e = new E2eSupport(home);
        e.loginAdmin();
        // GTIN único por execução (13 dígitos com DV válido)
        String base = "789" + String.format("%09d", Math.abs(System.nanoTime() % 1_000_000_000L));
        gtinValido = TestData.ean(base);
    }

    @Test
    @Order(1)
    void criaProdutoComGtinEAlteraPreco() {
        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", "Cad Produto " + e.sufixo);
        b.put("productCode", "CAD-" + e.sufixo);
        b.put("productQnt", "12");
        b.put("productUnitPrice", "3,0000");
        b.put("productSalePrice", "7,50");
        b.put("estoqueMinimo", "2");
        b.put("estoqueMaximo", "50");
        b.put("unidadeComercial", "UN");
        b.put("gtin", gtinValido);
        produto = e.ctx.produtos.criar(b);
        assertEquals(gtinValido, produto.gtin());
        assertEquals(0, new BigDecimal("7.50").compareTo(produto.preco()));

        Produto alterado = e.ctx.produtos.atualizarPreco(produto.id(), "8,90", produto.versaoPreco());
        assertNotNull(alterado);
        assertEquals(0, new BigDecimal("8.90").compareTo(e.produto(produto.id()).preco()), "preço gravado e conferido na API");
        // preço desatualizado (outra pessoa mudou antes): a API recusa em vez de sobrescrever
        ApiException conflito = falha(() -> e.ctx.produtos.atualizarPreco(produto.id(), "9,90", produto.versaoPreco()));
        assertTrue(conflito.status() == 409 || conflito.status() == 400, "versão antiga recusada: " + conflito.status());
    }

    @Test
    @Order(2)
    void gtinDuplicadoMostraAMensagemDaApi() {
        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", "Cad Duplicado " + e.sufixo);
        b.put("productCode", "CAD-DUP-" + e.sufixo);
        b.put("productQnt", "1");
        b.put("productUnitPrice", "1,0000");
        b.put("productSalePrice", "2,00");
        b.put("unidadeComercial", "UN");
        b.put("gtin", gtinValido);
        ApiException ex = falha(() -> e.ctx.produtos.criar(b));
        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().toLowerCase().contains("gtin") || ex.getMessage().toLowerCase().contains("existe"), ex.getMessage());
        log("GTIN duplicado: " + ex.getMessage());
    }

    @Test
    @Order(3)
    void categoriaFilhaEFornecedor() {
        String pai = e.ctx.cadastros.salvarCategoria(null, "Cad Pai " + e.sufixo, null, true);
        String filha = e.ctx.cadastros.salvarCategoria(null, "Cad Filha " + e.sufixo, pai, true);
        List<CadastroApi.Categoria> todas = e.ctx.cadastros.categorias();
        CadastroApi.Categoria c = todas.stream().filter(x -> x.id().equals(filha)).findFirst().orElseThrow();
        assertEquals(pai, c.categoriaPaiId());
        assertEquals("Cad Pai " + e.sufixo + " > Cad Filha " + e.sufixo, c.caminho());
        // 3o nível é recusado (a categoria só tem 2 níveis)
        assertEquals(400, falha(() -> e.ctx.cadastros.salvarCategoria(null, "Neta " + e.sufixo, filha, true)).status());

        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyName", "Distribuidora E2E " + e.sufixo + " Ltda");
        f.put("fantasyName", "Distribuidora " + e.sufixo);
        f.put("cnpj", "");
        f.put("telephone", "1133334444");
        f.put("email", "e2e@exemplo.com");
        for (String k : new String[] {"cep", "city", "state", "address", "neighborhood", "streetComplement", "number", "referencePoint", "cellphone"}) {
            f.put(k, "");
        }
        CadastroApi.Fornecedor salvo = e.ctx.cadastros.salvarFornecedor(null, f);
        assertEquals("Distribuidora " + e.sufixo, salvo.nome());
        assertTrue(e.ctx.cadastros.fornecedores().stream().anyMatch(x -> x.id().equals(salvo.id())));
    }

    @Test
    @Order(4)
    void registraPerdaEConfereAMovimentacao() {
        e.ctx.produtos.ajustar(produto.id(), "perda", new BigDecimal("2"), "embalagem avariada", null, null);
        assertEquals(10, e.produto(produto.id()).estoque().intValue());
        List<ProdutoApi.Movimentacao> movs = e.ctx.produtos.movimentacoes(produto.id());
        ProdutoApi.Movimentacao m = movs.stream().filter(x -> "perda".equals(x.tipo())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("2").compareTo(m.quantidade()));
        assertEquals(0, new BigDecimal("12").compareTo(m.estoqueAnterior()));
        assertEquals(0, new BigDecimal("10").compareTo(m.estoqueNovo()));
        assertEquals("embalagem avariada", m.motivo());
        assertFalse(m.usuarioNome().isBlank());
    }

    @Test
    @Order(5)
    void saidaMaiorQueOSaldoERecusadaESemMotivoTambem() {
        ApiException ex = falha(() -> e.ctx.produtos.ajustar(produto.id(), "saida", new BigDecimal("999"), "tentativa", null, null));
        assertEquals(400, ex.status());
        assertTrue(ex.getMessage().toLowerCase().contains("insuficiente"), ex.getMessage());
        assertEquals(10, e.produto(produto.id()).estoque().intValue(), "saldo inalterado");
        assertEquals(400, falha(() -> e.ctx.produtos.ajustar(produto.id(), "saida", BigDecimal.ONE, "", null, null)).status(), "motivo obrigatório");
    }

    @Test
    @Order(6)
    void entradaComLoteEValidadeEFiltroPorTipo() {
        e.ctx.produtos.ajustar(produto.id(), "entrada", new BigDecimal("4"), "reposição", java.time.LocalDate.now().plusDays(20).toString(), "LOTE-A");
        assertEquals(14, e.produto(produto.id()).estoque().intValue());
        List<ProdutoApi.Movimentacao> perdas = e.ctx.produtos.movimentacoes("perda", 500);
        assertFalse(perdas.isEmpty());
        assertTrue(perdas.stream().allMatch(x -> "perda".equals(x.tipo())), "o filtro traz só perdas");
        assertTrue(perdas.stream().anyMatch(x -> x.produtoId().equals(produto.id())));
    }
}
