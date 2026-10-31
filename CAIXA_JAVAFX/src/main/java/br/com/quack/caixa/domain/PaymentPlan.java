package br.com.quack.caixa.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Parcelas de pagamento de uma venda (regras D8). */
public final class PaymentPlan {
    private final BigDecimal total;
    private final List<PaymentPart> parts = new ArrayList<>();

    public PaymentPlan(BigDecimal total) {
        this.total = Money.round2(total);
    }

    public BigDecimal total() {
        return total;
    }

    public List<PaymentPart> parts() {
        return List.copyOf(parts);
    }

    public BigDecimal paid() {
        BigDecimal s = BigDecimal.ZERO;
        for (PaymentPart p : parts) {
            s = s.add(p.amount());
        }
        return Money.round2(s);
    }

    public BigDecimal remaining() {
        return Money.round2(total.subtract(paid()));
    }

    public BigDecimal totalChange() {
        BigDecimal s = BigDecimal.ZERO;
        for (PaymentPart p : parts) {
            s = s.add(p.change());
        }
        return Money.round2(s);
    }

    public void removeAt(int index) {
        if (index >= 0 && index < parts.size()) {
            parts.remove(index);
        }
    }

    /** cashGiven é usado só em dinheiro (null = igual ao valor). */
    public PaymentPart addPart(PaymentType type, BigDecimal amount, BigDecimal cashGiven, Customer customer) {
        if (amount == null || Money.round2(amount).signum() <= 0) {
            throw new DomainException("Informe um valor maior que zero.");
        }
        BigDecimal amt = Money.round2(amount);
        BigDecimal rem = remaining();
        if (amt.compareTo(rem) > 0) {
            throw new DomainException("Valor maior que o saldo restante (R$ " + Money.formatBr(rem) + ").");
        }
        BigDecimal given;
        BigDecimal change;
        if (type == PaymentType.DINHEIRO) {
            given = cashGiven == null ? amt : Money.round2(cashGiven);
            if (given.compareTo(amt) < 0) {
                throw new DomainException("Valor recebido é menor que o valor da parcela.");
            }
            change = given.subtract(amt);
        } else {
            given = amt;
            change = Money.ZERO;
        }
        if (type == PaymentType.FIADO) {
            checkCredit(amt, customer);
        }
        PaymentPart part = new PaymentPart(type, amt, given, change);
        parts.add(part);
        return part;
    }

    private void checkCredit(BigDecimal amount, Customer customer) {
        if (customer == null) {
            throw new DomainException("Selecione um cliente cadastrado para vender no fiado.");
        }
        BigDecimal fiadoNoPlano = BigDecimal.ZERO;
        for (PaymentPart p : parts) {
            if (p.type() == PaymentType.FIADO) {
                fiadoNoPlano = fiadoNoPlano.add(p.amount());
            }
        }
        BigDecimal available = customer.creditLimit().subtract(customer.debt()).subtract(fiadoNoPlano)
                .max(BigDecimal.ZERO);
        if (customer.creditLimit().signum() <= 0 || amount.compareTo(available) > 0) {
            throw new DomainException("Limite de crédito insuficiente para " + customer.name() + "! Limite total: R$ "
                    + Money.formatBr(customer.creditLimit()) + " | Saldo devedor: R$ "
                    + Money.formatBr(customer.debt()) + " | Disponível: R$ " + Money.formatBr(available)
                    + " | Parcela fiado: R$ " + Money.formatBr(amount));
        }
    }

    /**
     * Confirmação rápida: usa o valor corrente como última (ou única) parcela e exige soma == total.
     * amount null = saldo restante. Lança DomainException se ainda faltar valor.
     */
    public void finalizeWith(PaymentType type, BigDecimal amount, BigDecimal cashGiven, Customer customer) {
        BigDecimal rem = remaining();
        if (rem.signum() > 0) {
            BigDecimal amt = amount == null ? rem : Money.round2(amount);
            if (type == PaymentType.DINHEIRO && parts.isEmpty() && cashGiven != null
                    && Money.round2(cashGiven).compareTo(total) >= 0) {
                addPart(type, total, cashGiven, customer);
            } else if (amt.signum() > 0 && amt.compareTo(rem) == 0) {
                addPart(type, amt, cashGiven, customer);
            } else if (parts.isEmpty() && type != PaymentType.DINHEIRO && amt.compareTo(total) >= 0) {
                addPart(type, total, null, customer);
            }
        }
        BigDecimal faltam = remaining();
        if (faltam.signum() != 0) {
            throw new DomainException("Faltam R$ " + Money.formatBr(faltam) + " para liquidar a venda.");
        }
    }

    public String headerPaymentType() {
        return parts.size() == 1 ? parts.get(0).type().wire() : "Múltiplo";
    }
}
