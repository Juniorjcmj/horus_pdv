package br.com.quack.estoque.api;

import br.com.quack.estoque.analise.FiltrosAnalise;
import com.fasterxml.jackson.databind.JsonNode;

/** Blocos da análise de estoque calculados no servidor (EstoqueAnaliseController, api/Estoque/analise). */
public class AnaliseApi {
    private final ApiClient api;

    public AnaliseApi(ApiClient api) {
        this.api = api;
    }

    /** Devolve {dados, calculadoEm, doCache} do bloco (rota = "kpis", "serie-estoque"...). */
    public JsonNode bloco(String rota, FiltrosAnalise filtros) {
        return api.send("GET", "/api/Estoque/analise/" + rota + filtros.query(), null, ApiClient.LONG_TIMEOUT);
    }
}
