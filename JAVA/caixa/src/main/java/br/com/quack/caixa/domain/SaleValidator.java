package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.VendaPayload;
import java.math.BigDecimal;

/** Porta de validateSale (saleMoney.ts): nenhuma venda inconsistente sai do caixa. */
public final class SaleValidator {
    private SaleValidator() {
    }

    public static void validate(VendaPayload p) {
        BigDecimal total = Money.round2(Money.parseBr(p.totalAmount()));
        if (total.signum() <= 0 || p.items() == null || p.items().isEmpty()) {
            throw new DomainException("A venda precisa ter itens e total maior que zero.");
        }
        BigDecimal sumItems = BigDecimal.ZERO;
        for (VendaPayload.Item i : p.items()) {
            BigDecimal net = Money.itemNet(i.quantity(), i.unitPrice(), i.desconto() == null ? BigDecimal.ZERO
                    : i.desconto());
            if (i.itemTotal() == null || Money.round2(i.itemTotal()).compareTo(net) != 0) {
                throw new DomainException("Total do item " + i.productCode() + " inconsistente.");
            }
            sumItems = sumItems.add(net);
        }
        if (Money.round2(sumItems).compareTo(total) != 0) {
            throw new DomainException("A soma dos itens não confere com o total da venda.");
        }
        if (p.payments() == null || p.payments().isEmpty()) {
            throw new DomainException("Informe ao menos uma forma de pagamento.");
        }
        BigDecimal sumPay = BigDecimal.ZERO;
        for (VendaPayload.Payment pay : p.payments()) {
            BigDecimal amount = Money.round2(pay.amount() == null ? BigDecimal.ZERO : pay.amount());
            if (amount.signum() <= 0) {
                throw new DomainException("Parcela de pagamento inválida.");
            }
            BigDecimal change = Money.round2(pay.changeAmount() == null ? BigDecimal.ZERO : pay.changeAmount());
            boolean cash = PaymentType.DINHEIRO.wire().equalsIgnoreCase(pay.paymentType());
            if (!cash && change.signum() != 0) {
                throw new DomainException("Troco só é permitido em dinheiro.");
            }
            if (cash) {
                BigDecimal given = Money.round2(pay.cashGiven() == null ? amount : pay.cashGiven());
                if (given.compareTo(amount.add(change)) != 0) {
                    throw new DomainException("Valor recebido em dinheiro inconsistente.");
                }
            }
            sumPay = sumPay.add(amount);
        }
        if (Money.round2(sumPay).compareTo(total) != 0) {
            throw new DomainException("A soma das parcelas não confere com o total da venda.");
        }
    }
}
