package br.com.quack.estoque.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Conversão da unidade de compra (ex.: caixa com 12) para a unidade de estoque: 5 CX x 12 = 60 UN, e o custo
 * unitário é o custo da caixa dividido por 12.
 */
public final class ConversaoUnidade {
    private ConversaoUnidade() {
    }

    public static BigDecimal quantidadeEstoque(BigDecimal quantidadeCompra, BigDecimal fator) {
        return quantidadeCompra.multiply(fatorValido(fator)).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** Custo por unidade de estoque (4 casas, como a API guarda). */
    public static BigDecimal custoUnitario(BigDecimal custoCompra, BigDecimal fator) {
        return custoCompra.divide(fatorValido(fator), 4, RoundingMode.HALF_UP);
    }

    /** Total da linha na compra (quantidade comprada x custo da embalagem). */
    public static BigDecimal totalLinha(BigDecimal quantidadeCompra, BigDecimal custoCompra) {
        return quantidadeCompra.multiply(custoCompra).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal fatorValido(BigDecimal fator) {
        if (fator == null || fator.signum() <= 0) {
            throw new DomainException("O fator de conversão deve ser maior que zero.");
        }
        return fator;
    }
}
