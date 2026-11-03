package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.VendaPayload;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Resultado de uma venda confirmada (base do cupom). saleNumber = número da API ou referência OFF-.
 * units = unidade de cada produto (código -> "UN"/"KG"); fiadoSaldo = saldo devedor após a venda (só no fiado).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleOutcome(VendaPayload payload, String saleNumber, boolean offline, boolean emitirFiscal,
        Map<String, String> units, BigDecimal fiadoSaldo) {

    public SaleOutcome(VendaPayload payload, String saleNumber, boolean offline, boolean emitirFiscal) {
        this(payload, saleNumber, offline, emitirFiscal, Map.of(), null);
    }

    public SaleOutcome withExtras(Map<String, String> units, BigDecimal fiadoSaldo) {
        return new SaleOutcome(payload, saleNumber, offline, emitirFiscal, units, fiadoSaldo);
    }

    public String unitOf(String productCode) {
        return units == null ? "" : units.getOrDefault(productCode, "");
    }

    public boolean hasFiado() {
        return payload.payments().stream().anyMatch(p -> "fiado".equalsIgnoreCase(p.paymentType()));
    }
}
