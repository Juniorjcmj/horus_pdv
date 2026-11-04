package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

/** Servidor de loja autenticado pelo token (D9): id e empresa dele. */
public record ServidorLoja(String id, String companyId) {

    public static final String ATRIBUTO = "quack.servidorLoja";
}
