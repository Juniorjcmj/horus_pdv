package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Passos das operacoes de estoque que a nuvem gravou num ciclo do detector (O1), agrupados pela versao do Change Tracking
 * (a mesma versao para tudo o que uma transacao gravou), e os produtos que tiveram movimentacao no ciclo (O3). Vive so
 * durante um ciclo; no fim, {@link MotorDeteccao#criarOperacoes} transforma cada grupo num comando {@code operacao}.
 */
final class ColetorOperacoes {

    /** Um passo: {@code tipo} (inserir, movimento, transicao), a tabela, a chave serializada e o corpo JSON. */
    record Passo(String tipo, String tabela, String chave, ObjectNode corpo) {}

    private final Map<Long, List<Passo>> porVersao = new TreeMap<>();
    private final Set<String> produtosComMovimento = new HashSet<>();

    void adicionar(long versao, Passo passo) {
        porVersao.computeIfAbsent(versao, v -> new ArrayList<>()).add(passo);
    }

    void marcarProduto(String produtoId) {
        if (produtoId != null) produtosComMovimento.add(produtoId);
    }

    boolean produtoTemMovimento(String produtoId) { return produtosComMovimento.contains(produtoId); }

    /** Grupos por versao, em ordem crescente (a ordem de criacao dos comandos). */
    Map<Long, List<Passo>> grupos() { return porVersao; }

    boolean vazio() { return porVersao.isEmpty(); }
}
