package br.com.quack.caixa.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Alteração de preço com senha de gerente (mesmas rotas do caixa web):
 * GET /api/Usuario/supervisores e POST /api/HistoricoVendas/autorizar-preco. O servidor valida a senha e
 * devolve uma autorização de uso único (vale 2 horas) que acompanha o item na venda.
 */
public class PriceAuthApi {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Supervisor(String id, String name, String role) {
        /** Texto da lista: "Nome (Gerente)" / "Nome (Administrador)". */
        @Override
        public String toString() {
            return name + " (" + ("administrador".equalsIgnoreCase(role) ? "Administrador" : "Gerente") + ")";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Authorization(String autorizacaoId, String supervisorNome, BigDecimal precoTabela,
            BigDecimal precoNovo, String expiraEm) {
    }

    private final ApiClient api;

    public PriceAuthApi(ApiClient api) {
        this.api = api;
    }

    public List<Supervisor> supervisors() {
        List<Supervisor> list = api.send("GET", "/api/Usuario/supervisores", null, ApiClient.READ_TIMEOUT,
                com.fasterxml.jackson.databind.type.TypeFactory.defaultInstance()
                        .constructCollectionType(List.class, Supervisor.class));
        return list == null ? List.of() : list;
    }

    public Authorization authorize(String supervisorId, String supervisorPassword, String productCode,
            BigDecimal precoNovo, String motivo) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supervisorId", supervisorId);
        b.put("supervisorPassword", supervisorPassword);
        b.put("productCode", productCode);
        b.put("precoNovo", precoNovo);
        if (motivo != null && !motivo.isBlank()) {
            b.put("motivo", motivo.trim());
        }
        return api.send("POST", "/api/HistoricoVendas/autorizar-preco", b, ApiClient.CASH_TIMEOUT,
                Authorization.class);
    }
}
