package br.com.quack.caixa.api;

import br.com.quack.caixa.api.dto.VendaPayload;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** POST /api/HistoricoVendas. 503 com saleNumber = venda gravada (fila fiscal falhou) = sucesso. */
public class VendaApi {

    public record Result(String saleNumber, boolean emitirFiscal, BigDecimal fiadoSaldoAtual) {
    }

    private final ApiClient api;

    public VendaApi(ApiClient api) {
        this.api = api;
    }

    public Result register(VendaPayload payload) {
        try {
            JsonNode d = api.send("POST", "/api/HistoricoVendas", payload, ApiClient.SALE_TIMEOUT);
            return parse(d);
        } catch (ApiException e) {
            if (e.status() == 503 && e.data() != null && e.data().hasNonNull("saleNumber")) {
                return parse(e.data());
            }
            throw e;
        }
    }

    private static Result parse(JsonNode d) {
        if (d == null || !d.hasNonNull("saleNumber")) {
            throw new ApiException(500, "A API não devolveu o número da venda.");
        }
        BigDecimal fiado = d.hasNonNull("fiadoSaldoAtual") && d.get("fiadoSaldoAtual").isNumber()
                ? d.get("fiadoSaldoAtual").decimalValue()
                : null;
        return new Result(d.get("saleNumber").asText(), d.path("emitirFiscal").asBoolean(false), fiado);
    }
}
