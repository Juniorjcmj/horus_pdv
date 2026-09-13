/**
 * Arquivo: API/NETCORE/Services/Fiado/IFiadoService.cs
 * Objetivo: interface de regras de negócio para fiado e conta corrente de clientes.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;

namespace HORUSPDV_API.Services.Fiado;

public interface IFiadoService
{
    Task<FiadoMovimentoAD> ReceberAsync(
        string companyId,
        string operadorId,
        string operadorNome,
        RecebimentoFiadoRequest request);

    Task<List<FiadoMovimentoAD>> ObterExtratoAsync(
        string companyId,
        string clienteId,
        DateTimeOffset? dataInicio = null,
        DateTimeOffset? dataFim = null);

    Task<List<FiadoDevedorAD>> ListarDevedoresAsync(
        string companyId,
        string? busca = null);

    Task<FiadoResumoAD> ObterResumoAsync(string companyId);
}
