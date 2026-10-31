package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Promoções ativas, para a etiqueta mostrar o preço promocional (GET /api/Promocao/ativas). */
public class PromocaoApi {
    /** Só as promoções de preço fixo interessam à etiqueta. */
    public record Promocao(String nome, String tipo, BigDecimal precoFixo, String inicio, String fim,
            String categoriaId, List<String> produtoIds) {
        public boolean ehPrecoFixo() {
            return precoFixo != null && precoFixo.signum() > 0 && tipo != null
                    && tipo.toLowerCase().contains("preco");
        }
    }

    private final ApiClient api;

    public PromocaoApi(ApiClient api) {
        this.api = api;
    }

    public List<Promocao> ativas() {
        JsonNode data = api.send("GET", "/api/Promocao/ativas", null, ApiClient.LIST_TIMEOUT);
        List<Promocao> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                List<String> ids = new ArrayList<>();
                for (JsonNode id : n.path("produtoIds")) {
                    ids.add(id.asText());
                }
                out.add(new Promocao(n.path("nome").asText(""), n.path("tipo").asText(""),
                        n.hasNonNull("precoFixo") ? n.get("precoFixo").decimalValue() : null,
                        n.path("inicioVigencia").asText(""), n.path("fimVigencia").asText(""),
                        n.path("categoriaId").asText(""), ids));
            }
        }
        return out;
    }
}
