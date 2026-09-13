/**
 * Arquivo: API/NETCORE/Services/Promocoes/PromocaoService.cs
 * Objetivo: implementação das regras de negócio de promoções e preços dinâmicos.
 */
using HORUSPDV_API.Models.Promocoes;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;

namespace HORUSPDV_API.Services.Promocoes;

public class PromocaoService(PromocaoAB promocaoAb, CategoriaAB categoriaAb) : IPromocaoService
{
    private static readonly HashSet<string> TiposValidos = new(StringComparer.OrdinalIgnoreCase)
    {
        "desconto_percentual",
        "desconto_valor",
        "preco_fixo",
        "leve_x_pague_y",
        "combo_quantidade",
        "preco_atacado"
    };

    public async Task<List<PromocaoModel>> ListarAsync(string companyId)
    {
        var list = await promocaoAb.ListarAsync(companyId);
        return list.Select(ToModel).ToList();
    }

    public async Task<List<PromocaoModel>> ListarAtivasAsync(string companyId)
    {
        var list = await promocaoAb.ListarAtivasAsync(companyId);
        return list.Select(ToModel).ToList();
    }

    public async Task<PromocaoModel?> ObterPorIdAsync(string companyId, string id)
    {
        var ad = await promocaoAb.ObterPorIdAsync(companyId, id);
        return ad is null ? null : ToModel(ad);
    }

    public async Task<PromocaoModel> CriarAsync(string companyId, string operadorNome, PromocaoRequest request)
    {
        await ValidarAsync(companyId, request);

        var entity = new PromocaoAD
        {
            Id = Guid.NewGuid().ToString(),
            CompanyId = companyId,
            Nome = request.Nome.Trim(),
            Tipo = request.Tipo.Trim().ToLowerInvariant(),
            ValorDesconto = request.ValorDesconto,
            PrecoFixo = request.PrecoFixo,
            QuantidadeLeva = request.QuantidadeLeva,
            QuantidadePaga = request.QuantidadePaga,
            QuantidadeMinima = request.QuantidadeMinima,
            InicioVigencia = request.InicioVigencia,
            FimVigencia = request.FimVigencia,
            Ativa = request.Ativa,
            CategoriaId = string.IsNullOrWhiteSpace(request.CategoriaId) ? null : request.CategoriaId.Trim(),
            CriadoPor = string.IsNullOrWhiteSpace(operadorNome) ? "Operador" : operadorNome.Trim(),
            CriadoEm = DateTimeOffset.Now,
            ProdutoIds = request.ProdutoIds.Where(p => !string.IsNullOrWhiteSpace(p)).Select(p => p.Trim()).Distinct().ToList()
        };

        var saved = await promocaoAb.SalvarAsync(companyId, entity);
        return ToModel(saved);
    }

    public async Task<PromocaoModel> AtualizarAsync(string companyId, string id, PromocaoRequest request)
    {
        var existing = await promocaoAb.ObterPorIdAsync(companyId, id);
        if (existing is null)
        {
            throw new KeyNotFoundException($"Promoção {id} não encontrada.");
        }

        await ValidarAsync(companyId, request);

        existing.Nome = request.Nome.Trim();
        existing.Tipo = request.Tipo.Trim().ToLowerInvariant();
        existing.ValorDesconto = request.ValorDesconto;
        existing.PrecoFixo = request.PrecoFixo;
        existing.QuantidadeLeva = request.QuantidadeLeva;
        existing.QuantidadePaga = request.QuantidadePaga;
        existing.QuantidadeMinima = request.QuantidadeMinima;
        existing.InicioVigencia = request.InicioVigencia;
        existing.FimVigencia = request.FimVigencia;
        existing.Ativa = request.Ativa;
        existing.CategoriaId = string.IsNullOrWhiteSpace(request.CategoriaId) ? null : request.CategoriaId.Trim();
        existing.ProdutoIds = request.ProdutoIds.Where(p => !string.IsNullOrWhiteSpace(p)).Select(p => p.Trim()).Distinct().ToList();

        var saved = await promocaoAb.SalvarAsync(companyId, existing);
        return ToModel(saved);
    }

    public async Task AtivarDesativarAsync(string companyId, string id, bool ativa)
    {
        var existing = await promocaoAb.ObterPorIdAsync(companyId, id);
        if (existing is null)
        {
            throw new KeyNotFoundException($"Promoção {id} não encontrada.");
        }

        await promocaoAb.AtivarDesativarAsync(companyId, id, ativa);
    }

