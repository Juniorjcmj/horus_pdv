package br.com.quack.estoque.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Contas de preço da tabela de produtos. Custo e preço de venda são a base; os outros três são derivados:
 * <ul>
 * <li>Lucro (R$) = preço − custo</li>
 * <li>Margem (%) = (preço − custo) ÷ preço × 100 — lucro sobre a venda</li>
 * <li>Markup (%) = (preço − custo) ÷ custo × 100 — acréscimo sobre o custo</li>
 * </ul>
 * Editar lucro, margem ou markup calcula o novo preço (arredondado a centavos); os demais vêm desse preço.
 */
public final class Precificacao {
    private static final BigDecimal CEM = new BigDecimal("100");

    private Precificacao() {
    }

    public static BigDecimal lucro(BigDecimal custo, BigDecimal preco) {
        return preco.subtract(custo).setScale(2, RoundingMode.HALF_UP);
    }

    /** Margem % sobre a venda; null se o preço for zero. */
    public static BigDecimal margem(BigDecimal custo, BigDecimal preco) {
        if (preco.signum() <= 0) {
            return null;
        }
        return preco.subtract(custo).multiply(CEM).divide(preco, 2, RoundingMode.HALF_UP);
    }

    /** Markup % sobre o custo; null se o custo for zero. */
    public static BigDecimal markup(BigDecimal custo, BigDecimal preco) {
        if (custo.signum() <= 0) {
            return null;
        }
        return preco.subtract(custo).multiply(CEM).divide(custo, 2, RoundingMode.HALF_UP);
    }

    /** Preço para ter este lucro em R$. */
    public static BigDecimal precoPorLucro(BigDecimal custo, BigDecimal lucro) {
        exigeCusto(custo);
        return valida(custo.add(lucro));
    }

    /** Preço para ter esta margem % sobre a venda (menor que 100%). */
    public static BigDecimal precoPorMargem(BigDecimal custo, BigDecimal margem) {
        exigeCusto(custo);
        if (margem.compareTo(CEM) >= 0) {
            throw new DomainException("A margem precisa ser menor que 100% (é o lucro sobre o preço de venda).");
        }
        BigDecimal fator = BigDecimal.ONE.subtract(margem.divide(CEM, 10, RoundingMode.HALF_UP));
        return valida(custo.divide(fator, 10, RoundingMode.HALF_UP));
    }

    /** Preço para ter este markup % sobre o custo. */
    public static BigDecimal precoPorMarkup(BigDecimal custo, BigDecimal markup) {
        exigeCusto(custo);
        BigDecimal fator = BigDecimal.ONE.add(markup.divide(CEM, 10, RoundingMode.HALF_UP));
        return valida(custo.multiply(fator));
    }

    private static void exigeCusto(BigDecimal custo) {
        if (custo == null || custo.signum() <= 0) {
            throw new DomainException("Informe o custo do produto antes de calcular pelo lucro, margem ou markup.");
        }
    }

    private static BigDecimal valida(BigDecimal preco) {
        BigDecimal p = preco.setScale(2, RoundingMode.HALF_UP);
        if (p.signum() <= 0) {
            throw new DomainException("O preço de venda calculado ficaria zero ou negativo.");
        }
        return p;
    }
}
