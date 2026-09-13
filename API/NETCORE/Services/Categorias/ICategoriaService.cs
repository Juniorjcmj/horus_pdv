/**
 * Arquivo: API/NETCORE/Services/Categorias/ICategoriaService.cs
 * Objetivo: centraliza regras de negócio de categorias e departamentos de produtos.
 */
using HORUSPDV_API.Models.Categorias;
using HORUSPDV_API.Models.Requests;

namespace HORUSPDV_API.Services.Categorias;

public interface ICategoriaService
{
    Task<List<CategoriaModel>> ListarArvoreAsync(string companyId, bool apenasAtivas = false);
    Task<List<CategoriaModel>> ListarTodasAsync(string companyId, bool apenasAtivas = false);
    Task<CategoriaModel?> ObterPorIdAsync(string companyId, string id);
    Task<CategoriaModel> CriarAsync(string companyId, CategoriaRequest request);
    Task<CategoriaModel?> AtualizarAsync(string companyId, string id, CategoriaRequest request);
    Task<bool> AtivarDesativarAsync(string companyId, string id, bool ativa);
    Task<bool> ExcluirAsync(string companyId, string id);
}
