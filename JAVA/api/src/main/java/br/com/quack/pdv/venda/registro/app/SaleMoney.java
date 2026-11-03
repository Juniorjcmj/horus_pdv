package br.com.quack.pdv.venda.registro.app;

import br.com.quack.pdv.venda.registro.domain.PagamentoNormalizado;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Porta do {@code SaleMoney} da API .NET: contrato canonico de liquidacao. O arredondamento para duas casas e o do
 * {@code decimal}: ponto medio para longe do zero. As mensagens com {@code N2} saem na cultura invariante do servidor
 * .NET ("1,234.50"), nao em pt-BR.
 */
public final class SaleMoney {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private SaleMoney() {}

    /** {@code Math.Round(valor, 2, MidpointRounding.AwayFromZero)}. */
    public static BigDecimal money(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code valor.ToString("N2")} na cultura invariante. */
    public static String n2(BigDecimal valor) {
        DecimalFormat formato = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        formato.setRoundingMode(RoundingMode.HALF_UP);
        formato.setParseBigDecimal(true);
        return formato.format(valor);
    }

    /** Liquido do item: bruto arredondado menos o desconto arredondado. */
    public static BigDecimal itemNet(BigDecimal quantity, BigDecimal price, BigDecimal discount) {
        if (quantity.signum() <= 0 || price.signum() < 0 || discount.signum() < 0) {
            throw new IllegalArgumentException("Quantidade, preço ou desconto inválido na venda.");
        }
        BigDecimal gross = money(quantity.multiply(price));
        BigDecimal roundedDiscount = money(discount);
        if (roundedDiscount.compareTo(gross) > 0) {
            throw new IllegalArgumentException("Desconto superior ao bruto do item.");
        }
        return gross.subtract(roundedDiscount);
    }

    /** Pagamento enviado pelo cliente (valores ainda nao arredondados). */
    public record Entrada(String paymentType, BigDecimal amount, BigDecimal cashGiven, BigDecimal changeAmount) {}

    /** Normaliza e confere os pagamentos contra o total. Sem pagamentos, vale uma unica parcela do tipo informado. */
    public static List<PagamentoNormalizado> payments(BigDecimal total, String paymentType, List<Entrada> source) {
        BigDecimal totalArredondado = money(total);
        if (totalArredondado.signum() <= 0) {
            throw new IllegalArgumentException("Total da venda deve ser maior que zero.");
        }
        List<Entrada> payments = source != null && !source.isEmpty()
                ? source
                : List.of(new Entrada(paymentType, totalArredondado, null, null));
        List<PagamentoNormalizado> result = new ArrayList<>();
        for (Entrada payment : payments) {
            if (payment.amount().signum() <= 0
                    || (payment.cashGiven() != null && payment.cashGiven().signum() < 0)
                    || (payment.changeAmount() != null && payment.changeAmount().signum() < 0)) {
                throw new IllegalArgumentException("Pagamento, recebido ou troco inválido.");
            }
            BigDecimal amount = money(payment.amount());
            BigDecimal change = payment.changeAmount() != null ? money(payment.changeAmount()) : null;
            BigDecimal received = payment.cashGiven() != null ? money(payment.cashGiven()) : null;
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Parcela deve ser maior que zero.");
            }
            String type = payment.paymentType() == null ? null : dotNetTrim(payment.paymentType());
            if (type == null || type.isBlank() || type.equals("-")) {
                type = "dinheiro";
            }
            boolean cash = type.equalsIgnoreCase("dinheiro");
            BigDecimal troco = change == null ? ZERO : change;
            if (!cash && troco.signum() != 0) {
                throw new IllegalArgumentException("Troco permitido somente em dinheiro.");
            }
            if (cash && (received != null || change != null)
                    && (received != null ? received : amount).compareTo(amount.add(troco)) != 0) {
                throw new IllegalArgumentException("Recebido em dinheiro deve ser igual à parcela mais troco.");
            }
            result.add(new PagamentoNormalizado(type, amount, received, change));
        }
        BigDecimal soma = result.stream().map(PagamentoNormalizado::amount).reduce(ZERO, BigDecimal::add);
        if (soma.compareTo(totalArredondado) != 0) {
            throw new IllegalArgumentException("Soma dos pagamentos difere do total R$ " + n2(totalArredondado) + ".");
        }
        return result;
    }

    /** Soma das parcelas em fiado. */
    public static BigDecimal fiado(List<PagamentoNormalizado> payments) {
        return payments.stream()
                .filter(p -> p.paymentType().equalsIgnoreCase("fiado"))
                .map(PagamentoNormalizado::amount)
                .reduce(ZERO, BigDecimal::add);
    }

    /** Confere o limite de credito (a mensagem usa a cultura invariante, como o .NET). */
    public static void credit(BigDecimal limit, BigDecimal debt, BigDecimal requested) {
        if (limit.signum() < 0 || debt.signum() < 0) {
            throw new IllegalArgumentException("Dados de crédito inválidos.");
        }
        BigDecimal available = money(limit).subtract(money(debt)).max(ZERO);
        if (limit.signum() <= 0 || requested.compareTo(available) > 0) {
            BigDecimal insuficiencia = requested.subtract(available).max(ZERO);
            throw new IllegalArgumentException("Crédito insuficiente. Disponível: R$ " + n2(available)
                    + ", solicitado em fiado: R$ " + n2(requested) + ", insuficiência: R$ " + n2(insuficiencia)
                    + ". Cliente sem limite não pode comprar fiado.");
        }
    }

    private static String dotNetTrim(String s) {
        return br.com.quack.pdv.venda.registro.HashPayload.trim(s);
    }
}
