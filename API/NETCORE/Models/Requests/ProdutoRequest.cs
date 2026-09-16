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
    public string EstoqueMinimo { get; set; } = "0";
    public string ProductUnitPrice { get; set; } = string.Empty;
    public string ProductSalePrice { get; set; } = string.Empty;
    public string TotalPriceOnProduct { get; set; } = string.Empty;

    /// <summary>% de lucro desejado sobre o custo (ex.: "30,00" = 30%). Vazio/nulo = não configurado.</summary>
    public string? MargemDesejadaPercentual { get; set; }

    /// <summary>Vínculo com a tabela Categorias (departamento ou subcategoria). Null/vazio = sem categoria.</summary>
    public string? CategoriaId { get; set; }

    /// <summary>Data de validade do produto (formato ISO "YYYY-MM-DD" ou null/vazio).</summary>
    public string? DataValidade { get; set; }

    /// <summary>Indica se o produto tem controle de validade ativo.</summary>
    public bool ControlaValidade { get; set; }

    /// <summary>Dias de antecedência para disparar alertas de vencimento (padrão 15).</summary>
    public int DiasAlertaValidade { get; set; } = 15;

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
