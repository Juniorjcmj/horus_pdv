/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/VendaHistoricoAD.cs
 * Objetivo: representa estrutura de dados de registro de vendas e itens do carrinho retornada
 *           pelo acesso ao banco — é também o contrato JSON devolvido ao frontend (não há
 *           camada Model/Service separada para histórico de vendas).
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 *
 * TotalAmount/UnitPrice/ItemTotal continuam string pt-BR no contrato (formatados por
 * HorusMoneyFormat a partir do `decimal` nativo das colunas). Quantity vira `decimal` nativo
 * (JSON number) — nunca foi mascarado no frontend, então o contrato não muda para quem só lê.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class VendaHistoricoAD
{
    public string SaleNumber { get; set; } = string.Empty;
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public string UnitPrice { get; set; } = string.Empty;
    public string ItemTotal { get; set; } = string.Empty;
    public string SaleDate { get; set; } = string.Empty;
}

public class VendaRegistroResultadoAD
{
    public string SaleNumber { get; set; } = string.Empty;
    public string VendaId { get; set; } = string.Empty;
    public List<VendaHistoricoAD> Rows { get; set; } = [];
    public List<VendaPagamentoAD> Payments { get; set; } = [];
}
