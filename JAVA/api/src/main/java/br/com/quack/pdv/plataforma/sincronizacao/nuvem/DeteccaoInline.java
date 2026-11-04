package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.util.Map;

/**
 * Ponto de extensao do aplicador (D6, grupo 4): chamado antes e depois de escrever cada linha do lote. O grupo 4
 * liga aqui a deteccao inline de edicoes da nuvem e o espelho. Sem implementacao, nada acontece.
 */
public interface DeteccaoInline {

    /** Antes de escrever a linha. {@code existe}: a linha ja existe na nuvem. */
    default void antes(String companyId, TabelaSincronizada tabela, Map<String, Object> linha, boolean existe) {}

    /** Depois de escrever a linha. */
    default void depois(String companyId, TabelaSincronizada tabela, Map<String, Object> linha) {}
}
