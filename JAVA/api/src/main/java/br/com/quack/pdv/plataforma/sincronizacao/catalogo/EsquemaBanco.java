package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Visao do esquema do banco usada pelas guardas e pela ordem do catalogo. */
public interface EsquemaBanco {

    /** Tabela -> colunas (nomes exatos). */
    Map<String, Set<String>> colunas();

    /** Tabela -> colunas da chave primaria (ausente ou vazia = sem chave). */
    Map<String, List<String>> chavesPrimarias();

    /** Pares {filha, mae} das chaves estrangeiras entre tabelas distintas. */
    List<String[]> chavesEstrangeiras();
}
