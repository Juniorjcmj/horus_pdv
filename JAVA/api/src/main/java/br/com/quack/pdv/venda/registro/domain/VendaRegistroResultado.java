package br.com.quack.pdv.venda.registro.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * VendaRegistroResultadoAD da API .NET. E tambem o ResponsePayload de ProcessedEvents: JSON camelCase lido pelas
 * duas APIs.
 */
public record VendaRegistroResultado(
        @JsonProperty("saleNumber") String saleNumber,
        @JsonProperty("vendaId") String vendaId,
        @JsonProperty("clientSaleId") String clientSaleId,
        @JsonProperty("isReplay") boolean isReplay,
        @JsonProperty("rows") List<VendaHistoricoDto> rows,
        @JsonProperty("payments") List<VendaPagamentoDto> payments,
        @JsonProperty("warnings") List<RupturaAviso> warnings,
        @JsonProperty("fiadoSaldoAtual") BigDecimal fiadoSaldoAtual,
        @JsonProperty("fiadoClienteId") String fiadoClienteId) {

    public VendaRegistroResultado {
        rows = rows == null ? List.of() : rows;
        payments = payments == null ? List.of() : payments;
        warnings = warnings == null ? List.of() : warnings;
    }

    public VendaRegistroResultado comReplay(boolean replay) {
        return new VendaRegistroResultado(saleNumber, vendaId, clientSaleId, replay, rows, payments, warnings,
                fiadoSaldoAtual, fiadoClienteId);
    }

    public VendaRegistroResultado comFiado(BigDecimal saldo, String clienteId) {
        return new VendaRegistroResultado(saleNumber, vendaId, clientSaleId, isReplay, rows, payments, warnings,
                saldo, clienteId);
    }

    public VendaRegistroResultado comWarnings(List<RupturaAviso> avisos) {
        return new VendaRegistroResultado(saleNumber, vendaId, clientSaleId, isReplay, rows, payments, avisos,
                fiadoSaldoAtual, fiadoClienteId);
    }
}
