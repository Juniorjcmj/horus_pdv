/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/ProdutoAD.cs
 * Objetivo: representa estrutura de dados de cadastro, estoque, manutenção e dados fiscais de
 *           produtos retornada pelo acesso ao banco.
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 *
 * ProductQnt/ProductUnitPrice/ProductSalePrice/TotalPriceOnProduct são `decimal` nativo
 * (colunas convertidas de NVARCHAR por 01_migracao_valores.sql) — a formatação pt-BR usada
 * no contrato HTTP acontece na borda (ProdutoService), via HorusMoneyFormat.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class ProdutoAD
{
    public string Id { get; set; } = string.Empty;
    public string ProductImageUrl { get; set; } = string.Empty;
    public string ProductImageName { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string ProductCode { get; set; } = string.Empty;
    public string ProductSupplier { get; set; } = string.Empty;
    public string ProductDescription { get; set; } = string.Empty;
    public decimal ProductQnt { get; set; }
    public decimal ProductUnitPrice { get; set; }
    public decimal ProductSalePrice { get; set; }
    public decimal TotalPriceOnProduct { get; set; }

    /// <summary>% de lucro desejado sobre o custo (ex.: 30 = 30%). Null = não configurado.</summary>
    public decimal? MargemDesejadaPercentual { get; set; }

    // Dados fiscais (NFC-e modelo 65) — ver API/NETCORE/DataBase/Migrations/02_estrutura_fiscal.sql
    public string Ncm { get; set; } = "00000000";
    public string? Cest { get; set; }
    public string Cfop { get; set; } = "5102";
    public byte OrigemMercadoria { get; set; }
    public string UnidadeComercial { get; set; } = "UN";
    public string UnidadeTributavel { get; set; } = "UN";
    public string Gtin { get; set; } = "SEM GTIN";
    public string? CsosnIcms { get; set; }
    public string? CstIcms { get; set; }
    public decimal AliquotaIcms { get; set; }
    public string CstPis { get; set; } = "07";
    public string CstCofins { get; set; } = "07";
    public string? CstIbsCbs { get; set; }
    public string? CClassTrib { get; set; }
}
