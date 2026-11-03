package br.com.quack.pdv.venda.registro.domain;

import java.math.BigDecimal;

/** Parcela de pagamento ja normalizada (tipo aparado, valores com duas casas). */
public record PagamentoNormalizado(String paymentType, BigDecimal amount, BigDecimal cashGiven,
                                   BigDecimal changeAmount) {}
