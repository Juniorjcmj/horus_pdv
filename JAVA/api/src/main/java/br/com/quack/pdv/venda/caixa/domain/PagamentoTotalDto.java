package br.com.quack.pdv.venda.caixa.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** PaymentBreakdownDto da API .NET: total vendido por forma de pagamento no turno. */
public record PagamentoTotalDto(
        @JsonProperty("paymentType") String paymentType,
        @JsonProperty("total") String total) {}
