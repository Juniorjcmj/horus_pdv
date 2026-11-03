package br.com.quack.pdv.produto.lote.app;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Porta literal de LoteEstimador: distribui o estoque atual do produto entre seus lotes assumindo consumo FEFO
 * (os lotes de validade mais distante retem o saldo remanescente) ou devolve o saldo real quando o modo e 'ativo'.
 */
public final class LoteEstimador {

    private static final Comparator<LoteLinha> ORDEM_VALIDADE_DESC = Comparator
            .comparing(LoteLinha::dataValidade, Comparator.reverseOrder())
            .thenComparing(l -> l.criadoEm().toInstant(), Comparator.reverseOrder());

    private LoteEstimador() {}

    public static Map<String, BigDecimal> distribuir(List<LoteLinha> lotesDoProduto) {
        Map<String, BigDecimal> saldos = new LinkedHashMap<>();
        if (lotesDoProduto == null || lotesDoProduto.isEmpty()) {
            return saldos;
        }

        BigDecimal estoque = lotesDoProduto.getFirst().estoque();
        BigDecimal restante = estoque != null && estoque.compareTo(BigDecimal.ZERO) > 0 ? estoque : BigDecimal.ZERO;

        List<LoteLinha> ordenados = lotesDoProduto.stream().sorted(ORDEM_VALIDADE_DESC).toList();
        for (LoteLinha lote : ordenados) {
            BigDecimal inicial = lote.qtdInicial() != null ? lote.qtdInicial() : BigDecimal.ZERO;
            BigDecimal menor = inicial.compareTo(restante) <= 0 ? inicial : restante;
            BigDecimal alocado = menor.compareTo(BigDecimal.ZERO) > 0 ? menor : BigDecimal.ZERO;
            saldos.put(lote.id(), alocado);
            restante = restante.subtract(alocado);
        }
        return saldos;
    }

    public static Map<String, BigDecimal> saldos(List<LoteLinha> lotesDoProduto, boolean usarSaldoReal) {
        if (!usarSaldoReal) {
            return distribuir(lotesDoProduto);
        }
        Map<String, BigDecimal> saldos = new LinkedHashMap<>();
        if (lotesDoProduto == null) {
            return saldos;
        }
        for (LoteLinha lote : lotesDoProduto) {
            BigDecimal real = lote.qtdAtual() != null && lote.qtdAtual().compareTo(BigDecimal.ZERO) > 0
                    ? lote.qtdAtual()
                    : BigDecimal.ZERO;
            saldos.put(lote.id(), real);
        }
        return saldos;
    }
}
