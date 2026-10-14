using HORUSPDV_API.Models.Requests;

namespace HORUSPDV_API.Services.Shared;

/// <summary>Canonical settlement contract; decimal midpoint rounds away from zero.</summary>
public static class SaleMoney
{
    public static decimal Money(decimal value) => Math.Round(value, 2, MidpointRounding.AwayFromZero);
    public static decimal ItemNet(decimal quantity, decimal price, decimal discount)
    {
        if (quantity <= 0 || price < 0 || discount < 0)
            throw new InvalidOperationException("Quantidade, preÃ§o ou desconto invÃ¡lido na venda.");
        var gross = Money(quantity * price);
        var roundedDiscount = Money(discount);
        if (roundedDiscount > gross) throw new InvalidOperationException("Desconto superior ao bruto do item.");
        return gross - roundedDiscount;
    }
    public static List<VendaPagamentoRequest> Payments(decimal total, string paymentType, List<VendaPagamentoRequest>? source)
    {
        total = Money(total);
        if (total <= 0) throw new InvalidOperationException("Total da venda deve ser maior que zero.");
        var payments = source is { Count: > 0 } ? source : [new VendaPagamentoRequest { PaymentType = paymentType, Amount = total }];
        var result = new List<VendaPagamentoRequest>();
        foreach (var payment in payments)
        {
            if (payment.Amount <= 0 || payment.CashGiven < 0 || payment.ChangeAmount < 0)
                throw new InvalidOperationException("Pagamento, recebido ou troco invÃ¡lido.");
            var amount = Money(payment.Amount);
            var change = payment.ChangeAmount.HasValue ? Money(payment.ChangeAmount.Value) : (decimal?)null;
            var received = payment.CashGiven.HasValue ? Money(payment.CashGiven.Value) : (decimal?)null;
            if (amount <= 0) throw new InvalidOperationException("Parcela deve ser maior que zero.");
            var type = payment.PaymentType?.Trim();
            if (string.IsNullOrWhiteSpace(type) || type == "-") type = "dinheiro";
            var cash = string.Equals(type, "dinheiro", StringComparison.OrdinalIgnoreCase);
            if (!cash && change.GetValueOrDefault() != 0)
                throw new InvalidOperationException("Troco permitido somente em dinheiro.");
            if (cash && (received.HasValue || change.HasValue) && (received ?? amount) != amount + change.GetValueOrDefault())
                throw new InvalidOperationException("Recebido em dinheiro deve ser igual Ã  parcela mais troco.");
            result.Add(new VendaPagamentoRequest { PaymentType = type, Amount = amount, CashGiven = received, ChangeAmount = change });
        }
        if (result.Sum(p => p.Amount) != total)
            throw new InvalidOperationException($"Soma dos pagamentos difere do total R$ {total:N2}.");
        return result;
    }
    public static decimal Fiado(IEnumerable<VendaPagamentoRequest> payments) => payments.Where(p => string.Equals(p.PaymentType, "fiado", StringComparison.OrdinalIgnoreCase)).Sum(p => p.Amount);
    public static void Credit(decimal limit, decimal debt, decimal requested)
    {
        if (limit < 0 || debt < 0) throw new InvalidOperationException("Dados de crÃ©dito invÃ¡lidos.");
        var available = Math.Max(0, Money(limit) - Money(debt));
        if (limit <= 0 || requested > available)
            throw new InvalidOperationException($"CrÃ©dito insuficiente. DisponÃ­vel: R$ {available:N2}, solicitado em fiado: R$ {requested:N2}, insuficiÃªncia: R$ {Math.Max(0, requested - available):N2}. Cliente sem limite nÃ£o pode comprar fiado.");
    }
}
