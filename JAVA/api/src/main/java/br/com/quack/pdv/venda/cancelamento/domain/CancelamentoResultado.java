package br.com.quack.pdv.venda.cancelamento.domain;

import java.math.BigDecimal;

/** Resultado do cancelamento (CancelamentoVendaResultado da .NET). Codigo identifica a recusa. */
public record CancelamentoResultado(boolean sucesso, String mensagem, int itensEstornados, String canceladoEm,
                                    String codigo, String documentoFiscalId, Integer documentoModelo,
                                    BigDecimal fiadoEstornado) {

    public static CancelamentoResultado recusa(String mensagem, String codigo) {
        return new CancelamentoResultado(false, mensagem, 0, null, codigo, null, null, BigDecimal.ZERO);
    }

    public static CancelamentoResultado recusaDeNota(String mensagem, String codigo, String documentoId,
                                                     int modelo) {
        return new CancelamentoResultado(false, mensagem, 0, null, codigo, documentoId, modelo, BigDecimal.ZERO);
    }
}
