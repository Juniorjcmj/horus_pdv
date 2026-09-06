/**
 * Arquivo: API/NETCORE/Services/Produtos/NfeImportService.cs
 * Objetivo: orquestra a importação de produtos a partir do XML de uma NF-e de compra do
 *           fornecedor — pré-visualização (só leitura) e confirmação (grava fornecedor,
 *           produtos novos e entrada de estoque dos já existentes).
 * Entradas esperadas: recebe o XML bruto na pré-visualização e a lista revisada/editada pelo
 *           operador na confirmação.
 *
 * Importante: os dados fiscais de VENDA do produto (CFOP, CSOSN/CST, PIS/COFINS) NÃO vêm do XML
 * de compra — aquele reflete a operação de ENTRADA do fornecedor, com CFOP/CST diferentes dos
 * que a loja usa para vender ao consumidor final na NFC-e. Só os dados de identificação da
 * mercadoria (NCM, CEST, GTIN, unidade) são reaproveitados; o resto usa os mesmos defaults do
 * cadastro manual (ver ProductRegisterPage.tsx EMPTY_FORM).
 */
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Produtos;

public class NfeImportService(ProdutoAB produtosAB, FornecedorAB fornecedoresAB)
{
    public async Task<NfeImportPreviewModel> PreVisualizarAsync(string companyId, NfeImportPreviewRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.XmlBase64))
        {
            throw new InvalidOperationException("Nenhum arquivo XML enviado.");
        }

        byte[] xmlBytes;
        try
        {
            xmlBytes = Convert.FromBase64String(request.XmlBase64);
        }
        catch (FormatException ex)
        {
            throw new InvalidOperationException("Arquivo XML inválido (base64 corrompido).", ex);
        }

        var parsed = NfeXmlParser.Parse(xmlBytes);
        var fornecedorExistente = await fornecedoresAB.ObterPorCnpjAsync(companyId, parsed.Emitente.Cnpj);

        var fornecedorPreview = fornecedorExistente is not null
            ? new NfeImportFornecedorPreview
            {
                JaExiste = true,
                Cnpj = fornecedorExistente.Cnpj,
                CompanyName = fornecedorExistente.CompanyName,
                FantasyName = fornecedorExistente.FantasyName,
                Cep = fornecedorExistente.Cep,
                City = fornecedorExistente.City,
                State = fornecedorExistente.State,
                Address = fornecedorExistente.Address,
                Neighborhood = fornecedorExistente.Neighborhood,
                Number = fornecedorExistente.Number,
                Telephone = fornecedorExistente.Telephone,
            }
            : new NfeImportFornecedorPreview
            {
                JaExiste = false,
                Cnpj = parsed.Emitente.Cnpj,
                CompanyName = parsed.Emitente.RazaoSocial,
                FantasyName = parsed.Emitente.NomeFantasia ?? parsed.Emitente.RazaoSocial,
                Cep = parsed.Emitente.Cep,
                City = parsed.Emitente.Municipio,
                State = parsed.Emitente.Uf,
                Address = parsed.Emitente.Logradouro,
                Neighborhood = parsed.Emitente.Bairro,
                Number = parsed.Emitente.Numero,
                Telephone = parsed.Emitente.Telefone ?? string.Empty,
            };

        var itens = new List<NfeImportItemPreview>();
        foreach (var item in parsed.Itens)
        {
            var existente = item.Gtin is not null
                ? await produtosAB.ObterPorGtinAsync(companyId, item.Gtin)
                : null;

            itens.Add(new NfeImportItemPreview
            {
                NumeroItem = item.NumeroItem,
                ProdutoExistenteId = existente?.Id,
                ProdutoExistenteNome = existente?.ProductName,
                ProductCode = existente?.ProductCode ?? (item.Gtin ?? item.CodigoFornecedor),
                ProductName = item.Descricao,
                Gtin = item.Gtin ?? "SEM GTIN",
                Ncm = item.Ncm,
                Cest = item.Cest,
                UnidadeComercial = item.UnidadeComercial,
                Quantidade = HorusMoneyFormat.FormatQuantity(item.Quantidade),
                PrecoCusto = HorusMoneyFormat.Format(item.ValorUnitario),
                PrecoVendaSugerido = HorusMoneyFormat.Format(existente?.ProductSalePrice ?? item.ValorUnitario),
            });
        }

        return new NfeImportPreviewModel
        {
            NumeroNota = parsed.NumeroNota,
            Serie = parsed.Serie,
            Fornecedor = fornecedorPreview,
            Itens = itens,
        };
    }

    public async Task<NfeImportResultModel> ConfirmarAsync(string companyId, NfeImportConfirmRequest request)
    {
        if (request.Itens.Count == 0)
        {
            throw new InvalidOperationException("Nenhum item para importar.");
        }

        var cnpjDigits = new string(request.Fornecedor.Cnpj.Where(char.IsDigit).ToArray());
        if (cnpjDigits.Length != 14)
        {
            throw new InvalidOperationException("CNPJ do fornecedor inválido.");
        }

        if (string.IsNullOrWhiteSpace(request.Fornecedor.CompanyName))
        {
            throw new InvalidOperationException("Razão social do fornecedor é obrigatória.");
        }

        // Códigos duplicados dentro do próprio lote (dois itens da nota sugerindo o mesmo código).
        var codigosNovos = request.Itens
            .Where(item => string.IsNullOrWhiteSpace(item.ProdutoExistenteId))
            .Select(item => item.ProductCode.Trim())
            .ToList();
        var codigoDuplicadoNoLote = codigosNovos
            .GroupBy(code => code, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault(group => group.Count() > 1);
        if (codigoDuplicadoNoLote is not null)
        {
            throw new InvalidOperationException(
                $"Código de produto \"{codigoDuplicadoNoLote.Key}\" repetido em mais de um item da nota — ajuste antes de confirmar.");
        }

        var produtosAtuais = await produtosAB.ListarAsync(companyId);
        var codigoJaCadastrado = codigosNovos.FirstOrDefault(code =>
            produtosAtuais.Any(p => p.ProductCode.Equals(code, StringComparison.OrdinalIgnoreCase)));
        if (codigoJaCadastrado is not null)
        {
            throw new InvalidOperationException(
                $"Já existe produto cadastrado com o código \"{codigoJaCadastrado}\" — ajuste antes de confirmar.");
        }

        var fantasyName = string.IsNullOrWhiteSpace(request.Fornecedor.FantasyName)
            ? request.Fornecedor.CompanyName.Trim()
            : request.Fornecedor.FantasyName.Trim();

        var fornecedorExistente = await fornecedoresAB.ObterPorCnpjAsync(companyId, request.Fornecedor.Cnpj);
        var fornecedor = new FornecedorAD
        {
            Id = fornecedorExistente?.Id ?? $"fr-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}",
            CompanyName = request.Fornecedor.CompanyName.Trim(),
            FantasyName = fantasyName,
            Cnpj = request.Fornecedor.Cnpj.Trim(),
            Cep = request.Fornecedor.Cep.Trim(),
            City = request.Fornecedor.City.Trim(),
            State = request.Fornecedor.State.Trim(),
            Address = request.Fornecedor.Address.Trim(),
            Neighborhood = request.Fornecedor.Neighborhood.Trim(),
            StreetComplement = fornecedorExistente?.StreetComplement ?? string.Empty,
            Number = request.Fornecedor.Number.Trim(),
            ReferencePoint = fornecedorExistente?.ReferencePoint ?? string.Empty,
            Telephone = string.IsNullOrWhiteSpace(request.Fornecedor.Telephone)
                ? fornecedorExistente?.Telephone ?? string.Empty
                : request.Fornecedor.Telephone.Trim(),
            Cellphone = fornecedorExistente?.Cellphone ?? request.Fornecedor.Telephone.Trim(),
            Email = fornecedorExistente?.Email ?? string.Empty,
        };
        await fornecedoresAB.SalvarAsync(companyId, fornecedor);

        var resultado = new NfeImportResultModel { FornecedorCriado = fornecedorExistente is null };

        var indice = 0;
        foreach (var item in request.Itens)
        {
            indice++;
            var quantidade = HorusMoneyFormat.ParseDecimal(item.Quantidade);
            var precoCusto = HorusMoneyFormat.ParseDecimal(item.PrecoCusto);

            if (quantidade <= 0)
            {
                throw new InvalidOperationException($"Quantidade inválida no item \"{item.ProductName}\".");
            }

            if (!string.IsNullOrWhiteSpace(item.ProdutoExistenteId))
            {
                await produtosAB.EntradaEstoqueAsync(companyId, item.ProdutoExistenteId, quantidade, precoCusto);
                resultado.ProdutosAtualizados++;
                continue;
            }

            var precoVenda = HorusMoneyFormat.ParseDecimal(item.PrecoVenda);
            if (precoVenda <= 0)
            {
                throw new InvalidOperationException($"Preço de venda deve ser maior que zero no item \"{item.ProductName}\".");
            }

            if (string.IsNullOrWhiteSpace(item.ProductName) || item.ProductName.Trim().Length < 3)
            {
                throw new InvalidOperationException($"Nome do produto muito curto no item {item.NumeroItem}.");
            }

            var novoProduto = new ProdutoAD
            {
                Id = $"pr-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}-{indice}",
                ProductName = item.ProductName.Trim(),
                ProductCode = item.ProductCode.Trim(),
                ProductSupplier = fantasyName,
                ProductDescription = string.Empty,
                ProductQnt = quantidade,
                ProductUnitPrice = precoCusto,
                ProductSalePrice = precoVenda,
                TotalPriceOnProduct = precoCusto * quantidade,
                Ncm = string.IsNullOrWhiteSpace(item.Ncm) ? "00000000" : item.Ncm.Trim(),
                Cest = string.IsNullOrWhiteSpace(item.Cest) ? null : item.Cest.Trim(),
                Cfop = "5102",
                OrigemMercadoria = 0,
                UnidadeComercial = string.IsNullOrWhiteSpace(item.UnidadeComercial) ? "UN" : item.UnidadeComercial.Trim().ToUpperInvariant(),
                UnidadeTributavel = string.IsNullOrWhiteSpace(item.UnidadeComercial) ? "UN" : item.UnidadeComercial.Trim().ToUpperInvariant(),
                Gtin = string.IsNullOrWhiteSpace(item.Gtin) ? "SEM GTIN" : item.Gtin.Trim(),
                CsosnIcms = "102",
                CstIcms = null,
                AliquotaIcms = 0,
                CstPis = "07",
                CstCofins = "07",
                CstIbsCbs = null,
                CClassTrib = null,
            };
            await produtosAB.SalvarAsync(companyId, novoProduto);
            resultado.ProdutosCriados++;
        }

        return resultado;
    }
}
