package br.com.quack.pdv.plataforma.sincronizacao.envio;

/** Classifica o resultado de um envio (D4): temporario tenta de novo; definitivo pausa; versao recomeca do esperado. */
public final class ClassificadorErro {

    public enum Falha { TEMPORARIA, DEFINITIVA, VERSAO }

    private ClassificadorErro() {}

    /** Status HTTP de erro (nao 2xx). */
    public static Falha porStatus(int status) {
        if (status == 409) return Falha.VERSAO;
        if (status == 408 || status == 429 || status >= 500) return Falha.TEMPORARIA;
        return Falha.DEFINITIVA;
    }

    /** Falha de rede, tempo esgotado e afins: sempre temporaria. */
    public static Falha porExcecao(Throwable t) {
        return Falha.TEMPORARIA;
    }
}
