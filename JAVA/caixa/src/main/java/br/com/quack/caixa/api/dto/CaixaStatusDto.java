package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Resposta de /api/Caixa/status e das operações de caixa (valores em texto pt-BR). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaixaStatusDto(String state, boolean canSell, String blockReason, String serverNow,
        Session currentSession, Session lastSession) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Session(String id, String status, String openedAt, String closedAt, String openingAmount,
            String closingAmount, String operatorId, String operatorName, long elapsedMinutes,
            String expectedCashAmount, String differenceAmount, String differenceReason,
            List<Movement> movimentos, List<Breakdown> paymentBreakdown, String closedByName, String note) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Movement(String id, String tipo, String valor, String motivo, String createdAt,
            String operatorName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Breakdown(String paymentType, String total) {
    }

    public boolean open() {
        return "aberto".equalsIgnoreCase(state);
    }
}
