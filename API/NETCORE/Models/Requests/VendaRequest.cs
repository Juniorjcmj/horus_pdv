/**
 * Arquivo: API/NETCORE/Models/Requests/VendaRequest.cs
 * Objetivo: define contrato de entrada para operações de registro de vendas e itens do carrinho.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 *
 * Quantity é `decimal` (não `int`) para suportar produtos vendidos por peso/volume (ex.: 0,452
 * kg) — o frontend já envia esse campo como número JSON puro (nunca foi mascarado como texto
 * pt-BR), então o model binder do ASP.NET Core aceita o mesmo payload sem mudança de formato.
 */
namespace HORUSPDV_API.Models.Requests;

public class VendaRequest
{
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
    public List<VendaItemRequest> Items { get; set; } = [];
}

public class VendaItemRequest
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
}
