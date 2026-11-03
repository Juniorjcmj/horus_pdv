package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controle de validade por lote (LoteController, api/Produto/lotes): alertas, consulta paginada, baixa por lote (FEFO),
 * registro manual de lote e prazos por categoria. Mudar o modo FEFO e os prazos: gerente/administrador.
 */
public class LoteApi {
    /** Filtros da consulta de lotes (null/vazio = sem filtro). Datas AAAA-MM-DD. */
    public record Filtro(String busca, String de, String ate, String categoriaId, String faixa, boolean comSaldo,
            String origem, int pagina, int tamanhoPagina) {
    }

    private final ApiClient api;

    public LoteApi(ApiClient api) {
        this.api = api;
    }

    /** vencidos, criticos, atencao, valorEmRisco, produtosSemLote, modo e itens (lotes em alerta). */
    public JsonNode alertas() {
        return api.send("GET", "/api/Produto/lotes/alertas", null, ApiClient.LIST_TIMEOUT);
    }

    /** total, pagina, tamanhoPagina, valorEmRisco, modo e itens. */
    public JsonNode consulta(Filtro f) {
        StringBuilder q = new StringBuilder("?pagina=").append(Math.max(1, f.pagina()))
                .append("&tamanhoPagina=").append(f.tamanhoPagina() <= 0 ? 25 : f.tamanhoPagina());
        param(q, "busca", f.busca());
        param(q, "de", f.de());
        param(q, "ate", f.ate());
        param(q, "categoriaId", f.categoriaId());
        param(q, "faixa", f.faixa());
        param(q, "origem", f.origem());
        if (f.comSaldo()) {
            q.append("&comSaldo=true");
        }
        return api.send("GET", "/api/Produto/lotes/consulta" + q, null, ApiClient.LIST_TIMEOUT);
    }

    private static void param(StringBuilder q, String nome, String valor) {
        if (valor != null && !valor.isBlank()) {
            q.append('&').append(nome).append('=').append(ApiClient.q(valor.trim()));
        }
    }

    /** modo, lotesComSaldo, lotesComDivergencia, produtosComDivergencia, produtosComEstoqueSemLote e itens. */
    public JsonNode fefo() {
        return api.send("GET", "/api/Produto/lotes/fefo", null, ApiClient.LIST_TIMEOUT);
    }

    /** desligado | sombra | ativo. */
    public void definirModo(String modo) {
        api.send("PUT", "/api/Produto/lotes/fefo/modo", Map.of("modo", modo), ApiClient.LONG_TIMEOUT);
    }

    public void registrar(String produtoId, String dataValidade, BigDecimal quantidade, String numeroLote) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("produtoId", produtoId);
        b.put("dataValidade", dataValidade);
        b.put("quantidade", quantidade);
        if (numeroLote != null && !numeroLote.isBlank()) {
            b.put("numeroLote", numeroLote.trim());
        }
        api.send("POST", "/api/Produto/lotes/registrar", b, ApiClient.LONG_TIMEOUT);
    }

    /** categoriaId, nome, categoriaPaiId, prazoPadraoDias, diasAlerta. */
    public JsonNode categorias() {
        return api.send("GET", "/api/Produto/lotes/categorias", null, ApiClient.LIST_TIMEOUT);
    }

    public void salvarCategoria(String categoriaId, Integer prazoPadraoDias, Integer diasAlerta) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("prazoPadraoDias", prazoPadraoDias);
        b.put("diasAlerta", diasAlerta);
        api.send("PUT", "/api/Produto/lotes/categorias/" + ApiClient.q(categoriaId), b, ApiClient.LONG_TIMEOUT);
    }
}
