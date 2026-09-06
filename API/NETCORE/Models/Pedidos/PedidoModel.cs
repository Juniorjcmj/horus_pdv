/**
 * Arquivo: API/NETCORE/Models/Pedidos/PedidoModel.cs
 * Objetivo: representa dados de pedido trafegados entre banco, serviços e API.
 * Entradas esperadas: recebe valores persistidos ou calculados para serialização nas respostas.
 */
namespace HORUSPDV_API.Models.Pedidos;

public class PedidoModel
{
    public string OrderNumber { get; set; } = string.Empty;
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string SellerName { get; set; } = string.Empty;
    /// <summary>"aberto" | "finalizado" | "cancelado".</summary>
    public string Status { get; set; } = "aberto";
    public string CreatedAt { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public List<PedidoItemModel> Itens { get; set; } = [];
}

public class PedidoItemModel
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public string UnitPrice { get; set; } = string.Empty;
    public string ItemTotal { get; set; } = string.Empty;
}
