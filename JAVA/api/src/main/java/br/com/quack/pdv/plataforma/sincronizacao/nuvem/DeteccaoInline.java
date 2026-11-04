package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.util.Map;
import java.util.Set;

/**
 * Ponto de extensao do aplicador (D6): deteccao inline de edicoes da nuvem e espelho. {@code chave} e a chave
 * primaria serializada (valores separados por {@code |}). Roda dentro da transacao do lote.
 */
public interface DeteccaoInline {

    /**
     * Antes de escrever a linha. Se a nuvem a editou depois do ultimo ciclo do detector, cria o comando agora. Devolve
     * as colunas com comando pendente, que o aplicador NAO escreve (a nuvem continua mostrando o valor novo).
     */
    Set<String> antes(String companyId, TabelaSincronizada tabela, String chave, boolean existe);

    /**
     * Como {@link #antes}, num lote de retrato completo (D5): a linha da nuvem que sera sobrescrita e guardada na
     * quarentena com origem {@code ressincronizacao} (D7).
     */
    default Set<String> antesDaRessincronizacao(String companyId, TabelaSincronizada tabela, String chave, boolean existe) {
        return antes(companyId, tabela, chave, existe);
    }

    /**
     * Depois de escrever a linha: atualiza o espelho. {@code textosDoLote} sao os valores da loja (usados nas colunas
     * bloqueadas, que a nuvem nao escreveu).
     */
    void depois(String companyId, TabelaSincronizada tabela, String chave, Map<String, String> textosDoLote,
                Set<String> bloqueadas);

    /** Depois de excluir a linha. */
    void depoisDeExcluir(String companyId, TabelaSincronizada tabela, String chave);

    /** A nuvem ja viu esta linha vinda da loja (tem espelho)? Usado nas sobras da ressincronizacao completa. */
    boolean conhecida(String companyId, TabelaSincronizada tabela, String chave);
}
