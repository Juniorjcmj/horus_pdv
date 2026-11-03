package br.com.quack.pdv.estoque.registrador;

/** Tipos de movimentacao aceitos (coluna EstoqueMovimentacoes.Tipo). */
public final class EstoqueTipos {

    public static final String ENTRADA = "entrada";
    public static final String SAIDA = "saida";
    public static final String SAIDA_VENDA = "saida_venda";
    public static final String DEVOLUCAO = "devolucao";
    public static final String USO_INTERNO = "uso_interno";
    public static final String PERDA = "perda";
    public static final String INVENTARIO = "inventario";
    public static final String TRANSFERENCIA = "transferencia";

    private EstoqueTipos() {}
}
