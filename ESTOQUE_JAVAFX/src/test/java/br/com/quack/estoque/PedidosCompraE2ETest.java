package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.OrdemCompraApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tela "Pedidos de compra": pedido para cotação (sem fornecedor) → escolhe o fornecedor e os preços → recebe em duas
 * partes (estoque sobe, situação vai a "recebido em parte" e depois "recebido"); outro pedido é cancelado.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class PedidosCompraE2ETest {
    @TempDir
    static Path home;

    private static Produto buscar(E2eSupport e, String id) {
        return e.ctx.produtos.listar().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void cotacaoFornecedorRecebimentoECancelamento() {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyName", "Cotado " + e.sufixo + " Ltda");
        f.put("fantasyName", "Cotado " + e.sufixo);
        for (String k : new String[] {"cnpj", "telephone", "email", "cep", "city", "state", "address", "neighborhood",
            "streetComplement", "number", "referencePoint", "cellphone"}) {
            f.put(k, "");
        }
        CadastroApi.Fornecedor vencedor = e.ctx.cadastros.salvarFornecedor(null, f);

        ObjectNode b = JsonFiles.MAPPER.createObjectNode();
        b.put("productName", "Pedido Agua " + e.sufixo);
        b.put("productCode", "PED-" + e.sufixo);
        b.put("productQnt", "2");
        b.put("productUnitPrice", "1,0000");
        b.put("productSalePrice", "3,00");
        b.put("unidadeComercial", "UN");
        Produto p = e.ctx.produtos.criar(b);

        // 1) pedido para cotação, sem fornecedor
        String numero = e.ctx.compras.criar("", "cotação e2e", List.of(new OrdemCompraApi.Item(p.codigo(), new BigDecimal("10"),
                BigDecimal.ZERO)));
        JsonNode oc = e.ctx.compras.obter(numero);
        assertTrue(oc.path("supplierId").isNull() || oc.path("supplierId").asText().isBlank(), "nasce sem fornecedor");
        assertEquals(0, oc.path("status").asInt());

        // 2) cotação fechada: escolhe o fornecedor e o preço
        e.ctx.compras.atualizar(oc, vencedor.id(), List.of(new OrdemCompraApi.Item(p.codigo(), new BigDecimal("10"),
                new BigDecimal("1.75"))));
        oc = e.ctx.compras.obter(numero);
        assertEquals(vencedor.id(), oc.path("supplierId").asText());
        assertEquals(0, new BigDecimal("1.75").compareTo(oc.path("itens").get(0).path("unitCost").decimalValue()));
        assertEquals("cotação e2e", oc.path("note").asText(), "observação preservada");

        // 3) recebe 4 de 10: estoque 2 → 6, recebido em parte
        e.ctx.compras.receber(numero, List.of(new OrdemCompraApi.Recebido(p.codigo(), new BigDecimal("4"), "2027-03-31", "L1")));
        assertEquals(3, e.ctx.compras.obter(numero).path("status").asInt(), "recebido em parte");
        assertEquals(0, new BigDecimal("6").compareTo(buscar(e, p.id()).estoque()));

        // 4) recebe o resto: estoque 12, recebido
        e.ctx.compras.receber(numero, List.of(new OrdemCompraApi.Recebido(p.codigo(), new BigDecimal("6"), null, null)));
        assertEquals(1, e.ctx.compras.obter(numero).path("status").asInt(), "recebido");
        assertEquals(0, new BigDecimal("12").compareTo(buscar(e, p.id()).estoque()));

        // 5) outro pedido é cancelado com motivo
        String outro = e.ctx.compras.criar("", "cancelar e2e", List.of(new OrdemCompraApi.Item(p.codigo(), BigDecimal.ONE,
                BigDecimal.ONE)));
        e.ctx.compras.cancelar(outro, "Cotação não aprovada");
        JsonNode cancelado = e.ctx.compras.obter(outro);
        assertEquals(2, cancelado.path("status").asInt());
        assertEquals("Cotação não aprovada", cancelado.path("motivoCancelamento").asText());
        log("pedido " + numero + " recebido em 2 partes; pedido " + outro + " cancelado");
    }
}
