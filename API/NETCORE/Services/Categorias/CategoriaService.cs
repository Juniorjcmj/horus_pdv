/**
 * Arquivo: API/NETCORE/Services/Categorias/CategoriaService.cs
 * Objetivo: implementação das regras de negócio de categorias e departamentos de produtos.
 */
using HORUSPDV_API.Models.Categorias;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;

namespace HORUSPDV_API.Services.Categorias;

public class CategoriaService(CategoriaAB categoriaAb) : ICategoriaService
{
    public async Task<List<CategoriaModel>> ListarArvoreAsync(string companyId, bool apenasAtivas = false)
    {
        var roots = await categoriaAb.ListarArvoreAsync(companyId, apenasAtivas);
        return roots.Select(ToModelWithChildren).ToList();
    }

    public async Task<List<CategoriaModel>> ListarTodasAsync(string companyId, bool apenasAtivas = false)
    {
        var list = await categoriaAb.ListarTodasAsync(companyId, apenasAtivas);
        return list.Select(ToModel).ToList();
    }

    public async Task<CategoriaModel?> ObterPorIdAsync(string companyId, string id)
    {
        var ad = await categoriaAb.ObterPorIdAsync(companyId, id);
        return ad is null ? null : ToModel(ad);
    }

    public async Task<CategoriaModel> CriarAsync(string companyId, CategoriaRequest request)
    {
        Validar(request);

        if (!string.IsNullOrWhiteSpace(request.CategoriaPaiId))
        {
            var pai = await categoriaAb.ObterPorIdAsync(companyId, request.CategoriaPaiId);
            if (pai is null)
            {
                throw new InvalidOperationException("Departamento pai especificado não foi encontrado.");
            }
            if (!string.IsNullOrEmpty(pai.CategoriaPaiId))
            {
                throw new InvalidOperationException("A hierarquia de categorias permite apenas 2 níveis (Departamento -> Subcategoria).");
            }
        }

        var entity = new CategoriaAD
        {
            Id = Guid.NewGuid().ToString(),
            CompanyId = companyId,
            Nome = request.Nome.Trim(),
            CategoriaPaiId = string.IsNullOrWhiteSpace(request.CategoriaPaiId) ? null : request.CategoriaPaiId.Trim(),
            Ordem = request.Ordem,
            Ativa = request.Ativa
        };

        var saved = await categoriaAb.SalvarAsync(companyId, entity);
        return ToModel(saved);
    }

    public async Task<CategoriaModel?> AtualizarAsync(string companyId, string id, CategoriaRequest request)
    {
        Validar(request);

        var existing = await categoriaAb.ObterPorIdAsync(companyId, id);
        if (existing is null)
        {
            return null;
        }

        if (!string.IsNullOrWhiteSpace(request.CategoriaPaiId))
        {
            if (string.Equals(request.CategoriaPaiId.Trim(), id, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException("Uma categoria não pode ser subcategoria dela mesma.");
            }

            var pai = await categoriaAb.ObterPorIdAsync(companyId, request.CategoriaPaiId);
            if (pai is null)
            {
                throw new InvalidOperationException("Departamento pai especificado não foi encontrado.");
            }
            if (!string.IsNullOrEmpty(pai.CategoriaPaiId))
            {
                throw new InvalidOperationException("A hierarquia de categorias permite apenas 2 níveis (Departamento -> Subcategoria).");
            }
        }

        existing.Nome = request.Nome.Trim();
        existing.CategoriaPaiId = string.IsNullOrWhiteSpace(request.CategoriaPaiId) ? null : request.CategoriaPaiId.Trim();
        existing.Ordem = request.Ordem;
        existing.Ativa = request.Ativa;

        var saved = await categoriaAb.SalvarAsync(companyId, existing);
        return ToModel(saved);
    }

    public async Task<bool> AtivarDesativarAsync(string companyId, string id, bool ativa)
    {
        var existing = await categoriaAb.ObterPorIdAsync(companyId, id);
        if (existing is null)
        {
            return false;
        }

        return await categoriaAb.AtivarDesativarAsync(companyId, id, ativa);
    }

    public async Task<bool> ExcluirAsync(string companyId, string id)
    {
        return await categoriaAb.ExcluirAsync(companyId, id);
    }

    private static void Validar(CategoriaRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Nome))
        {
            throw new InvalidOperationException("O nome da categoria é obrigatório.");
        }
        if (request.Nome.Trim().Length > 80)
        {
            throw new InvalidOperationException("O nome da categoria deve ter no máximo 80 caracteres.");
        }
    }

    private static CategoriaModel ToModel(CategoriaAD ad) => new()
    {
        Id = ad.Id,
        Nome = ad.Nome,
        CategoriaPaiId = ad.CategoriaPaiId,
        CategoriaPaiNome = ad.CategoriaPaiNome,
        Ordem = ad.Ordem,
        Ativa = ad.Ativa,
        QuantidadeProdutos = ad.QuantidadeProdutos,
        Subcategorias = []
    };

    private static CategoriaModel ToModelWithChildren(CategoriaAD ad) => new()
    {
        Id = ad.Id,
        Nome = ad.Nome,
        CategoriaPaiId = ad.CategoriaPaiId,
        CategoriaPaiNome = ad.CategoriaPaiNome,
        Ordem = ad.Ordem,
        Ativa = ad.Ativa,
        QuantidadeProdutos = ad.QuantidadeProdutos,
        Subcategorias = ad.Subcategorias.Select(ToModel).ToList()
    };
}
