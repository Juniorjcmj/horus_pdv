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

    /**
     * Regrava um pedido pendente (fornecedor e itens). Os demais campos (observação, previsão, condição, frete,
     * desconto) vêm do pedido atual para não se perderem.
     */
    public JsonNode atualizar(JsonNode atual, String supplierId, List<Item> itens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplierId", supplierId == null ? "" : supplierId);
        body.put("note", atual.path("note").asText(""));
        if (!atual.path("previsaoEntrega").isNull() && !atual.path("previsaoEntrega").isMissingNode()) {
            body.put("previsaoEntrega", atual.path("previsaoEntrega").asText());
        }
        body.put("condicaoPagamento", atual.path("condicaoPagamento").isNull() ? null : atual.path("condicaoPagamento").asText(null));
        body.put("formaPagamento", atual.path("formaPagamento").isNull() ? null : atual.path("formaPagamento").asText(null));
        body.put("valorFrete", atual.path("valorFrete").decimalValue());
        body.put("valorDesconto", atual.path("valorDesconto").decimalValue());
        List<Map<String, Object>> its = new ArrayList<>();
        for (Item i : itens) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productCode", i.productCode());
            m.put("quantity", i.quantity());
            m.put("unitCost", i.unitCost());
            its.add(m);
        }
        body.put("items", its);
        return api.send("PUT", "/api/OrdemCompra/" + ApiClient.q(atual.path("orderNumber").asText()), body, ApiClient.LONG_TIMEOUT);
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
