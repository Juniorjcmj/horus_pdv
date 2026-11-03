package br.com.quack.caixa.domain;

import java.math.BigDecimal;

public record PaymentPart(PaymentType type, BigDecimal amount, BigDecimal cashGiven, BigDecimal change) {
}
