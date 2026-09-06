/**
 * Arquivo: API/NETCORE/Models/Requests/PedidoRequest.cs
 * Objetivo: define contratos de entrada para criação e finalização de pedidos.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 */
namespace HORUSPDV_API.Models.Requests;

public class CriarPedidoRequest
{
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public List<VendaItemRequest> Items { get; set; } = [];
}

public class FinalizarPedidoRequest
{
    public string PaymentType { get; set; } = string.Empty;
    public List<VendaPagamentoRequest> Payments { get; set; } = [];
}
