package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

/** Classe de uma tabela na sincronizacao loja/nuvem (D2 da change api-java-modo-loja). */
public enum ClasseTabela {
    /** So a loja grava. Sobe por sincronizacao; edicao na nuvem vai para a quarentena e e revertida. */
    LOJA,
    /** Sobe por sincronizacao; edicao na nuvem vira comando com conferencia de base. */
    EDITAVEL,
    /** So insercao, as duas pontas criam linhas e ninguem reverte nada (AuditLog). */
    ACRESCIMO,
    /** Nao sincroniza. */
    FORA
}
