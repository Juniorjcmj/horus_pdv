package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tabelas globais (sem empresa, classe FORA) de que tabelas sincronizadas dependem por chave estrangeira. Elas nao
 * entram no tracking nem sobem; a carga inicial so as copia inteiras, antes das demais, para a loja ter o que a chave
 * estrangeira exige (hoje: {@code ModuloMercadoRegistros.ModuleId} aponta para {@code ModulosMercado}).
 */
public final class ReferenciasGlobais {

    private static final List<String> NOMES = List.of("ModulosMercado");

    private ReferenciasGlobais() {}

    /** Descritores (classe FORA, sem coluna de empresa) na ordem de carga. */
    public static List<TabelaSincronizada> tabelas() {
        return NOMES.stream().map(ReferenciasGlobais::descritor).toList();
    }

    /** Tabela da carga: do catalogo (nao FORA) ou uma referencia global. */
    public static Optional<TabelaSincronizada> daCarga(String nome) {
        if (NOMES.contains(nome)) return Optional.of(descritor(nome));
        return CatalogoSincronizacao.tabela(nome).filter(TabelaSincronizada::sincroniza);
    }

    public static boolean global(TabelaSincronizada t) {
        return t.colunaEmpresa() == null && !t.filhaSemEmpresa();
    }

    private static TabelaSincronizada descritor(String nome) {
        return new TabelaSincronizada(nome, ClasseTabela.FORA, null, null, null, Map.of(), List.of());
    }
}
