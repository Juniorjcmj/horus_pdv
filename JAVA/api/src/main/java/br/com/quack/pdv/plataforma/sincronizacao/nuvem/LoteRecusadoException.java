package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

/** O lote e invalido (escopo, tabela ou coluna desconhecida): nada e aplicado e a loja pausa. */
public class LoteRecusadoException extends RuntimeException {

    public LoteRecusadoException(String mensagem) {
        super(mensagem);
    }
}
