package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

/** Classe de uma coluna em tabela LOJA ou EDITAVEL (D2). */
public enum ClasseColuna {
    /** Padrao na tabela LOJA; em tabela EDITAVEL, coluna que so operacao da loja muda. */
    LOJA,
    /** Padrao na tabela EDITAVEL. */
    EDITAVEL,
    /** A nuvem e a autora: a loja so a escreve na insercao; mudanca na nuvem desce como comando forcado. */
    NUVEM,
    /** Como NUVEM, mas o valor e recifrado com a chave da loja. */
    CIFRADA,
    /** Cada ponta tem o seu valor; so e copiada na insercao. */
    LOCAL
}