    public async Task ExcluirAsync(string companyId, string id)
    {
        var existing = await promocaoAb.ObterPorIdAsync(companyId, id);
        if (existing is null)
        {
            throw new KeyNotFoundException($"Promoção {id} não encontrada.");
        }

        await promocaoAb.ExcluirAsync(companyId, id);
    }

    public async Task<PromocaoResultadoModel> ObterResultadoAsync(string companyId, string id)
    {
        var res = await promocaoAb.ObterResultadoAsync(companyId, id)
            ?? throw new KeyNotFoundException($"Promoção {id} não encontrada.");
        return new PromocaoResultadoModel
        {
            PromocaoId = res.PromocaoId,
            Nome = res.Nome,
            QuantidadeVendas = res.QuantidadeVendas,
            ReceitaBruta = res.ReceitaBruta,
            ReceitaLiquida = res.ReceitaLiquida,
            DescontoTotal = res.DescontoTotal,
            MargemLiquida = res.MargemLiquida
        };
    }

    private async Task ValidarAsync(string companyId, PromocaoRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Nome))
        {
            throw new InvalidOperationException("O nome da promoção é obrigatório.");
        }

        var tipo = request.Tipo?.Trim().ToLowerInvariant() ?? string.Empty;
        if (!TiposValidos.Contains(tipo))
        {
            throw new InvalidOperationException($"Tipo de promoção '{request.Tipo}' é inválido. Tipos suportados: {string.Join(", ", TiposValidos)}.");
        }

        if (request.FimVigencia <= request.InicioVigencia)
        {
            throw new InvalidOperationException("A data final de vigência deve ser posterior à data inicial.");
        }

        switch (tipo)
        {
            case "desconto_percentual":
                if (request.ValorDesconto is null or <= 0 or > 100)
                {
                    throw new InvalidOperationException("Desconto percentual deve ser maior que 0 e no máximo 100%.");
                }
                break;

            case "desconto_valor":
                if (request.ValorDesconto is null or <= 0)
                {
                    throw new InvalidOperationException("Valor de desconto deve ser maior que zero.");
                }
                break;

            case "preco_fixo":
                if (request.PrecoFixo is null or <= 0)
                {
                    throw new InvalidOperationException("Preço fixo deve ser maior que zero.");
                }
                break;

            case "leve_x_pague_y":
                if (request.QuantidadeLeva is null or <= 0 || request.QuantidadePaga is null or <= 0)
                {
                    throw new InvalidOperationException("Quantidade 'leva' e 'paga' devem ser maiores que zero.");
                }
                if (request.QuantidadeLeva <= request.QuantidadePaga)
                {
                    throw new InvalidOperationException("A quantidade 'leva' deve ser maior que a quantidade 'paga'.");
                }
                break;

            case "combo_quantidade":
            case "preco_atacado":
                if (request.QuantidadeMinima is null or <= 1)
                {
                    throw new InvalidOperationException("Quantidade mínima deve ser maior que 1.");
                }
                if ((request.PrecoFixo is null or <= 0) && (request.ValorDesconto is null or <= 0))
                {
                    throw new InvalidOperationException("Informe um preço unitário promocional ou valor de desconto para o combo/atacado.");
                }
                break;
        }

        if (request.ProdutoIds.Count == 0 && string.IsNullOrWhiteSpace(request.CategoriaId))
        {
            throw new InvalidOperationException("A promoção deve estar vinculada a pelo menos um produto ou a uma categoria.");
        }

        if (!string.IsNullOrWhiteSpace(request.CategoriaId))
        {
            var cat = await categoriaAb.ObterPorIdAsync(companyId, request.CategoriaId.Trim());
            if (cat is null)
            {
                throw new InvalidOperationException("Categoria informada não foi encontrada.");
            }
        }
    }

    private static PromocaoModel ToModel(PromocaoAD ad) => new()
    {
        Id = ad.Id,
        CompanyId = ad.CompanyId,
        Nome = ad.Nome,
        Tipo = ad.Tipo,
        ValorDesconto = ad.ValorDesconto,
        PrecoFixo = ad.PrecoFixo,
        QuantidadeLeva = ad.QuantidadeLeva,
        QuantidadePaga = ad.QuantidadePaga,
        QuantidadeMinima = ad.QuantidadeMinima,
        InicioVigencia = ad.InicioVigencia,
        FimVigencia = ad.FimVigencia,
        Ativa = ad.Ativa,
        CategoriaId = ad.CategoriaId,
        CategoriaNome = ad.CategoriaNome,
        CriadoPor = ad.CriadoPor,
        CriadoEm = ad.CriadoEm,
        ProdutoIds = ad.ProdutoIds
    };
}
