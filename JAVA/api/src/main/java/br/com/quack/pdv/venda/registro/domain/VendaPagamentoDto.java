package br.com.quack.pdv.venda.registro.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** VendaPagamentoAD da API .NET (tambem faz parte do ResponsePayload guardado em ProcessedEvents). */
public record VendaPagamentoDto(
        @JsonProperty("id") String id,
        @JsonProperty("companyId") String companyId,
        @JsonProperty("vendaId") String vendaId,
        @JsonProperty("paymentType") String paymentType,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("cashGiven") BigDecimal cashGiven,
        @JsonProperty("changeAmount") BigDecimal changeAmount,
        @JsonProperty("createdAt") OffsetDateTime createdAt) {}
