package br.com.quack.pdv.plataforma.sincronizacao.envio;

import java.io.IOException;

/** Transporte loja -> nuvem (D9). A loja so inicia conexoes. */
public interface ClienteNuvem {

    /** Resposta da nuvem: status HTTP e o aplicadoAte (ou o esperado, no 409) quando vier no corpo. */
    record Resposta(int status, Long aplicadoAte, boolean aplicado, String mensagem) {}

    /** Envia uma parte (JSON gzip). Lanca IOException em falha de rede ou tempo esgotado. */
    Resposta enviarParte(String urlBase, String token, byte[] gzip) throws IOException;
}
