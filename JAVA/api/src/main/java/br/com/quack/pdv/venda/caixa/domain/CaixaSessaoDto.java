package br.com.quack.pdv.venda.caixa.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** CaixaSessionDto da API .NET (valores monetarios em texto pt-BR). */
public record CaixaSessaoDto(
        @JsonProperty("id") String id,
        @JsonProperty("status") String status,
        @JsonProperty("openedAt") String openedAt,
        @JsonProperty("closedAt") String closedAt,
        @JsonProperty("openingAmount") String openingAmount,
        @JsonProperty("closingAmount") String closingAmount,
        @JsonProperty("operatorId") String operatorId,
        @JsonProperty("operatorName") String operatorName,
        @JsonProperty("closedById") String closedById,
        @JsonProperty("closedByName") String closedByName,
        @JsonProperty("note") String note,
        @JsonProperty("elapsedMinutes") int elapsedMinutes,
        @JsonProperty("expectedCashAmount") String expectedCashAmount,
        @JsonProperty("differenceAmount") String differenceAmount,
        @JsonProperty("differenceReason") String differenceReason,
        @JsonProperty("movimentos") List<CaixaMovimentoDto> movimentos,
        @JsonProperty("paymentBreakdown") List<PagamentoTotalDto> paymentBreakdown) {

    public CaixaSessaoDto {
        movimentos = movimentos == null ? List.of() : movimentos;
    }
}
