package br.com.quack.caixa.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Erro de API. status 0 = rede/timeout. */
public class ApiException extends RuntimeException {
    private final int status;
    private final JsonNode data;

    public ApiException(int status, String message) {
        this(status, message, null);
    }

    public ApiException(int status, String message, JsonNode data) {
        super(message);
        this.status = status;
        this.data = data;
    }

    public int status() {
        return status;
    }

    public JsonNode data() {
        return data;
    }

    public boolean isNetwork() {
        return status == 0;
    }

    /** Falha transitória: vale reenviar depois (rede, 5xx, 408, 429). */
    public boolean isTransient() {
        return status == 0 || status >= 500 || status == 408 || status == 429;
    }
}
