package br.com.quack.estoque.api;

import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produtos, preço, ajustes de estoque e movimentações (ProdutoController / EntradaManualController). */
public class ProdutoApi {
    /** Movimentação de estoque (EstoqueMovimentacaoModel). */
    public record Movimentacao(String id, String produtoId, String produtoNome, String produtoCodigo, String tipo,
            java.math.BigDecimal quantidade, java.math.BigDecimal estoqueAnterior, java.math.BigDecimal estoqueNovo,
            String motivo, String usuarioNome, String criadoEm) {
    }

    private final ApiClient api;

    public ProdutoApi(ApiClient api) {
        this.api = api;
    }

    public List<Produto> listar() {
        JsonNode data = api.send("GET", "/api/Produto", null, ApiClient.LIST_TIMEOUT);
        List<Produto> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                out.add(Produto.de(n));
            }
        }
        return out;
    }

    /** Cria o produto (corpo = ProdutoRequest). Devolve o produto gravado. */
    public Produto criar(ObjectNode body) {
        return Produto.de(api.send("POST", "/api/Produto", body, ApiClient.CASH_TIMEOUT));
    }

    public Produto atualizar(String id, ObjectNode body) {
        return Produto.de(api.send("PUT", "/api/Produto/" + id, body, ApiClient.CASH_TIMEOUT));
    }

    /** Altera só o preço de venda; versaoPreco evita sobrescrever uma alteração feita por outra pessoa. */
    public Produto atualizarPreco(String id, String precoVenda, String versaoConsultada) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("precoVenda", precoVenda);
        body.put("versaoConsultada", versaoConsultada == null ? "" : versaoConsultada);
        JsonNode data = api.send("PATCH", "/api/Produto/" + id + "/preco-venda", body, ApiClient.CASH_TIMEOUT);
        return data == null ? null : Produto.de(data);
    }

    /** tipo: entrada | saida | uso_interno | perda. */
    public void ajustar(String id, String tipo, java.math.BigDecimal quantidade, String motivo, String dataValidade,
            String numeroLote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tipo", tipo);
        body.put("quantidade", quantidade);
        body.put("motivo", motivo);
        if (dataValidade != null && !dataValidade.isBlank()) {
            body.put("dataValidade", dataValidade);
        }
        if (numeroLote != null && !numeroLote.isBlank()) {
            body.put("numeroLote", numeroLote);
        }
        api.send("POST", "/api/Produto/" + id + "/ajuste-estoque", body, ApiClient.CASH_TIMEOUT);
    }

    public List<Movimentacao> movimentacoes(String produtoId) {
        return lerMovs(api.send("GET", "/api/Produto/" + produtoId + "/movimentacoes", null, ApiClient.LIST_TIMEOUT));
    }

    public List<Movimentacao> movimentacoes(String tipo, int limite) {
        String q = "?limit=" + limite + (tipo == null || tipo.isBlank() ? "" : "&tipo=" + ApiClient.q(tipo));
        return lerMovs(api.send("GET", "/api/Produto/movimentacoes" + q, null, ApiClient.LIST_TIMEOUT));
    }

    private static List<Movimentacao> lerMovs(JsonNode data) {
        if (data == null) {
            return List.of();
        }
        try {
            return JsonFiles.MAPPER.readerFor(JsonFiles.listOf(Movimentacao.class)).readValue(data);
        } catch (java.io.IOException e) {
            throw new ApiException(0, "Resposta inesperada da API: " + e.getMessage());
        }
    }

    /** Entrada manual (cupom, recibo, sem documento). OperacaoId torna o envio idempotente. */
    public JsonNode entradaManual(ObjectNode body) {
        return api.send("POST", "/api/Produto/entrada-manual", body, ApiClient.LONG_TIMEOUT);
    }
}
