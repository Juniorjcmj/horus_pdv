/**
 * Arquivo: API/NETCORE/Models/Requests/OrdemCompraRequests.cs
 * Objetivo: DTOs de requisição para criação e recebimento de ordens de compra.
 */
namespace HORUSPDV_API.Models.Requests;

public class CriarOrdemCompraRequest
{
    public string SupplierId { get; set; } = string.Empty;
    public string? Note { get; set; }
    public List<OrdemCompraItemRequest> Items { get; set; } = [];
}

public class OrdemCompraItemRequest
{
    public string ProductCode { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public decimal UnitCost { get; set; }
}

public class ReceberOrdemCompraRequest
{
    public List<ReceberOrdemCompraItemRequest> Itens { get; set; } = [];
}

public class ReceberOrdemCompraItemRequest
{
    public string ProductCode { get; set; } = string.Empty;
    public decimal QuantityReceived { get; set; }
}
