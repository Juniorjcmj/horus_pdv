/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/VendaPagamentoAD.cs
 * Objetivo: representa uma parcela/forma de pagamento individual de uma venda realizada.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class VendaPagamentoAD
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = string.Empty;
    public string VendaId { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public decimal Amount { get; set; }
    public decimal CashGiven { get; set; }
    public decimal ChangeAmount { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
}
