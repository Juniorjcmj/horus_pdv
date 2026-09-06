/**
 * Arquivo: API/NETCORE/Models/Requests/ProdutoRequest.cs
 * Objetivo: define contrato de entrada para operações de cadastro, estoque, manutenção e dados
 *           fiscais de produtos.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 *
 * Quantidade e preços seguem string pt-BR ("1.234,56"), como o resto do contrato HTTP hoje —
 * ProdutoService converte para `decimal` na borda (HorusMoneyFormat). ProductQnt aceita até
 * 4 casas decimais para produtos vendidos por peso/volume (ex.: "0,452").
 */
namespace HORUSPDV_API.Models.Requests;

public class ProdutoRequest
{
    public string ProductImageUrl { get; set; } = string.Empty;
    public string ProductImageName { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string ProductCode { get; set; } = string.Empty;
    public string ProductSupplier { get; set; } = string.Empty;
    public string ProductDescription { get; set; } = string.Empty;
    public string ProductQnt { get; set; } = string.Empty;
    public string ProductUnitPrice { get; set; } = string.Empty;
    public string ProductSalePrice { get; set; } = string.Empty;
    public string TotalPriceOnProduct { get; set; } = string.Empty;

    // Dados fiscais (NFC-e modelo 65)
    public string Ncm { get; set; } = "00000000";
    public string? Cest { get; set; }
    public string Cfop { get; set; } = "5102";
    public byte OrigemMercadoria { get; set; }
    public string UnidadeComercial { get; set; } = "UN";
    public string UnidadeTributavel { get; set; } = "UN";
    public string Gtin { get; set; } = "SEM GTIN";
    public string? CsosnIcms { get; set; }
    public string? CstIcms { get; set; }
    public string AliquotaIcms { get; set; } = "0,00";
    public string CstPis { get; set; } = "07";
    public string CstCofins { get; set; } = "07";
    public string? CstIbsCbs { get; set; }
    public string? CClassTrib { get; set; }
}
