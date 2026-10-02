/**
 * Arquivo: API/NETCORE/Models/Requests/CancelamentoVendaRequest.cs
 * Objetivo: define contrato de entrada para cancelamento de venda no PDV autorizado por senha do supervisor/gerente.
 */
namespace HORUSPDV_API.Models.Requests;

public class CancelamentoVendaComSupervisorRequest
{
    public string SupervisorId { get; set; } = string.Empty;
    public string SupervisorPassword { get; set; } = string.Empty;
    public string Justificativa { get; set; } = string.Empty;
}
