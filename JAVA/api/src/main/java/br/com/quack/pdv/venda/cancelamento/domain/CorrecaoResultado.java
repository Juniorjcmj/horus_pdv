package br.com.quack.pdv.venda.cancelamento.domain;

/** CorrecaoVendaResultadoAD da .NET (Data da resposta de corrigir). */
public record CorrecaoResultado(String vendaOriginalNumber, String novaVendaNumber, String corrigidaEm,
                                String financeiroNome, int itensRemovidos, String novoTotal, boolean fiscalQueued) {

    public CorrecaoResultado comFiscalQueued(boolean fila) {
        return new CorrecaoResultado(vendaOriginalNumber, novaVendaNumber, corrigidaEm, financeiroNome,
                itensRemovidos, novoTotal, fila);
    }
}
