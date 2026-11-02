package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Promoções (PromocaoController): ativas para a etiqueta mostrar o preço promocional, e o cadastro completo da tela
 * Promoções (listar, criar, editar, ativar/desativar, excluir e resultado). Escrita: gerente/administrador.
 */
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

    /** Todas as promoções da empresa (JSON da API: nome, tipo, valores, vigência, ativa, categoria e produtoIds). */
    public JsonNode listar() {
        return api.send("GET", "/api/Promocao", null, ApiClient.LIST_TIMEOUT);
    }

    public JsonNode obter(String id) {
        return api.send("GET", "/api/Promocao/" + ApiClient.q(id), null, ApiClient.LIST_TIMEOUT);
    }

    /** Desempenho: quantidadeVendas, receitaBruta, receitaLiquida, descontoTotal, margemLiquida. */
    public JsonNode resultado(String id) {
        return api.send("GET", "/api/Promocao/" + ApiClient.q(id) + "/resultado", null, ApiClient.LIST_TIMEOUT);
    }

    /** Cria (id null) ou atualiza a promoção; o corpo segue o PromocaoRequest da API. */
    public JsonNode salvar(String id, java.util.Map<String, Object> corpo) {
        return id == null || id.isBlank()
                ? api.send("POST", "/api/Promocao", corpo, ApiClient.LONG_TIMEOUT)
                : api.send("PUT", "/api/Promocao/" + ApiClient.q(id), corpo, ApiClient.LONG_TIMEOUT);
    }

    public void definirAtiva(String id, boolean ativa) {
        api.send("PATCH", "/api/Promocao/" + ApiClient.q(id) + "/status", java.util.Map.of("ativa", ativa), ApiClient.LONG_TIMEOUT);
    }

    public void excluir(String id) {
        api.send("DELETE", "/api/Promocao/" + ApiClient.q(id), null, ApiClient.LONG_TIMEOUT);
    }
}
