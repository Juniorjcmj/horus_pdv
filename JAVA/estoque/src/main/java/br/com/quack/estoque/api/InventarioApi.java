package br.com.quack.estoque.api;

import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inventário (InventarioController): abrir, contar, anular, recontar, divergências, aplicar, cancelar, trilha. */
public class InventarioApi {
    /** Linha de /itens (divergência de um produto). Valores monetários vêm nulos para o estoquista. */
    public record Item(String produtoId, String codigo, String nome, String unidade, BigDecimal custo,
            BigDecimal saldoNa1aContagem, BigDecimal contado, BigDecimal diferenca, BigDecimal valorDiferenca,
            boolean recontar, String primeiraContagemEm, String ultimaContagemEm, BigDecimal saldoAntes,
            BigDecimal delta, BigDecimal saldoDepois, BigDecimal valorDelta) {
    }

    /** Lançamento de contagem (lista "últimos lançamentos" e trilha). */
    public record Contagem(String id, String produtoId, String codigo, String nome, BigDecimal quantidade,
            String local, String usuario, String terminal, BigDecimal saldoSistema, String criadoEm,
            String anuladaPor, String anuladaEm, String anuladaMotivo, String usuarioId) {
        public boolean anulada() {
            return anuladaEm != null && !anuladaEm.isBlank();
        }
    }

    /** Resposta de uma contagem registrada. */
    public record Registro(String contagemId, String produtoId, String codigo, String nome, String unidade,
            BigDecimal quantidade, BigDecimal totalContado, String local) {
    }

    private final ApiClient api;

    public InventarioApi(ApiClient api) {
        this.api = api;
    }

    public JsonNode abrir(String descricao, String escopo, List<String> categoriaIds, List<String> produtoIds,
            String terminal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("descricao", descricao);
        body.put("escopo", escopo);
        if (categoriaIds != null) {
            body.put("categoriaIds", categoriaIds);
        }
        if (produtoIds != null) {
            body.put("produtoIds", produtoIds);
        }
        body.put("terminal", terminal);
        return api.send("POST", "/api/Inventario", body, ApiClient.LONG_TIMEOUT);
    }

    public JsonNode listar(String status) {
        return api.send("GET", "/api/Inventario" + (status == null || status.isBlank() ? "" : "?status=" + status),
                null, ApiClient.LIST_TIMEOUT);
    }

    public JsonNode obter(String id) {
        return api.send("GET", "/api/Inventario/" + id, null, ApiClient.LIST_TIMEOUT);
    }

    public List<Item> itens(String id, String filtro) {
        return lista("/api/Inventario/" + id + "/itens" + (filtro == null || filtro.isBlank() ? "" : "?filtro=" + filtro),
                Item.class);
    }

    public List<Contagem> contagens(String id, boolean todas, String produtoId, int limite) {
        String q = "?limite=" + limite + (todas ? "&todas=true" : "")
                + (produtoId == null ? "" : "&produtoId=" + ApiClient.q(produtoId));
        return lista("/api/Inventario/" + id + "/contagens" + q, Contagem.class);
    }

    /** Registra a contagem. Informe produtoId (catálogo do app) ou codigo (código interno/GTIN). */
    public Registro contar(String id, String produtoId, String codigo, BigDecimal quantidade, String local,
            String terminal) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (produtoId != null) {
            body.put("produtoId", produtoId);
        }
        if (codigo != null) {
            body.put("codigo", codigo);
        }
        body.put("quantidade", quantidade);
        body.put("local", local);
        body.put("terminal", terminal);
        return api.send("POST", "/api/Inventario/" + id + "/contagens", body, ApiClient.CASH_TIMEOUT, Registro.class);
    }

    public void anular(String id, String contagemId, String motivo) {
        api.send("POST", "/api/Inventario/" + id + "/contagens/" + contagemId + "/anular", Map.of("motivo", motivo),
                ApiClient.CASH_TIMEOUT);
    }

    public void recontar(String id, List<String> produtoIds) {
        api.send("POST", "/api/Inventario/" + id + "/recontar", Map.of("produtoIds", produtoIds),
                ApiClient.CASH_TIMEOUT);
    }

    public JsonNode aplicar(String id, String supervisorId, String senha, String justificativa, boolean zerar,
            String terminal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supervisorId", supervisorId);
        body.put("supervisorPassword", senha);
        body.put("justificativa", justificativa);
        body.put("zerarNaoContados", zerar);
        body.put("terminal", terminal);
        return api.send("POST", "/api/Inventario/" + id + "/aplicar", body, ApiClient.LONG_TIMEOUT);
    }

    public void cancelar(String id, String motivo, String terminal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("motivo", motivo);
        body.put("terminal", terminal);
        api.send("POST", "/api/Inventario/" + id + "/cancelar", body, ApiClient.CASH_TIMEOUT);
    }

    public JsonNode trilha(String id) {
        return api.send("GET", "/api/Inventario/" + id + "/trilha", null, ApiClient.LONG_TIMEOUT);
    }

    private <T> List<T> lista(String path, Class<T> tipo) {
        JsonNode data = api.send("GET", path, null, ApiClient.LIST_TIMEOUT);
        if (data == null || !data.isArray()) {
            return new ArrayList<>();
        }
        try {
            return JsonFiles.MAPPER.readerFor(JsonFiles.listOf(tipo)).readValue(data);
        } catch (java.io.IOException e) {
            throw new ApiException(0, "Resposta inesperada da API: " + e.getMessage());
        }
    }
}
