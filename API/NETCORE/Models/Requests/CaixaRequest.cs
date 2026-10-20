/**
 * Arquivo: API/NETCORE/Models/Requests/CaixaRequest.cs
 * Objetivo: define contrato de entrada para operações de abertura, fechamento e status de caixa.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 */
namespace HORUSPDV_API.Models.Requests;

public class AbrirCaixaRequest
{
    public string? EventId { get; set; }
    public string? PayloadHash { get; set; }
    public string OpeningAmount { get; set; } = "0,00";

    /// <summary>Id da sessão gerado no cliente (abertura feita offline). Formato <c>cx-...</c>. Opcional.</summary>
    public string? ClientSessionId { get; set; }

    /// <summary>Horário real da abertura (ISO 8601), quando a abertura foi feita offline. Opcional.</summary>
    public string? OpenedAt { get; set; }
}

public class FecharCaixaRequest
{
    public string? EventId { get; set; }
    public string? PayloadHash { get; set; }
    public string ClosingAmount { get; set; } = "0,00";
    public string Note { get; set; } = "";

    /// <summary>Obrigatório quando o valor contado diverge do dinheiro esperado (calculado pelo servidor).</summary>
    public string? DifferenceReason { get; set; }

    /// <summary>Id da sessão a fechar (gerado no cliente). Sem ele, fecha a sessão aberta do operador.</summary>
    public string? ClientSessionId { get; set; }

    /// <summary>Horário real do fechamento (ISO 8601), quando feito offline. Opcional.</summary>
    public string? ClosedAt { get; set; }
}

public class RegistrarMovimentoCaixaRequest
{
    public string? EventId { get; set; }
    public string? PayloadHash { get; set; }

    /// <summary>"Reforco" ou "Sangria".</summary>
    public string Tipo { get; set; } = "";
    public string Valor { get; set; } = "0,00";
    public string Motivo { get; set; } = "";
}
