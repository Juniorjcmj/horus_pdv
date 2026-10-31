package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Entrada por NF-e e consulta/estorno de notas de entrada (NfeImportController). */
public class EntradaApi {
    private final ApiClient api;

    public EntradaApi(ApiClient api) {
        this.api = api;
    }

    /** Lê o XML (Base64) e devolve fornecedor + itens, já vinculando por GTIN aos produtos existentes. */
    public JsonNode previewXml(String xmlBase64) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("xmlBase64", xmlBase64);
        return api.send("POST", "/api/NfeImport/preview", body, ApiClient.LONG_TIMEOUT);
    }

    /** Baixa a NF-e direto da SEFAZ pela chave de acesso (44 dígitos). */
    public JsonNode buscarSefaz(String chave) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chaveAcesso", chave);
        return api.send("POST", "/api/NfeImport/buscar-sefaz", body, ApiClient.LONG_TIMEOUT);
    }

    public JsonNode confirmar(ObjectNode body) {
        return api.send("POST", "/api/NfeImport/confirmar", body, ApiClient.LONG_TIMEOUT);
    }

    public JsonNode listarNotas(String busca, int pagina, int tamanho) {
        String q = "?pagina=" + pagina + "&tamanhoPagina=" + tamanho
                + (busca == null || busca.isBlank() ? "" : "&busca=" + ApiClient.q(busca));
        return api.send("GET", "/api/NfeImport/notas-entrada" + q, null, ApiClient.LIST_TIMEOUT);
    }

    public JsonNode obterNota(String id) {
        return api.send("GET", "/api/NfeImport/notas-entrada/" + id, null, ApiClient.LIST_TIMEOUT);
    }

    public byte[] baixarXml(String id) {
        return api.getBytes("/api/NfeImport/notas-entrada/" + id + "/xml", ApiClient.LIST_TIMEOUT);
    }

    public void estornar(String id, String justificativa) {
        api.send("DELETE", "/api/NfeImport/notas-entrada/" + id + "?justificativa=" + ApiClient.q(justificativa), null,
                ApiClient.LONG_TIMEOUT);
    }
}
