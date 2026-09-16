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
    public decimal EstoqueMinimo { get; set; }
    public decimal ProductUnitPrice { get; set; }
    public decimal ProductSalePrice { get; set; }
    public decimal TotalPriceOnProduct { get; set; }

    /// <summary>% de lucro desejado sobre o custo (ex.: 30 = 30%). Null = não configurado.</summary>
    public decimal? MargemDesejadaPercentual { get; set; }

    /// <summary>Vínculo com a tabela Categorias (departamento ou subcategoria). Null = sem categoria.</summary>
    public string? CategoriaId { get; set; }
    public string? CategoriaNome { get; set; }

    /// <summary>Data de validade do lote mais próximo (null se não informado ou não controlado).</summary>
    public DateTime? DataValidade { get; set; }

    /// <summary>Indica se o produto tem controle de validade ativo.</summary>
    public bool ControlaValidade { get; set; }

    /// <summary>Dias de antecedência para disparar alertas de vencimento (padrão 15).</summary>
    public int DiasAlertaValidade { get; set; } = 15;

    // Unidades de medida (compra/conversão)
    public string UnidadeCompra { get; set; } = "UN";
    public decimal FatorConversao { get; set; } = 1;
    public decimal QtdEmbalagem { get; set; } = 1;

    // Marca e fabricante
    public string? Marca { get; set; }
    public string? Fabricante { get; set; }
    public string? ReferenciaFabricante { get; set; }

    // Peso e dimensões
    public decimal PesoLiquidoKg { get; set; }
    public decimal PesoBrutoKg { get; set; }
    public decimal LarguraCm { get; set; }
    public decimal AlturaCm { get; set; }
    public decimal ComprimentoCm { get; set; }

    // Estoque expandido
    public decimal EstoqueMaximo { get; set; }
    public string? LocalizacaoEstoque { get; set; }

    // Dados de custo detalhados
    public decimal CustoMedio { get; set; }
    public decimal CustoComImposto { get; set; }
    public decimal CustoSemImposto { get; set; }

    // Campos comerciais
    public decimal DescontoMaximoPercentual { get; set; }
    public decimal ComissaoPercentual { get; set; }
    public decimal MarkupCadastrado { get; set; }
    public decimal MarkupPraticado { get; set; }

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
