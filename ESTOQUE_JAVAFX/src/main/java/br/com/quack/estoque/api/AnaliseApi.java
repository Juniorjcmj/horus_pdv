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

    /** Curva ABC com filtro opcional de classe (A/B/C) e de FSN (F/S/N) para a tabela (a matriz clicável). */
    public JsonNode abc(FiltrosAnalise filtros, String classe, String fsn) {
        String extra = (classe == null || classe.isBlank() ? "" : "&classe=" + ApiClient.q(classe))
                + (fsn == null || fsn.isBlank() ? "" : "&fsn=" + ApiClient.q(fsn));
        return api.send("GET", "/api/Estoque/analise/abc" + filtros.query() + extra, null, ApiClient.LONG_TIMEOUT);
    }

    /** Reposição (categoria e fornecedor dos filtros; o período vem da janela de consumo da configuração). */
    public JsonNode reposicao(FiltrosAnalise filtros) {
        return api.send("GET", "/api/Estoque/analise/reposicao" + filtros.query(), null, ApiClient.LONG_TIMEOUT);
    }

    /** Cria um rascunho de pedido por fornecedor. Devolve {pedidos[], ignorados[]}. */
    public JsonNode gerarPedidos(java.util.Map<String, java.math.BigDecimal> quantidadePorProduto, String observacao) {
        java.util.List<java.util.Map<String, Object>> itens = new java.util.ArrayList<>();
        quantidadePorProduto.forEach((id, qtd) -> itens.add(java.util.Map.of("produtoId", id, "quantidade", qtd)));
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("itens", itens);
        body.put("observacao", observacao);
        return api.send("POST", "/api/Estoque/analise/reposicao/pedidos", body, ApiClient.LONG_TIMEOUT);
    }

    /** Detalhe de um produto: {dados:{produto, kpis, series}, ...}. */
    public JsonNode produto(String produtoId, FiltrosAnalise filtros) {
        return bloco("produto/" + ApiClient.q(produtoId), filtros);
    }

    /** Extrato paginado do produto no período: {total, pagina, paginas, itens[]}. */
    public JsonNode extrato(String produtoId, FiltrosAnalise filtros, int pagina, int tamanho) {
        return api.send("GET", "/api/Estoque/analise/produto/" + ApiClient.q(produtoId) + "/extrato" + filtros.query()
                + "&pagina=" + pagina + "&tamanho=" + tamanho, null, ApiClient.LONG_TIMEOUT);
    }

    /** Lista do dia da contagem cíclica: {itensPorDia, periodicidade, itens[]}. */
    public JsonNode contagemCiclica() {
        return api.send("GET", "/api/Estoque/analise/contagem-ciclica", null, ApiClient.LONG_TIMEOUT);
    }

    /** Configurações da análise: {atual, padrao, podeEditar}. */
    public JsonNode config() {
        return api.send("GET", "/api/Estoque/analise/config", null, ApiClient.LIST_TIMEOUT);
    }

    /** Salva as configurações (gerente/administrador). Devolve o mesmo formato de {@link #config()}. */
    public JsonNode salvarConfig(java.util.Map<String, Object> corpo) {
        return api.send("PUT", "/api/Estoque/analise/config", corpo, ApiClient.LONG_TIMEOUT);
    }

    /** Volta aos padrões (apaga a configuração da empresa). */
    public JsonNode restaurarConfig() {
        return api.send("DELETE", "/api/Estoque/analise/config", null, ApiClient.LONG_TIMEOUT);
    }
}
