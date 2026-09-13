/**
 * Arquivo: API/NETCORE/Services/Promocoes/IPromocaoService.cs
 * Objetivo: interface de regras de negócio para gerenciamento de promoções e preços dinâmicos.
 */
using HORUSPDV_API.Models.Promocoes;
using HORUSPDV_API.Models.Requests;

namespace HORUSPDV_API.Services.Promocoes;

public interface IPromocaoService
{
    Task<List<PromocaoModel>> ListarAsync(string companyId);
    Task<List<PromocaoModel>> ListarAtivasAsync(string companyId);
    Task<PromocaoModel?> ObterPorIdAsync(string companyId, string id);
    Task<PromocaoModel> CriarAsync(string companyId, string operadorNome, PromocaoRequest request);
    Task<PromocaoModel> AtualizarAsync(string companyId, string id, PromocaoRequest request);
    Task AtivarDesativarAsync(string companyId, string id, bool ativa);
    Task ExcluirAsync(string companyId, string id);
    Task<PromocaoResultadoModel> ObterResultadoAsync(string companyId, string id);
}
