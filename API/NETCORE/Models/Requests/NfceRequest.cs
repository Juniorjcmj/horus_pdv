/**
 * Arquivo: API/NETCORE/Models/Requests/NfceRequest.cs
 * Objetivo: define contratos de entrada para cancelamento e inutilização de NFC-e.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 */
namespace HORUSPDV_API.Models.Requests;

public class CancelamentoNfceRequest
{
    /// <summary>Mínimo 15 caracteres, exigência da SEFAZ.</summary>
    public string Justificativa { get; set; } = string.Empty;
}

public class CancelamentoComSupervisorRequest
{
    public string SupervisorId { get; set; } = string.Empty;
    public string SupervisorPassword { get; set; } = string.Empty;
    /// <summary>Mínimo 15 caracteres, exigência da SEFAZ.</summary>
    public string Justificativa { get; set; } = string.Empty;
}

public class InutilizacaoNfceRequest
{
    public int Serie { get; set; } = 1;
    public int NumeroInicial { get; set; }
    public int NumeroFinal { get; set; }
    /// <summary>Mínimo 15 caracteres, exigência da SEFAZ.</summary>
    public string Justificativa { get; set; } = string.Empty;
}
