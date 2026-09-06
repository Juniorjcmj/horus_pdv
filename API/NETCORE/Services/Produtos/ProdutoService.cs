/**
 * Arquivo: API/NETCORE/Services/Produtos/ProdutoService.cs
 * Objetivo: centraliza regras de negócio de cadastro, estoque, manutenção e dados fiscais de
 *           produtos antes do acesso ao banco ou resposta HTTP.
 * Entradas esperadas: recebe requisições já validadas pelos controladores e aplica consistência operacional do domínio.
 */
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Produtos;

public class ProdutoService(ProdutoAB produtosAB, FornecedorAB fornecedoresAB) : IProdutoService
{
    public async Task<List<ProdutoModel>> ListarAsync(string companyId)
        => (await produtosAB.ListarAsync(companyId)).Select(ToModel).ToList();

    public async Task<ProdutoModel> CriarAsync(string companyId, ProdutoRequest request)
    {
        Validate(request);
        await ValidateBusinessRulesAsync(companyId, request, null);
        var product = MapRequest($"pr-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}", request);
        return ToModel(await produtosAB.SalvarAsync(companyId, product));
    }

    public async Task<ProdutoModel?> AtualizarAsync(string companyId, string id, ProdutoRequest request)
    {
        Validate(request);
        var current = await produtosAB.ObterAsync(companyId, id);
        if (current is null)
        {
            return null;
        }

        await ValidateBusinessRulesAsync(companyId, request, id);
        return ToModel(await produtosAB.SalvarAsync(companyId, MapRequest(id, request)));
    }

    public Task<bool> ExcluirAsync(string companyId, string id)
        => produtosAB.ExcluirAsync(companyId, id);

    private static void Validate(ProdutoRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.ProductName) || request.ProductName.Trim().Length < 3)
        {
            throw new InvalidOperationException("Nome do produto deve ter no minimo 3 caracteres.");
        }

        if (string.IsNullOrWhiteSpace(request.ProductCode))
        {
            throw new InvalidOperationException("Codigo do produto e obrigatorio.");
        }

        if (string.IsNullOrWhiteSpace(request.ProductSupplier))
        {
            throw new InvalidOperationException("Fornecedor do produto e obrigatorio.");
        }

        if (HorusMoneyFormat.ParseDecimal(request.ProductQnt) <= 0)
        {
            throw new InvalidOperationException("Quantidade do produto deve ser maior que zero.");
        }

        if (HorusMoneyFormat.ParseDecimal(request.ProductUnitPrice) <= 0)
        {
            throw new InvalidOperationException("Preco de custo deve ser maior que zero.");
        }

        if (HorusMoneyFormat.ParseDecimal(request.ProductSalePrice) <= 0)
        {
            throw new InvalidOperationException("Preco de venda deve ser maior que zero.");
        }
    }

    private async Task ValidateBusinessRulesAsync(string companyId, ProdutoRequest request, string? currentId)
    {
        var products = await produtosAB.ListarAsync(companyId);
        var productCode = request.ProductCode.Trim();
        if (products.Any(item =>
                item.Id != currentId &&
                item.ProductCode.Equals(productCode, StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("Já existe produto com este código.");
        }

        var suppliers = await fornecedoresAB.ListarAsync(companyId);
        var supplierName = request.ProductSupplier.Trim();
        if (!suppliers.Any(item =>
                item.FantasyName.Equals(supplierName, StringComparison.OrdinalIgnoreCase) ||
                item.CompanyName.Equals(supplierName, StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("Fornecedor informado não está cadastrado.");
        }
    }

    private static ProdutoAD MapRequest(string id, ProdutoRequest request) => new()
    {
        Id = id,
        ProductImageUrl = request.ProductImageUrl,
        ProductImageName = request.ProductImageName,
        ProductName = request.ProductName.Trim(),
        ProductCode = request.ProductCode.Trim(),
        ProductSupplier = request.ProductSupplier.Trim(),
        ProductDescription = request.ProductDescription.Trim(),
        ProductQnt = HorusMoneyFormat.ParseDecimal(request.ProductQnt),
        ProductUnitPrice = HorusMoneyFormat.ParseDecimal(request.ProductUnitPrice),
        ProductSalePrice = HorusMoneyFormat.ParseDecimal(request.ProductSalePrice),
        TotalPriceOnProduct = HorusMoneyFormat.ParseDecimal(request.TotalPriceOnProduct),
        Ncm = string.IsNullOrWhiteSpace(request.Ncm) ? "00000000" : request.Ncm.Trim(),
        Cest = string.IsNullOrWhiteSpace(request.Cest) ? null : request.Cest.Trim(),
        Cfop = string.IsNullOrWhiteSpace(request.Cfop) ? "5102" : request.Cfop.Trim(),
        OrigemMercadoria = request.OrigemMercadoria,
        UnidadeComercial = string.IsNullOrWhiteSpace(request.UnidadeComercial) ? "UN" : request.UnidadeComercial.Trim().ToUpperInvariant(),
        UnidadeTributavel = string.IsNullOrWhiteSpace(request.UnidadeTributavel) ? "UN" : request.UnidadeTributavel.Trim().ToUpperInvariant(),
        Gtin = string.IsNullOrWhiteSpace(request.Gtin) ? "SEM GTIN" : request.Gtin.Trim(),
        CsosnIcms = string.IsNullOrWhiteSpace(request.CsosnIcms) ? null : request.CsosnIcms.Trim(),
        CstIcms = string.IsNullOrWhiteSpace(request.CstIcms) ? null : request.CstIcms.Trim(),
        AliquotaIcms = HorusMoneyFormat.ParseDecimal(request.AliquotaIcms),
        CstPis = string.IsNullOrWhiteSpace(request.CstPis) ? "07" : request.CstPis.Trim(),
        CstCofins = string.IsNullOrWhiteSpace(request.CstCofins) ? "07" : request.CstCofins.Trim(),
        CstIbsCbs = string.IsNullOrWhiteSpace(request.CstIbsCbs) ? null : request.CstIbsCbs.Trim(),
        CClassTrib = string.IsNullOrWhiteSpace(request.CClassTrib) ? null : request.CClassTrib.Trim()
    };

    private static ProdutoModel ToModel(ProdutoAD source) => new()
    {
        Id = source.Id,
        ProductImageUrl = source.ProductImageUrl,
        ProductImageName = source.ProductImageName,
        ProductName = source.ProductName,
        ProductCode = source.ProductCode,
        ProductSupplier = source.ProductSupplier,
        ProductDescription = source.ProductDescription,
        ProductQnt = HorusMoneyFormat.FormatQuantity(source.ProductQnt),
        ProductUnitPrice = HorusMoneyFormat.Format(source.ProductUnitPrice),
        ProductSalePrice = HorusMoneyFormat.Format(source.ProductSalePrice),
        TotalPriceOnProduct = HorusMoneyFormat.Format(source.TotalPriceOnProduct),
        Ncm = source.Ncm,
        Cest = source.Cest,
        Cfop = source.Cfop,
        OrigemMercadoria = source.OrigemMercadoria,
        UnidadeComercial = source.UnidadeComercial,
        UnidadeTributavel = source.UnidadeTributavel,
        Gtin = source.Gtin,
        CsosnIcms = source.CsosnIcms,
        CstIcms = source.CstIcms,
        AliquotaIcms = HorusMoneyFormat.Format(source.AliquotaIcms),
        CstPis = source.CstPis,
        CstCofins = source.CstCofins,
        CstIbsCbs = source.CstIbsCbs,
        CClassTrib = source.CClassTrib
    };
}
