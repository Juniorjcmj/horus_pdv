/**
 * Arquivo: API/NETCORE/Models/Requests/FiadoRequest.cs
 * Objetivo: define contratos de entrada para liquidação e pagamentos de fiado.
 */
namespace HORUSPDV_API.Models.Requests;

public class RecebimentoFiadoRequest
{
    public string ClienteId { get; set; } = string.Empty;
    public decimal Valor { get; set; }
    public string FormaPagamento { get; set; } = "dinheiro";
    public string? Observacao { get; set; }
}
