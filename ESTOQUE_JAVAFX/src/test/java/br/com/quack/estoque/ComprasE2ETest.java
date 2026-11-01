package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.falha;
import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.OrdemCompraApi;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.ComprasView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Sugestão de reposição que vira um pedido de compra por fornecedor (dois fornecedores, dois pedidos). */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class ComprasE2ETest {
    @TempDir
    static Path home;

    private CadastroApi.Fornecedor fornecedor(E2eSupport e, String nome) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyName", nome + " Ltda");
        f.put("fantasyName", nome);
        f.put("cnpj", "");
        for (String k : new String[] {"telephone", "email", "cep", "city", "state", "address", "neighborhood", "streetComplement", "number",
            "referencePoint", "cellphone"}) {
            f.put(k, "");
        }
        return e.ctx.cadastros.salvarFornecedor(null, f);
    }

    private Produto produto(E2eSupport e, String nome, String codigo, String forn, String estoque, String minimo) {
        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", nome + " " + e.sufixo);
        b.put("productCode", codigo + "-" + e.sufixo);
        b.put("productQnt", estoque);
        b.put("estoqueMinimo", minimo);
        b.put("productUnitPrice", "5,0000");
        b.put("productSalePrice", "9,00");
        b.put("unidadeComercial", "UN");
        b.put("productSupplier", forn);
        return e.ctx.produtos.criar(b);
    }

    @Test
    void geraUmPedidoPorFornecedor() {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        String catId = e.ctx.cadastros.salvarCategoria(null, "E2E Compras " + e.sufixo, null, true);
        CadastroApi.Fornecedor f1 = fornecedor(e, "Forn Um " + e.sufixo);
        CadastroApi.Fornecedor f2 = fornecedor(e, "Forn Dois " + e.sufixo);
        Produto p1 = produto(e, "Compra A", "CPA", f1.nome(), "1", "10");
        Produto p2 = produto(e, "Compra B", "CPB", f2.nome(), "2", "10");
        Produto p3 = produto(e, "Compra C", "CPC", "", "3", "10"); // sem fornecedor

        JsonNode rep = e.ctx.relatorios.reposicao(30, 15, null);
        List<ComprasView.LinhaCompra> linhas = new ArrayList<>();
        for (JsonNode i : rep.get("itens")) {
            String cod = i.get("codigo").asText();
            if (cod.equals(p1.codigo()) || cod.equals(p2.codigo()) || cod.equals(p3.codigo())) {
                linhas.add(new ComprasView.LinhaCompra(i, true));
            }
        }
        assertEquals(3, linhas.size());

        // um pedido por fornecedor + um para cotação com o item sem fornecedor
        Map<String, List<ComprasView.LinhaCompra>> grupos = ComprasView.agrupar(linhas);
        assertEquals(3, grupos.size(), "um pedido por fornecedor e um para cotação");
        assertEquals(List.of(p3.codigo()), grupos.get("").stream().map(ComprasView.LinhaCompra::codigo).toList());
        List<List<String>> cotacao = ComprasView.linhasCotacao(grupos.get(""));
        assertEquals("__________", cotacao.get(0).get(3), "cotação sai sem o nosso custo");

        List<String> numeros = new ArrayList<>();
        for (var g : grupos.entrySet()) {
            List<OrdemCompraApi.Item> itens = new ArrayList<>();
            for (ComprasView.LinhaCompra l : g.getValue()) {
                String esperado = l.codigo().startsWith("CPA") ? "9" : l.codigo().startsWith("CPB") ? "8" : "7";
                assertEquals(0, new BigDecimal(esperado).compareTo(l.qtd()), "completa o mínimo de 10");
                itens.add(new OrdemCompraApi.Item(l.codigo(), l.qtd(), l.custoUnit()));
            }
            numeros.add(e.ctx.compras.criar(g.getKey(), "teste e2e", itens));
        }
        assertEquals(3, numeros.size());
        assertEquals(3, new java.util.HashSet<>(numeros).size());

        JsonNode todas = e.ctx.compras.listar();
        List<String> existentes = new ArrayList<>();
        todas.forEach(o -> existentes.add(o.path("orderNumber").asText(o.path("OrderNumber").asText())));
        for (String n : numeros) {
            assertTrue(existentes.contains(n), "pedido " + n + " listado em GET /api/OrdemCompra");
        }
        JsonNode oc = e.ctx.compras.obter(numeros.get(0));
        assertNotNull(oc, "detalhe do pedido");
        int idxCotacao = new ArrayList<>(grupos.keySet()).indexOf("");
        JsonNode cot = e.ctx.compras.obter(numeros.get(idxCotacao));
        log("pedido para cotação: " + cot.path("supplierName").asText(cot.path("SupplierName").asText()));
        log("pedidos criados: " + numeros);
    }

    @Test
    void quantidadeInvalidaEItemDesmarcado() {
        JsonNode i = JsonFiles.MAPPER.createObjectNode().put("codigo", "X").put("nome", "Produto X").put("fornecedorId", "f1")
                .put("fornecedor", "F1").put("sugerido", 4);
        ComprasView.LinhaCompra l = new ComprasView.LinhaCompra(i, true);
        assertEquals(1, ComprasView.agrupar(List.of(l)).size());
        assertThrows(DomainException.class, () -> {
            ComprasView.LinhaCompra zero = new ComprasView.LinhaCompra(i, true);
            zero.definirQuantidade("0");
            ComprasView.agrupar(List.of(zero));
        });
    }
}
