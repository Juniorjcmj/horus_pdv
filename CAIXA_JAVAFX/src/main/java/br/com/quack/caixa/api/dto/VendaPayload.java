package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/** Corpo de POST /api/HistoricoVendas (mesmo contrato do caixa web). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VendaPayload(
        String clientSaleId,
        String eventId,
        String eventType,
        String occurredAt,
        String offlineReference,
        String payloadHash,
        boolean reenvioOffline,
        String customerName,
        String customerCpf,
        String paymentType,
        String totalAmount,
        String operatorName,
        String operatorId,
        List<Item> items,
        List<Payment> payments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    /** autorizacaoPrecoId: autorização do gerente para o preço alterado deste item (null = preço cadastrado). */
    public record Item(String productCode, String productName, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal desconto, BigDecimal itemTotal, String promocaoId, String autorizacaoPrecoId) {
        public Item(String productCode, String productName, BigDecimal quantity, BigDecimal unitPrice,
                BigDecimal desconto, BigDecimal itemTotal, String promocaoId) {
            this(productCode, productName, quantity, unitPrice, desconto, itemTotal, promocaoId, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payment(String paymentType, BigDecimal amount, BigDecimal cashGiven, BigDecimal changeAmount) {
    }

    public VendaPayload withHash(String hash) {
        return new VendaPayload(clientSaleId, eventId, eventType, occurredAt, offlineReference, hash,
                reenvioOffline, customerName, customerCpf, paymentType, totalAmount, operatorName, operatorId,
                items, payments);
    }

    public VendaPayload asOfflineResend() {
        return new VendaPayload(clientSaleId, eventId, eventType, occurredAt, offlineReference, payloadHash, true,
                customerName, customerCpf, paymentType, totalAmount, operatorName, operatorId, items, payments);
    }
}
