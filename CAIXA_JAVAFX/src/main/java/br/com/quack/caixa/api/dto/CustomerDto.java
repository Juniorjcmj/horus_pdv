package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerDto(String id, String customerName, String document, BigDecimal limiteCredito,
        BigDecimal saldoDevedor) {
}
