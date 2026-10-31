package br.com.quack.caixa.api;

import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.CashEvent;
import br.com.quack.caixa.domain.PayloadHash;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * /api/Caixa/*. eventId e payloadHash são gerados ANTES da tentativa online e reaproveitados na fila: se o
 * servidor gravou mas a resposta não chegou, o reenvio vira repetição idempotente em vez de duplicar.
 */
public class CaixaApi {
    private final ApiClient api;

    public CaixaApi(ApiClient api) {
        this.api = api;
    }

    public CaixaStatusDto status() {
        return api.send("GET", "/api/Caixa/status", null, ApiClient.READ_TIMEOUT, CaixaStatusDto.class);
    }

    public static String newEventId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public CaixaStatusDto abrir(String openingAmount) {
        return abrir(openingAmount, newEventId("ev-cxopen"), null, null);
    }

    /** clientSessionId/openedAt só na abertura feita offline (a nuvem cria a MESMA sessão, no horário real). */
    public CaixaStatusDto abrir(String openingAmount, String eventId, String clientSessionId, String openedAt) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("openingAmount", openingAmount);
        b.put("eventId", eventId);
        b.put("payloadHash", PayloadHash.cashOpen(openingAmount));
        put(b, "clientSessionId", clientSessionId);
        put(b, "openedAt", openedAt);
        return api.send("POST", "/api/Caixa/abrir", b, ApiClient.CASH_TIMEOUT, CaixaStatusDto.class);
    }

    public CaixaStatusDto fechar(String closingAmount, String note, String differenceReason) {
        return fechar(closingAmount, note, differenceReason, newEventId("ev-cxclose"), null, null);
    }

    /** clientSessionId/closedAt só no fechamento offline: a nuvem fecha SOMENTE aquela sessão. */
    public CaixaStatusDto fechar(String closingAmount, String note, String differenceReason, String eventId,
            String clientSessionId, String closedAt) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("closingAmount", closingAmount);
        b.put("note", note == null ? "" : note);
        b.put("differenceReason", differenceReason == null || differenceReason.isBlank() ? null : differenceReason);
        b.put("eventId", eventId);
        b.put("payloadHash", PayloadHash.cashClose(closingAmount, note, differenceReason));
        put(b, "clientSessionId", clientSessionId);
        put(b, "closedAt", closedAt);
        return api.send("POST", "/api/Caixa/fechar", b, ApiClient.CASH_TIMEOUT, CaixaStatusDto.class);
    }

    /** tipo: "Sangria" ou "Reforco" (valor esperado pela API). */
    public CaixaStatusDto movimento(String tipo, String valor, String motivo) {
        return movimento(tipo, valor, motivo, newEventId("ev-cxmov"));
    }

    public CaixaStatusDto movimento(String tipo, String valor, String motivo, String eventId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tipo", tipo);
        b.put("valor", valor);
        b.put("motivo", motivo);
        b.put("eventId", eventId);
        b.put("payloadHash", PayloadHash.cashMovement(tipo, valor, motivo));
        return api.send("POST", "/api/Caixa/movimento", b, ApiClient.CASH_TIMEOUT, CaixaStatusDto.class);
    }

    /** Reenvio de uma operação de caixa feita offline (SyncWorker). */
    public CaixaStatusDto send(CashEvent e) {
        return switch (e.eventType()) {
            case CashEvent.OPEN -> abrir(e.openingAmount(), e.eventId(), e.clientSessionId(), e.occurredAt());
            case CashEvent.CLOSE -> fechar(e.closingAmount(), e.note(), e.differenceReason(), e.eventId(),
                    e.clientSessionId(), e.occurredAt());
            case CashEvent.MOVEMENT -> movimento(e.tipo(), e.valor(), e.motivo(), e.eventId());
            default -> throw new ApiException(400, "Evento de caixa desconhecido: " + e.eventType());
        };
    }

    private static void put(Map<String, Object> b, String k, String v) {
        if (v != null && !v.isBlank()) {
            b.put(k, v);
        }
    }
}
