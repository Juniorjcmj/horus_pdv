package br.com.quack.pdv.shared;

import org.springframework.http.HttpStatus;

/** Falha de regra de negocio: vira o envelope de erro com o status indicado. */
public class NegocioException extends RuntimeException {

    private final HttpStatus status;
    private final String details;
    private final transient Object data;

    public NegocioException(HttpStatus status, String message) {
        this(status, message, null, null);
    }

    public NegocioException(HttpStatus status, String message, String details, Object data) {
        super(message);
        this.status = status;
        this.details = details;
        this.data = data;
    }

    public HttpStatus getStatus() { return status; }
    public String getDetails() { return details; }
    public Object getData() { return data; }
}
