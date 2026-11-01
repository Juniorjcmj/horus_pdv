package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pedidos de compra (OrdemCompraController, gerente/administrador). */
public class OrdemCompraApi {
    /** Item do pedido: código do produto, quantidade e custo unitário. */
    public record Item(String productCode, BigDecimal quantity, BigDecimal unitCost) {
    }

    private final ApiClient api;

    public OrdemCompraApi(ApiClient api) {
        this.api = api;
    }

    /** Cria um pedido para o fornecedor e devolve o número (OrderNumber). */
    public String criar(String supplierId, String observacao, List<Item> itens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplierId", supplierId);
        body.put("note", observacao);
        List<Map<String, Object>> its = new ArrayList<>();
        for (Item i : itens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productCode", i.productCode());
            m.put("quantity", i.quantity());
            m.put("unitCost", i.unitCost());
            its.add(m);
        }
        body.put("items", its);
        JsonNode data = api.send("POST", "/api/OrdemCompra", body, ApiClient.LONG_TIMEOUT);
        return data == null ? "" : data.path("orderNumber").asText(data.path("OrderNumber").asText(""));
    }

    public JsonNode listar() {
        return api.send("GET", "/api/OrdemCompra", null, ApiClient.LIST_TIMEOUT);
    }

    /** Dados do pedido além dos itens (null/zero = vazio). previsaoEntrega em AAAA-MM-DD. */
    public record Cabecalho(String supplierId, String observacao, String previsaoEntrega, String condicaoPagamento,
            String formaPagamento, BigDecimal frete, BigDecimal desconto) {
        /** Cabeçalho atual do pedido (para trocar só fornecedor/itens sem perder o resto). */
        public static Cabecalho de(JsonNode oc) {
            String prev = oc.path("previsaoEntrega").isNull() || oc.path("previsaoEntrega").isMissingNode() ? null
                    : oc.path("previsaoEntrega").asText("");
            return new Cabecalho(oc.path("supplierId").isNull() ? "" : oc.path("supplierId").asText(""), oc.path("note").asText(""),
                    prev == null || prev.length() < 10 ? null : prev.substring(0, 10), texto(oc, "condicaoPagamento"),
                    texto(oc, "formaPagamento"), oc.path("valorFrete").decimalValue(), oc.path("valorDesconto").decimalValue());
        }

        private static String texto(JsonNode oc, String campo) {
            return oc.path(campo).isNull() ? null : oc.path(campo).asText(null);
        }
    }

    /** Regrava um pedido pendente (cabeçalho e itens). A API só aceita pedido com situação Pendente. */
    public JsonNode atualizar(String numero, Cabecalho c, List<Item> itens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplierId", c.supplierId() == null ? "" : c.supplierId());
        body.put("note", c.observacao() == null ? "" : c.observacao());
        if (c.previsaoEntrega() != null && !c.previsaoEntrega().isBlank()) {
            body.put("previsaoEntrega", c.previsaoEntrega() + "T12:00:00-03:00");
        }
        body.put("condicaoPagamento", vazioNull(c.condicaoPagamento()));
        body.put("formaPagamento", vazioNull(c.formaPagamento()));
        body.put("valorFrete", c.frete() == null ? BigDecimal.ZERO : c.frete());
        body.put("valorDesconto", c.desconto() == null ? BigDecimal.ZERO : c.desconto());
        List<Map<String, Object>> its = new ArrayList<>();
        for (Item i : itens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productCode", i.productCode());
            m.put("quantity", i.quantity());
            m.put("unitCost", i.unitCost());
            its.add(m);
        }
        body.put("items", its);
        return api.send("PUT", "/api/OrdemCompra/" + ApiClient.q(numero), body, ApiClient.LONG_TIMEOUT);
    }

    /** Atalho: troca fornecedor e itens mantendo o resto do pedido. */
    public JsonNode atualizar(JsonNode atual, String supplierId, List<Item> itens) {
        Cabecalho c = Cabecalho.de(atual);
        return atualizar(atual.path("orderNumber").asText(), new Cabecalho(supplierId, c.observacao(), c.previsaoEntrega(),
                c.condicaoPagamento(), c.formaPagamento(), c.frete(), c.desconto()), itens);
    }

    private static String vazioNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Item recebido: código, quantidade e (opcional) validade AAAA-MM-DD e lote. */
    public record Recebido(String productCode, BigDecimal quantity, String validade, String lote) {
    }

    /** Dá entrada no estoque do que chegou (parcial ou total). */
    public void receber(String numero, List<Recebido> itens) {
        List<Map<String, Object>> its = new ArrayList<>();
        for (Recebido r : itens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productCode", r.productCode());
            m.put("quantityReceived", r.quantity());
            if (r.validade() != null && !r.validade().isBlank()) {
                m.put("dataValidade", r.validade() + "T12:00:00-03:00");
            }
            if (r.lote() != null && !r.lote().isBlank()) {
                m.put("numeroLote", r.lote().trim());
            }
            its.add(m);
        }
        api.send("POST", "/api/OrdemCompra/" + ApiClient.q(numero) + "/receber", Map.of("itens", its), ApiClient.LONG_TIMEOUT);
    }

    /** Cancela um pedido pendente. */
    public void cancelar(String numero, String motivo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("motivoCancelamento", motivo);
        api.send("POST", "/api/OrdemCompra/" + ApiClient.q(numero) + "/cancelar", body, ApiClient.LONG_TIMEOUT);
    }

    public JsonNode obter(String numero) {
        return api.send("GET", "/api/OrdemCompra/" + ApiClient.q(numero), null, ApiClient.LIST_TIMEOUT);
    }
}
