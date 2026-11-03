package br.com.quack.pdv.venda.registro.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** EstoqueRupturaAvisoAD: aviso de saldo negativo numa venda offline. */
public record RupturaAviso(
        @JsonProperty("productCode") String productCode,
        @JsonProperty("productName") String productName,
        @JsonProperty("estoqueAnterior") BigDecimal estoqueAnterior,
        @JsonProperty("quantidadeVendida") BigDecimal quantidadeVendida,
        @JsonProperty("saldoResultante") BigDecimal saldoResultante) {}
