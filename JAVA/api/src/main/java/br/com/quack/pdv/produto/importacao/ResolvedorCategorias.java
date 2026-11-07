package br.com.quack.pdv.produto.importacao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Resolve Classe > Grupo > Sub-Grupo em categorias encadeadas (I5). Compara por (pai, nome normalizado) contra as
 * categorias que ja existem e acumula as que faltam, na ordem Classe, Grupo, Sub-Grupo (pais antes dos filhos).
 */
public final class ResolvedorCategorias {

    public record CategoriaExistente(String id, String nome, String paiId, int ordem) {}

    public record CategoriaNova(String id, String nome, String paiId, int ordem) {}

    private static final String RAIZ = "";

    private final Map<String, String> idPorPaiENome = new HashMap<>();
    private final List<CategoriaNova> novas = new ArrayList<>();
    private int proximaOrdem;

    public ResolvedorCategorias(List<CategoriaExistente> existentes) {
        int maior = 0;
        for (CategoriaExistente c : existentes) {
            idPorPaiENome.putIfAbsent(chave(c.paiId(), c.nome()), c.id());
            maior = Math.max(maior, c.ordem());
        }
        proximaOrdem = maior + 1;
    }

    /**
     * Devolve o id da categoria do nivel mais profundo encadeado (ou null sem Classe). Grupo sem Classe ou Sub-Grupo sem
     * Grupo: o produto fica no ultimo nivel encadeado e o aviso e emitido.
     */
    public String resolver(String classe, String grupo, String subGrupo, Consumer<String> aviso) {
        if (classe == null) {
            if (grupo != null || subGrupo != null) {
                aviso.accept("Grupo/Sub-Grupo sem Classe: produto ficou sem categoria");
            }
            return null;
        }
        String id = obter(RAIZ, classe);
        if (grupo == null) {
            if (subGrupo != null) {
                aviso.accept("Sub-Grupo sem Grupo: produto ficou na Classe '" + classe + "'");
            }
            return id;
        }
        id = obter(id, grupo);
        return subGrupo == null ? id : obter(id, subGrupo);
    }

    private String obter(String paiId, String nome) {
        String chave = chave(paiId, nome);
        String existente = idPorPaiENome.get(chave);
        if (existente != null) {
            return existente;
        }
        String id = "cat-" + UUID.randomUUID().toString().replace("-", "");
        novas.add(new CategoriaNova(id, nome, paiId.isEmpty() ? null : paiId, proximaOrdem++));
        idPorPaiENome.put(chave, id);
        return id;
    }

    private static String chave(String paiId, String nome) {
        return (paiId == null ? RAIZ : paiId) + "\u0000" + Textos.chaveCategoria(nome);
    }

    public List<CategoriaNova> novas() {
        return List.copyOf(novas);
    }
}
