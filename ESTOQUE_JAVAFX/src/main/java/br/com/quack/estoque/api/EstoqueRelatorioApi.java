package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

/** Relatórios de compra calculados no servidor (EstoqueRelatorioController, api/Estoque/relatorios). */
public class EstoqueRelatorioApi {
    private final ApiClient api;

    public EstoqueRelatorioApi(ApiClient api) {
        this.api = api;
    }

    private JsonNode get(String rota) {
        return api.send("GET", "/api/Estoque/relatorios/" + rota, null, ApiClient.LONG_TIMEOUT);
    }

    /** Devolve {dias, cobertura, itens[], custoTotal}. */
    public JsonNode reposicao(int dias, int cobertura, String categoriaId) {
        return get("reposicao?dias=" + dias + "&cobertura=" + cobertura
                + (categoriaId == null || categoriaId.isBlank() ? "" : "&categoriaId=" + ApiClient.q(categoriaId)));
    }

    /** Devolve {de, ate, faturamentoTotal, itens[], classes[]}. */
    public JsonNode curvaAbc(LocalDate de, LocalDate ate, String categoriaId) {
        return get("curva-abc?de=" + de + "&ate=" + ate
                + (categoriaId == null || categoriaId.isBlank() ? "" : "&categoriaId=" + ApiClient.q(categoriaId)));
    }

    public JsonNode critico() {
        return get("critico");
    }

    public JsonNode parado(int dias) {
        return get("parado?dias=" + dias);
    }

    public JsonNode validade(int dias) {
        return get("validade?dias=" + dias);
    }

    /** Devolve {de, ate, itens[], quantidadeTotal, valorTotal}. */
    public JsonNode perdas(LocalDate de, LocalDate ate) {
        return get("perdas?de=" + de + "&ate=" + ate);
    }
}
