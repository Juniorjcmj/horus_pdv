package br.com.quack.estoque.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogo de produtos em memória com busca por nome (sem acento, parcial), código interno, GTIN e etiqueta de
 * balança (design D8). Recarregado ao abrir as telas, com F5 ou a cada 5 minutos.
 */
public final class Catalogo {

    /** Resultado da leitura de um código no leitor/campo de busca. */
    public record Leitura(Produto produto, BigDecimal quantidade, boolean balanca) {
    }

    private volatile List<Produto> produtos = List.of();
    private final Map<String, Produto> porId = new ConcurrentHashMap<>();
    private volatile long carregadoEm;

    public void carregar(List<Produto> lista) {
        List<Produto> ordenada = new ArrayList<>(lista);
        ordenada.sort(Comparator.comparing(p -> Fmt.normalizar(p.nome())));
        produtos = List.copyOf(ordenada);
        porId.clear();
        for (Produto p : produtos) {
            porId.put(p.id(), p);
        }
        carregadoEm = System.currentTimeMillis();
    }

    public List<Produto> todos() {
        return produtos;
    }

    public Produto porId(String id) {
        return id == null ? null : porId.get(id);
    }

    /** Troca um produto já carregado pela versão salva (edição na tabela), mantendo a ordem por nome. */
    public synchronized void substituir(Produto novo) {
        if (novo == null || novo.id() == null) {
            return;
        }
        List<Produto> lista = new ArrayList<>(produtos);
        lista.removeIf(p -> p.id().equals(novo.id()));
        lista.add(novo);
        lista.sort(Comparator.comparing(p -> Fmt.normalizar(p.nome())));
        produtos = List.copyOf(lista);
        porId.put(novo.id(), novo);
    }

    /** Marca o catálogo como velho: a próxima tela que precisar recarrega. */
    public void invalidar() {
        carregadoEm = 0;
    }

    public boolean vazio() {
        return produtos.isEmpty();
    }

    public boolean desatualizado(long maxIdadeMs) {
        return produtos.isEmpty() || System.currentTimeMillis() - carregadoEm > maxIdadeMs;
    }

    /** Código interno (texto igual) ou GTIN exato. */
    public Produto porCodigoOuGtin(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        String c = codigo.trim();
        for (Produto p : produtos) {
            if (p.codigo().equalsIgnoreCase(c)) {
                return p;
            }
        }
        for (Produto p : produtos) {
            if (!p.gtin().isBlank() && p.gtin().equals(c)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Interpreta o que foi bipado: código interno, GTIN ou etiqueta de balança (quantidade = peso). Devolve null
     * se nada casar.
     */
    public Leitura ler(String codigo) {
        Produto direto = porCodigoOuGtin(codigo);
        if (direto != null) {
            return new Leitura(direto, BigDecimal.ONE, false);
        }
        BalancaBarcode.Result r = BalancaBarcode.parse(codigo, produtos);
        if (r == null) {
            return null;
        }
        Produto p = BalancaBarcode.find(produtos, r.productCode());
        if (p == null) {
            return null;
        }
        return new Leitura(p, r.weightKg(), true);
    }

    /** Busca livre: cada palavra precisa aparecer no nome, no código, no GTIN, na marca/fornecedor. */
    public List<Produto> buscar(String texto, int limite) {
        String q = Fmt.normalizar(texto);
        if (q.isEmpty()) {
            return produtos.size() <= limite ? produtos : produtos.subList(0, limite);
        }
        String[] palavras = q.split("\\s+");
        List<Produto> out = new ArrayList<>();
        for (Produto p : produtos) {
            String alvo = Fmt.normalizar(p.nome() + " " + p.codigo() + " " + p.gtin() + " " + p.categoriaNome() + " "
                    + p.fornecedor());
            boolean todas = true;
            for (String w : palavras) {
                if (!alvo.contains(w)) {
                    todas = false;
                    break;
                }
            }
            if (todas) {
                out.add(p);
                if (out.size() >= limite) {
                    break;
                }
            }
        }
        // Código ou GTIN exatos primeiro.
        out.sort(Comparator.comparingInt((Produto p) -> p.codigo().equalsIgnoreCase(texto.trim())
                || p.gtin().equals(texto.trim()) ? 0 : 1));
        return out;
    }
}
