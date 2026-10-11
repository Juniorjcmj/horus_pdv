using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using HORUSPDV_API.Models.Produtos;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Shared;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Services.Produtos;

public sealed class EntradaManualService(Connection connection, ProdutoAB produtos, FornecedorAB fornecedores,
    LoteAB lotes, NotaEntradaArquivoService arquivo)
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));
    private static string Numero(decimal value) => value.ToString("0.####", CultureInfo.GetCultureInfo("pt-BR"));

    public async Task<NfeImportResultModel> ConfirmarAsync(string companyId, EntradaManualRequest request, string userId, string userName)
    {
        if (!Guid.TryParse(request.OperacaoId, out var operation)) throw new InvalidOperationException("Identificação da entrada inválida. Abra uma nova entrada.");
        if (!DateOnly.TryParseExact(request.DataCompra, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
            throw new InvalidOperationException("Informe uma data de compra válida.");
        if (request.Itens is null || request.Itens.Count is < 1 or > 200) throw new InvalidOperationException("Adicione entre 1 e 200 produtos à entrada.");
        if (request.TipoDocumento is not ("cupom" or "recibo" or "outro")) throw new InvalidOperationException("Tipo de comprovante inválido.");
        if (request.NumeroDocumento.Length > 30 || request.Observacao.Length > 1000) throw new InvalidOperationException("Número do documento ou observação muito longo.");
        var fingerprint = Hash(JsonSerializer.Serialize(request, JsonOptions));
        var key = string.IsNullOrWhiteSpace(request.ChaveAcesso) ? null : NfeAccessKey.ValidateInvoiceKey(request.ChaveAcesso);
        var identity = Hash(key ?? "manual:" + operation.ToString("N"));
        var attachment = PrepararComprovante(request.Comprovante);
        // Uma só transação inclui produtos novos, estoque, lotes, comprovante e histórico.
        await using var db = await connection.OpenConnectionAsync();
        await using var tx = (SqlTransaction)await db.BeginTransactionAsync();
        using var scope = connection.UseImportTransaction(tx);
        foreach (var resource in new[] { "entrada-manual:" + companyId + ":" + operation.ToString("N"), "nota-entrada:" + companyId + ":" + identity })
        {
            await using var cmd = connection.CreateCommand("""
                DECLARE @result INT;
                EXEC @result=sys.sp_getapplock @Resource=@Resource,@LockMode='Exclusive',@LockOwner='Transaction',@LockTimeout=15000;
                IF @result < 0 THROW 51000, 'Esta entrada está em processamento. Tente novamente.', 1;
                """, db);
            cmd.Parameters.AddWithValue("@Resource", resource);
            await cmd.ExecuteNonQueryAsync();
        }
        var previous = await arquivo.ObterPorOperacaoManualAsync(companyId, operation.ToString("N"))
            ?? await arquivo.ObterPorIdentidadeAsync(companyId, identity);
        if (previous is { } saved)
        {
            if (saved.Entrada.TryGetProperty("operacaoId", out var id) && id.GetString() == operation.ToString("N"))
            {
                if (saved.Entrada.GetProperty("fingerprint").GetString() != fingerprint)
                    throw new InvalidOperationException("Esta entrada já foi confirmada com outros dados. Consulte o histórico e inicie uma nova entrada.");
                var replay = saved.Entrada.GetProperty("resultado").Deserialize<NfeImportResultModel>(JsonOptions)!;
                replay.NotaEntradaId = saved.Id;
                await tx.CommitAsync();
                return replay;
            }
            throw new InvalidOperationException("Esta nota já deu entrada no sistema. Consulte Notas de entrada; o estoque não foi alterado.");
        }
        var supplier = string.IsNullOrWhiteSpace(request.FornecedorId) ? null : await fornecedores.ObterAsync(companyId, request.FornecedorId);
        if (!string.IsNullOrWhiteSpace(request.FornecedorId) && supplier is null)
            throw new InvalidOperationException("Fornecedor não encontrado nesta empresa.");
        if (key is not null && supplier is not null && key.Substring(6, 14) != new string(supplier.Cnpj.Where(char.IsDigit).ToArray()))
            throw new InvalidOperationException("A chave não corresponde ao fornecedor selecionado.");

        var totals = new List<decimal>();
        var validations = new List<(EntradaManualItem Item, decimal Quantity, decimal Cost, decimal? Sale, DateTime? Expiry)>();
        var newCodes = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var index = 0; index < request.Itens.Count; index++)
        {
            var item = request.Itens[index] ?? throw new InvalidOperationException("Item inválido na entrada.");
            var purchased = Positivo(item.QuantidadeCompra, "Quantidade da compra", 1_000_000m);
            var factor = Positivo(item.FatorConversao, "Fator de conversão", 1_000_000m);
            var packCost = Positivo(item.CustoCompra, "Custo da compra", 1_000_000_000m);
            var quantity = purchased * factor;
            if (quantity > 1_000_000_000m || decimal.Round(quantity, 4) != quantity)
                throw new InvalidOperationException("Quantidade convertida deve ter no máximo quatro casas decimais e estar dentro do limite de estoque.");
            var cost = decimal.Round(packCost / factor, 4, MidpointRounding.AwayFromZero);
            if (cost <= 0) throw new InvalidOperationException("O custo convertido por unidade deve ser maior que zero.");
            decimal? sale = item.AlterarPrecoVenda || string.IsNullOrWhiteSpace(item.ProdutoExistenteId)
                ? Positivo(item.PrecoVenda, "Preço de venda", 1_000_000_000m) : null;
            if (sale is { } price && decimal.Round(price, 2) != price) throw new InvalidOperationException("Preço de venda deve ter no máximo duas casas decimais.");
            if (item.UnidadeCompra.Trim().Length is < 1 or > 6 || item.UnidadeComercial.Trim().Length is < 1 or > 6 || (item.NumeroLote?.Length ?? 0) > 60)
                throw new InvalidOperationException("Unidade ou lote inválido.");
            DateTime? expiry = null;
            if (!string.IsNullOrWhiteSpace(item.DataValidade))
            {
                if (!DateTime.TryParseExact(item.DataValidade, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var parsed))
                    throw new InvalidOperationException("Data de validade inválida.");
                expiry = parsed;
            }
            if (string.IsNullOrWhiteSpace(item.ProdutoExistenteId))
            {
                item.ProductCode = string.IsNullOrWhiteSpace(item.ProductCode) ? $"EM-{operation.ToString("N")[..12]}-{index + 1}" : item.ProductCode.Trim();
                if (item.ProductCode.Length > 50 || string.IsNullOrWhiteSpace(item.ProductName) || item.ProductName.Length > 250)
                    throw new InvalidOperationException("Informe uma descrição de até 250 caracteres e código de até 50 caracteres.");
                if (!newCodes.Add(item.ProductCode) || await produtos.ObterPorCodigoAsync(companyId, item.ProductCode) is not null)
                    throw new InvalidOperationException($"Código {item.ProductCode} já cadastrado ou repetido. Selecione o produto existente.");
                if (item.Ncm.Length != 8 || !item.Ncm.All(char.IsAsciiDigit)) throw new InvalidOperationException("NCM deve conter oito números, quando informado.");
            }
            validations.Add((item, quantity, cost, sale, expiry));
            totals.Add(decimal.Round(purchased * packCost, 2, MidpointRounding.AwayFromZero));
        }
        var result = new NfeImportResultModel();
        // Ordem estável de bloqueios de estoque evita inversão entre recebimentos simultâneos.
        foreach (var (item, quantity, cost, sale, expiry) in validations.OrderBy(v => v.Item.ProdutoExistenteId ?? v.Item.ProductCode, StringComparer.Ordinal))
        {
            ProdutoAD product;
            decimal before;
            if (!string.IsNullOrWhiteSpace(item.ProdutoExistenteId))
            {
                product = await produtos.EntradaEstoqueAsync(companyId, item.ProdutoExistenteId, quantity, cost, preservarPrecoVenda: true, precoVendaExplicito: sale);
                if (!string.Equals(item.UnidadeComercial.Trim(), product.UnidadeComercial, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("A unidade de venda do produto mudou. Atualize o produto selecionado antes de confirmar.");
                before = product.ProductQnt - quantity;
                result.ProdutosAtualizados++;
            }
            else
            {
                before = 0;
                product = new ProdutoAD {
                    Id = "pr-" + Guid.NewGuid().ToString("N"), ProductCode = item.ProductCode, ProductName = item.ProductName.Trim(),
                    ProductQnt = quantity, ProductUnitPrice = cost, ProductSalePrice = sale!.Value,
                    ProductSupplier = supplier is null ? "" : string.IsNullOrWhiteSpace(supplier.FantasyName) ? supplier.CompanyName : supplier.FantasyName,
                    TotalPriceOnProduct = cost * quantity, Lucro = sale.Value - cost,
                    CustoMedio = cost, CustoComImposto = cost, CustoSemImposto = cost,
                    MarkupPraticado = (sale.Value - cost) / cost * 100,
                    UnidadeCompra = item.UnidadeCompra.Trim().ToUpperInvariant(), FatorConversao = HorusMoneyFormat.ParseDecimal(item.FatorConversao),
                    QtdEmbalagem = HorusMoneyFormat.ParseDecimal(item.FatorConversao), UnidadeComercial = item.UnidadeComercial.Trim().ToUpperInvariant(),
                    UnidadeTributavel = item.UnidadeComercial.Trim().ToUpperInvariant(), Ncm = item.Ncm, Gtin = "SEM GTIN", CsosnIcms = "102",
                };
                await produtos.SalvarAsync(companyId, product);
                result.ProdutosCriados++;
            }
            item.ProdutoExistenteId = product.Id;
            item.ProductCode = product.ProductCode; item.ProductName = product.ProductName;
            item.UnidadeComercial = product.UnidadeComercial;
            item.Quantidade = Numero(quantity); item.PrecoCusto = Numero(cost); item.PrecoVenda = Numero(product.ProductSalePrice);
            item.EstoqueAntes = Numero(before); item.EstoqueDepois = Numero(product.ProductQnt);
            await lotes.RegistrarEntradaAsync(companyId, product.Id, expiry, quantity, item.NumeroLote, "manual", userName);
        }
        var supplierInput = new NfeImportFornecedorInput { CompanyName = supplier?.CompanyName ?? "", FantasyName = supplier?.FantasyName ?? "", Cnpj = supplier?.Cnpj ?? "" };
        var invoiceRequest = new NfeImportConfirmRequest { Fornecedor = supplierInput, Itens = request.Itens.Cast<NfeImportItemInput>().ToList() };
        var document = new NotaEntradaDocumento(identity, key, key is null ? 0 : int.Parse(key.Substring(20, 2)), request.NumeroDocumento.Trim(),
            key is null ? "" : int.Parse(key.Substring(22, 3)).ToString(), request.DataCompra, totals.Sum(), "manual", null);
        result.NotaEntradaId = await arquivo.SalvarAsync(companyId, invoiceRequest, document, userId, userName,
            new { OperacaoId = operation.ToString("N"), Fingerprint = fingerprint, request.DataCompra, request.TipoDocumento, request.Observacao,
                Fornecedor = supplierInput, request.Itens, Resultado = result },
            attachment?.Bytes, attachment?.Name, attachment?.Type, totals.Sum(), operation.ToString("N"));
        await tx.CommitAsync();
        return result;
    }

    private static decimal Positivo(string value, string field, decimal maximum)
    {
        if (!HorusMoneyFormat.TryParseDecimal(value, out var number) || number <= 0 || number > maximum || decimal.Round(number, 4) != number)
            throw new InvalidOperationException($"{field} deve ser maior que zero, com até quatro casas decimais e dentro do limite permitido.");
        return number;
    }

    private static (byte[] Bytes, string Name, string Type)? PrepararComprovante(EntradaManualComprovante? file)
    {
        if (file is null) return null;
        if (file.Base64.Length > 14_000_000) throw new InvalidOperationException("Comprovante deve ter no máximo 10 MB.");
        byte[] bytes;
        try { bytes = Convert.FromBase64String(file.Base64); }
        catch (FormatException) { throw new InvalidOperationException("Comprovante inválido. Selecione o arquivo novamente."); }
        var name = Path.GetFileName(file.Nome.Replace('\\', '/'));
        if (bytes.Length is 0 or > 10_485_760 || name.Length is 0 or > 180 || name.Any(char.IsControl)) throw new InvalidOperationException("Nome ou tamanho do comprovante inválido.");
        var ext = Path.GetExtension(name).ToLowerInvariant();
        var type = ext switch { ".pdf" => "application/pdf", ".png" => "image/png", ".jpg" or ".jpeg" => "image/jpeg", ".xml" => "application/xml", _ => null };
        var valid = ext switch {
            ".pdf" => bytes.AsSpan().StartsWith("%PDF-"u8),
            ".png" => bytes.AsSpan().StartsWith(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }),
            ".jpg" or ".jpeg" => bytes.AsSpan().StartsWith(new byte[] { 255, 216, 255 }),
            ".xml" => true, _ => false };
        if (type is null || !valid) throw new InvalidOperationException("Selecione uma imagem JPG/PNG, PDF ou XML válido.");
        return (bytes, name, type);
    }
}
