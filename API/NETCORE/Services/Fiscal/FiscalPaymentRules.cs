using HORUSPDV_API.Repositories.DataAccess;

namespace HORUSPDV_API.Services.Fiscal;

public static class FiscalPaymentRules
{
    // Amount is the net sale allocation; vPag is the amount actually received before change.
    public static decimal DeclaredAmount(VendaPagamentoAD payment)
    {
        if (payment.Amount < 0 || payment.ChangeAmount < 0 || payment.CashGiven < 0)
            throw new FiscalConfigurationException("Pagamento ou troco negativo no documento fiscal.");
        if (!string.Equals(payment.PaymentType, "dinheiro", StringComparison.OrdinalIgnoreCase))
        {
            if (payment.ChangeAmount != 0)
                throw new FiscalConfigurationException("Troco informado em pagamento que não é dinheiro.");
            return FiscalTaxRules.Money(payment.Amount);
        }
        var received = payment.CashGiven > 0 ? payment.CashGiven : payment.Amount + payment.ChangeAmount;
        if (Math.Abs(received - payment.Amount - payment.ChangeAmount) > .01m)
            throw new FiscalConfigurationException("Valor recebido em dinheiro não confere com a parcela e o troco da venda.");
        return FiscalTaxRules.Money(received);
    }

    public static void Validate(IReadOnlyList<PagamentoFiscal> payments, decimal total, decimal change, bool allowNoPayment = false)
    {
        if (payments.Count == 0 || total < 0 || change < 0 || payments.Any(p => p.Valor < 0))
            throw new FiscalConfigurationException("Pagamentos, total ou troco inválidos no documento fiscal.");
        if (allowNoPayment && payments.All(p => p.Tipo == "90" && p.Valor == 0) && change == 0) return;
        if (Math.Abs(payments.Sum(p => p.Valor) - change - total) > .01m)
            throw new FiscalConfigurationException("A soma dos pagamentos, descontado o troco, não confere com o total da nota.");
        if (change > payments.Where(p => p.Tipo == "01").Sum(p => p.Valor))
            throw new FiscalConfigurationException("O troco supera o valor recebido em dinheiro.");
    }
}
