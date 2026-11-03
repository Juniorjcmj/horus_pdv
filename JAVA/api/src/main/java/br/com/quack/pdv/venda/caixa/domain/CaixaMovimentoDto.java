package br.com.quack.pdv.venda.caixa.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** CaixaMovimentoDto da API .NET. */
public record CaixaMovimentoDto(
        @JsonProperty("id") String id,
        @JsonProperty("tipo") String tipo,
        @JsonProperty("valor") String valor,
        @JsonProperty("motivo") String motivo,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("operatorName") String operatorName) {}
