package br.com.quack.estoque.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Categorias (2 níveis) e fornecedores (CategoriaController / FornecedorController). */
public class CadastroApi {
    public record Categoria(String id, String nome, String categoriaPaiId, String categoriaPaiNome, boolean ativa,
            int quantidadeProdutos) {
        /** "Pai > Filha" para exibição. */
        public String caminho() {
            return categoriaPaiNome == null || categoriaPaiNome.isBlank() ? nome : categoriaPaiNome + " > " + nome;
        }
    }

    public record Fornecedor(String id, String companyName, String fantasyName, String cnpj, String telephone,
            String email, String city, String state) {
        public String nome() {
            return fantasyName != null && !fantasyName.isBlank() ? fantasyName : companyName;
        }
    }

    private final ApiClient api;

    public CadastroApi(ApiClient api) {
        this.api = api;
    }

    /** Lista plana (departamentos e subcategorias). */
    public List<Categoria> categorias() {
        JsonNode data = api.send("GET", "/api/Categoria?plana=true", null, ApiClient.LIST_TIMEOUT);
        List<Categoria> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                out.add(new Categoria(txt(n, "id"), txt(n, "nome"), txt(n, "categoriaPaiId"), txt(n, "categoriaPaiNome"),
                        n.path("ativa").asBoolean(true), n.path("quantidadeProdutos").asInt(0)));
            }
        }
        return out;
    }

    public String salvarCategoria(String id, String nome, String categoriaPaiId, boolean ativa) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nome", nome);
        body.put("categoriaPaiId", categoriaPaiId == null || categoriaPaiId.isBlank() ? null : categoriaPaiId);
        body.put("ativa", ativa);
        if (id == null || id.isBlank()) {
            JsonNode d = api.send("POST", "/api/Categoria", body, ApiClient.CASH_TIMEOUT);
            return d == null ? "" : txt(d, "id");
        }
        body.put("id", id);
        api.send("PUT", "/api/Categoria/" + id, body, ApiClient.CASH_TIMEOUT);
        return id;
    }

    public List<Fornecedor> fornecedores() {
        JsonNode data = api.send("GET", "/api/Fornecedor", null, ApiClient.LIST_TIMEOUT);
        List<Fornecedor> out = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode n : data) {
                out.add(lerFornecedor(n));
            }
        }
        return out;
    }

    public Fornecedor salvarFornecedor(String id, Map<String, String> campos) {
        JsonNode data = id == null || id.isBlank()
                ? api.send("POST", "/api/Fornecedor", campos, ApiClient.CASH_TIMEOUT)
                : api.send("PUT", "/api/Fornecedor/" + id, campos, ApiClient.CASH_TIMEOUT);
        return data == null ? null : lerFornecedor(data);
    }

    static Fornecedor lerFornecedor(JsonNode n) {
        return new Fornecedor(txt(n, "id"), txt(n, "companyName"), txt(n, "fantasyName"), txt(n, "cnpj"),
                txt(n, "telephone"), txt(n, "email"), txt(n, "city"), txt(n, "state"));
    }

    static String txt(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    /** Corpo vazio reutilizável. */
    public static ObjectNode novoObjeto() {
        return br.com.quack.estoque.persistence.JsonFiles.MAPPER.createObjectNode();
    }
}
