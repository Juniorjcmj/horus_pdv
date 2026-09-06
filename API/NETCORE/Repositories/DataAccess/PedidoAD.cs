/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/PedidoAD.cs
 * Objetivo: representa estrutura de dados de pedidos (orçamento montado pelo vendedor,
 *           finalizado depois no caixa) retornada pelo acesso ao banco.
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public enum PedidoStatus
{
    Aberto = 0,
    Finalizado = 1,
    Cancelado = 2
}

public class PedidoAD
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = string.Empty;
    public string OrderNumber { get; set; } = string.Empty;
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string SellerId { get; set; } = string.Empty;
    public string SellerName { get; set; } = string.Empty;
    public PedidoStatus Status { get; set; } = PedidoStatus.Aberto;
    public string? VendaId { get; set; }
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? FinalizedAt { get; set; }
    public string Note { get; set; } = string.Empty;
    public List<PedidoItemAD> Itens { get; set; } = [];
}

/// <summary>
/// Preço e nome já "congelados" no momento em que o vendedor montou o pedido — a finalização
/// no caixa cobra exatamente esse valor, não reconsulta o preço atual do produto.
/// </summary>
public class PedidoItemAD
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public decimal UnitPrice { get; set; }
    public decimal ItemTotal { get; set; }
}
