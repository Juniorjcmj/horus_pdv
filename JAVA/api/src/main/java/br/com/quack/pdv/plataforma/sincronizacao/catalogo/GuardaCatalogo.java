package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Guardas do catalogo (D2): devolve a lista de problemas; vazia = catalogo coerente com o esquema. */
public final class GuardaCatalogo {

    private GuardaCatalogo() {}

    public static List<String> verificar(Map<String, TabelaSincronizada> catalogo, List<String> ordem, EsquemaBanco esquema) {
        List<String> problemas = new ArrayList<>();
        Map<String, Set<String>> colunas = esquema.colunas();
        Map<String, List<String>> pks = esquema.chavesPrimarias();

        for (String tabela : colunas.keySet()) {
            if (!catalogo.containsKey(tabela) && !CatalogoSincronizacao.ehReservadaFora(tabela)) {
                problemas.add("Tabela do esquema sem classificacao no catalogo: " + tabela);
            }
        }
        for (TabelaSincronizada t : catalogo.values()) {
            Set<String> cols = colunas.get(t.nome());
            if (cols == null) {
                problemas.add("Tabela classificada que nao existe no esquema: " + t.nome());
                continue;
            }
            for (String c : t.excecoes().keySet()) exigirColuna(problemas, t, cols, c);
            for (var r : t.regras()) exigirColuna(problemas, t, cols, r.coluna());
            for (var a : t.aceites()) {
                exigirColuna(problemas, t, cols, a.colunaGatilho());
                for (String c : a.colunas()) exigirColuna(problemas, t, cols, c);
            }
            if (!t.sincroniza()) continue;
            if (pks.getOrDefault(t.nome(), List.of()).isEmpty()) {
                problemas.add("Tabela sincronizada sem chave primaria: " + t.nome());
            }
            verificarEscopo(problemas, catalogo, t, cols);
            if (!ordem.contains(t.nome())) problemas.add("Tabela sincronizada fora da ordem de aplicacao: " + t.nome());
        }
        for (String n : ordem) {
            TabelaSincronizada t = catalogo.get(n);
            if (t == null || !t.sincroniza()) problemas.add("Ordem de aplicacao cita tabela nao sincronizada: " + n);
        }
        if (new HashSet<>(ordem).size() != ordem.size()) problemas.add("Ordem de aplicacao com tabela repetida");
        verificarOrdem(problemas, ordem, esquema.chavesEstrangeiras());
        return problemas;
    }

    private static void exigirColuna(List<String> problemas, TabelaSincronizada t, Set<String> cols, String coluna) {
        if (!cols.contains(coluna)) problemas.add("Coluna classificada que nao existe: " + t.nome() + "." + coluna);
    }

    private static void verificarEscopo(List<String> problemas, Map<String, TabelaSincronizada> catalogo,
                                        TabelaSincronizada t, Set<String> cols) {
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = catalogo.get(t.mae());
            if (mae == null || !mae.sincroniza() || mae.filhaSemEmpresa()) {
                problemas.add("Mae invalida para a filha " + t.nome() + ": " + t.mae());
            }
            exigirColuna(problemas, t, cols, t.colunaLigacao());
        } else if (t.colunaEmpresa() == null) {
            problemas.add("Tabela sincronizada sem coluna de empresa nem mae: " + t.nome());
        } else {
            exigirColuna(problemas, t, cols, t.colunaEmpresa());
        }
    }

    private static void verificarOrdem(List<String> problemas, List<String> ordem, List<String[]> fks) {
        for (String[] fk : fks) {
            int filha = ordem.indexOf(fk[0]);
            int mae = ordem.indexOf(fk[1]);
            if (filha >= 0 && mae >= 0 && mae > filha) {
                problemas.add("Ordem viola a chave estrangeira: " + fk[0] + " -> " + fk[1]);
            }
        }
    }
}
