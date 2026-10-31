package br.com.quack.caixa.domain;

/** Erro de regra de negócio com mensagem pronta para o operador. */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }
}
