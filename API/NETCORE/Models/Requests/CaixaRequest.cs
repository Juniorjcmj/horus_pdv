/**
 * Arquivo: API/NETCORE/Models/Requests/CaixaRequest.cs
 * Objetivo: define contrato de entrada para operações de abertura, fechamento e status de caixa.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 */
namespace HORUSPDV_API.Models.Requests;

public class AbrirCaixaRequest
{
    public string OpeningAmount { get; set; } = "0,00";
}

public class FecharCaixaRequest
{
    public string ClosingAmount { get; set; } = "0,00";
    public string Note { get; set; } = "";

    /// <summary>Obrigatório quando o valor contado diverge do dinheiro esperado (calculado pelo servidor).</summary>
    public string? DifferenceReason { get; set; }
}

public class RegistrarMovimentoCaixaRequest
{
    /// <summary>"Reforco" ou "Sangria".</summary>
    public string Tipo { get; set; } = "";
    public string Valor { get; set; } = "0,00";
    public string Motivo { get; set; } = "";
}
