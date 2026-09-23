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

    public Task<int> ImportarCargaLegadoAsync(string companyId)
        => produtosAB.ImportarCargaLegadoAsync(companyId);

    public async Task<List<ProdutoModel>> ListarVencimentosAsync(string companyId, int dias)
        => (await produtosAB.ListarVencimentosAsync(companyId, dias)).Select(ToModel).ToList();

    public Task<VencimentoResumoModel> ObterResumoVencimentosAsync(string companyId)
        => produtosAB.ObterResumoVencimentosAsync(companyId);

    public async Task<bool> AtualizarValidadeAsync(string companyId, string id, string? dataValidade)
    {
        DateTime? parsed = DateTime.TryParse(dataValidade, out var dt) ? dt : null;
        return await produtosAB.AtualizarValidadeAsync(companyId, id, parsed);
    }

    public async Task<bool> AjustarEstoqueAsync(string companyId, string id, string tipo, decimal quantidade)
    {
        if (quantidade <= 0)
            throw new InvalidOperationException("Quantidade deve ser maior que zero.");

        if (!string.Equals(tipo, "entrada", StringComparison.OrdinalIgnoreCase) &&
            !string.Equals(tipo, "saida", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Tipo deve ser 'entrada' ou 'saida'.");

        return await produtosAB.AjustarEstoqueAsync(companyId, id, tipo, quantidade);
    }

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

    private static ProdutoAD MapRequest(string id, ProdutoRequest request)
    {
        var custoUnitario = HorusMoneyFormat.ParseDecimal(request.ProductUnitPrice);
        var precoVenda = HorusMoneyFormat.ParseDecimal(request.ProductSalePrice);
        var custoMedio = HorusMoneyFormat.ParseDecimal(request.CustoMedio);
        if (custoMedio == 0) custoMedio = custoUnitario;
        var markupPraticado = custoMedio > 0 ? (precoVenda - custoMedio) / custoMedio * 100m : 0m;

        return new()
        {
            Id = id,
            ProductImageUrl = request.ProductImageUrl,
            ProductImageName = request.ProductImageName,
            ProductName = request.ProductName.Trim(),
            ProductCode = request.ProductCode.Trim(),
            ProductSupplier = request.ProductSupplier.Trim(),
            ProductDescription = request.ProductDescription.Trim(),
            ProductQnt = HorusMoneyFormat.ParseDecimal(request.ProductQnt),
            EstoqueMinimo = HorusMoneyFormat.ParseDecimal(request.EstoqueMinimo),
            ProductUnitPrice = custoUnitario,
            ProductSalePrice = precoVenda,
            TotalPriceOnProduct = HorusMoneyFormat.ParseDecimal(request.TotalPriceOnProduct),
            MargemDesejadaPercentual = string.IsNullOrWhiteSpace(request.MargemDesejadaPercentual)
                ? null
                : HorusMoneyFormat.ParseDecimal(request.MargemDesejadaPercentual),
            CategoriaId = string.IsNullOrWhiteSpace(request.CategoriaId) ? null : request.CategoriaId.Trim(),
            DataValidade = DateTime.TryParse(request.DataValidade, out var dt) ? dt : null,
            ControlaValidade = request.ControlaValidade,
            DiasAlertaValidade = request.DiasAlertaValidade <= 0 ? 15 : request.DiasAlertaValidade,
            UnidadeCompra = string.IsNullOrWhiteSpace(request.UnidadeCompra) ? "UN" : request.UnidadeCompra.Trim().ToUpperInvariant(),
            FatorConversao = Math.Max(1m, HorusMoneyFormat.ParseDecimal(request.FatorConversao)),
            QtdEmbalagem = Math.Max(1m, HorusMoneyFormat.ParseDecimal(request.QtdEmbalagem)),
            Marca = string.IsNullOrWhiteSpace(request.Marca) ? null : request.Marca.Trim(),
            Fabricante = string.IsNullOrWhiteSpace(request.Fabricante) ? null : request.Fabricante.Trim(),
            ReferenciaFabricante = string.IsNullOrWhiteSpace(request.ReferenciaFabricante) ? null : request.ReferenciaFabricante.Trim(),
            PesoLiquidoKg = HorusMoneyFormat.ParseDecimal(request.PesoLiquidoKg),
            PesoBrutoKg = HorusMoneyFormat.ParseDecimal(request.PesoBrutoKg),
            LarguraCm = HorusMoneyFormat.ParseDecimal(request.LarguraCm),
            AlturaCm = HorusMoneyFormat.ParseDecimal(request.AlturaCm),
            ComprimentoCm = HorusMoneyFormat.ParseDecimal(request.ComprimentoCm),
            EstoqueMaximo = HorusMoneyFormat.ParseDecimal(request.EstoqueMaximo),
            LocalizacaoEstoque = string.IsNullOrWhiteSpace(request.LocalizacaoEstoque) ? null : request.LocalizacaoEstoque.Trim(),
            CustoMedio = custoMedio,
            CustoComImposto = HorusMoneyFormat.ParseDecimal(request.CustoComImposto),
            CustoSemImposto = HorusMoneyFormat.ParseDecimal(request.CustoSemImposto),
            DescontoMaximoPercentual = HorusMoneyFormat.ParseDecimal(request.DescontoMaximoPercentual),
            ComissaoPercentual = HorusMoneyFormat.ParseDecimal(request.ComissaoPercentual),
            MarkupCadastrado = HorusMoneyFormat.ParseDecimal(request.MarkupCadastrado),
            MarkupPraticado = markupPraticado,
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
    }

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
        EstoqueMinimo = HorusMoneyFormat.FormatQuantity(source.EstoqueMinimo),
        ProductUnitPrice = HorusMoneyFormat.Format(source.ProductUnitPrice),
        ProductSalePrice = HorusMoneyFormat.Format(source.ProductSalePrice),
        TotalPriceOnProduct = HorusMoneyFormat.Format(source.TotalPriceOnProduct),
        MargemDesejadaPercentual = source.MargemDesejadaPercentual is { } margem ? HorusMoneyFormat.Format(margem) : null,
        CategoriaId = source.CategoriaId,
        CategoriaNome = source.CategoriaNome,
        DataValidade = source.DataValidade?.ToString("yyyy-MM-dd"),
        ControlaValidade = source.ControlaValidade,
        DiasAlertaValidade = source.DiasAlertaValidade,
        DiasRestantes = source.DataValidade.HasValue ? (int)Math.Floor((source.DataValidade.Value.Date - DateTime.UtcNow.Date).TotalDays) : null,
        UnidadeCompra = source.UnidadeCompra,
        FatorConversao = HorusMoneyFormat.FormatQuantity(source.FatorConversao),
        QtdEmbalagem = HorusMoneyFormat.FormatQuantity(source.QtdEmbalagem),
        Marca = source.Marca,
        Fabricante = source.Fabricante,
        ReferenciaFabricante = source.ReferenciaFabricante,
        PesoLiquidoKg = HorusMoneyFormat.FormatQuantity(source.PesoLiquidoKg),
        PesoBrutoKg = HorusMoneyFormat.FormatQuantity(source.PesoBrutoKg),
        LarguraCm = HorusMoneyFormat.FormatQuantity(source.LarguraCm),
        AlturaCm = HorusMoneyFormat.FormatQuantity(source.AlturaCm),
        ComprimentoCm = HorusMoneyFormat.FormatQuantity(source.ComprimentoCm),
        EstoqueMaximo = HorusMoneyFormat.FormatQuantity(source.EstoqueMaximo),
        LocalizacaoEstoque = source.LocalizacaoEstoque,
        CustoMedio = HorusMoneyFormat.Format(source.CustoMedio),
        CustoComImposto = HorusMoneyFormat.Format(source.CustoComImposto),
        CustoSemImposto = HorusMoneyFormat.Format(source.CustoSemImposto),
        DescontoMaximoPercentual = HorusMoneyFormat.FormatQuantity(source.DescontoMaximoPercentual),
        ComissaoPercentual = HorusMoneyFormat.FormatQuantity(source.ComissaoPercentual),
        MarkupCadastrado = HorusMoneyFormat.FormatQuantity(source.MarkupCadastrado),
        MarkupPraticado = HorusMoneyFormat.FormatQuantity(source.MarkupPraticado),
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
