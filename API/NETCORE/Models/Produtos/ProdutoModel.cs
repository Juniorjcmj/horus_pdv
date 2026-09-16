/**
 * Arquivo: API/NETCORE/Models/Produtos/ProdutoModel.cs
 * Objetivo: representa dados de cadastro, estoque, manutenção e dados fiscais de produtos
 *           trafegados entre banco, serviços e API.
 * Entradas esperadas: recebe valores persistidos ou calculados para serialização nas respostas.
 */
namespace HORUSPDV_API.Models.Produtos;

public class ProdutoModel
{
    public string Id { get; set; } = string.Empty;
    public string ProductImageUrl { get; set; } = string.Empty;
    public string ProductImageName { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string ProductCode { get; set; } = string.Empty;
    public string ProductSupplier { get; set; } = string.Empty;
    public string ProductDescription { get; set; } = string.Empty;
    public string ProductQnt { get; set; } = string.Empty;
    public string EstoqueMinimo { get; set; } = "0";
    public string ProductUnitPrice { get; set; } = string.Empty;
    public string ProductSalePrice { get; set; } = string.Empty;
    public string TotalPriceOnProduct { get; set; } = string.Empty;
    public string? MargemDesejadaPercentual { get; set; }
    public string? CategoriaId { get; set; }
    public string? CategoriaNome { get; set; }
    public string? DataValidade { get; set; }
    public bool ControlaValidade { get; set; }
    public int DiasAlertaValidade { get; set; } = 15;
    public int? DiasRestantes { get; set; }

    // Unidades de medida (compra/conversão)
    public string UnidadeCompra { get; set; } = "UN";
    public string FatorConversao { get; set; } = "1";
    public string QtdEmbalagem { get; set; } = "1";

    // Marca e fabricante
    public string? Marca { get; set; }
    public string? Fabricante { get; set; }
    public string? ReferenciaFabricante { get; set; }

    // Peso e dimensões
    public string PesoLiquidoKg { get; set; } = "0";
    public string PesoBrutoKg { get; set; } = "0";
    public string LarguraCm { get; set; } = "0";
    public string AlturaCm { get; set; } = "0";
    public string ComprimentoCm { get; set; } = "0";

    // Estoque expandido
    public string EstoqueMaximo { get; set; } = "0";
    public string? LocalizacaoEstoque { get; set; }

    // Dados de custo detalhados
    public string CustoMedio { get; set; } = "0,00";
    public string CustoComImposto { get; set; } = "0,00";
    public string CustoSemImposto { get; set; } = "0,00";

    // Campos comerciais
    public string DescontoMaximoPercentual { get; set; } = "0";
    public string ComissaoPercentual { get; set; } = "0";
    public string MarkupCadastrado { get; set; } = "0";
    public string MarkupPraticado { get; set; } = "0";

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
