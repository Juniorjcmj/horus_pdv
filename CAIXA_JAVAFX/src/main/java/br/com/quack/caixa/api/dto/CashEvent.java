package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Operação de caixa feita sem conexão e guardada na fila (CASH_OPEN, CASH_CLOSE, CASH_MOVEMENT), como os
 * eventos do outbox da web. Ao sincronizar vira POST /api/Caixa/abrir|fechar|movimento com o mesmo eventId.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CashEvent(String eventType, String eventId, String payloadHash, String clientSessionId,
        String occurredAt, String openingAmount, String closingAmount, String note, String differenceReason,
        String tipo, String valor, String motivo) {

    public static final String OPEN = "CASH_OPEN";
    public static final String CLOSE = "CASH_CLOSE";
    public static final String MOVEMENT = "CASH_MOVEMENT";

    public static CashEvent open(String eventId, String payloadHash, String sessionId, String at, String opening) {
        return new CashEvent(OPEN, eventId, payloadHash, sessionId, at, opening, null, null, null, null, null, null);
    }

    public static CashEvent close(String eventId, String payloadHash, String sessionId, String at, String closing,
            String note, String reason) {
        return new CashEvent(CLOSE, eventId, payloadHash, sessionId, at, null, closing, note, reason, null, null, null);
    }

    public static CashEvent movement(String eventId, String payloadHash, String sessionId, String at, String tipo,
            String valor, String motivo) {
        return new CashEvent(MOVEMENT, eventId, payloadHash, sessionId, at, null, null, null, null, tipo, valor,
                motivo);
    }
}
