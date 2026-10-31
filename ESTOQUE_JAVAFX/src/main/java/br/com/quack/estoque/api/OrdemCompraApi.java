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

    public JsonNode obter(String numero) {
        return api.send("GET", "/api/OrdemCompra/" + ApiClient.q(numero), null, ApiClient.LIST_TIMEOUT);
    }
}
