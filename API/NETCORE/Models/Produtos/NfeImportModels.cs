/**
 * Arquivo: API/NETCORE/Models/Produtos/NfeImportModels.cs
 * Objetivo: contratos de saída da importação de produtos a partir de XML de NF-e de compra.
 * Entradas esperadas: alimentados pelo NfeImportService a partir do XML e do estado atual do cadastro.
 */
namespace HORUSPDV_API.Models.Produtos;

public class NfeImportFornecedorPreview
{
    public bool JaExiste { get; set; }
    public string Cnpj { get; set; } = string.Empty;
    public string CompanyName { get; set; } = string.Empty;
    public string FantasyName { get; set; } = string.Empty;
    public string Cep { get; set; } = string.Empty;
    public string City { get; set; } = string.Empty;
    public string State { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
    public string Neighborhood { get; set; } = string.Empty;
    public string Number { get; set; } = string.Empty;
    public string Telephone { get; set; } = string.Empty;
}

public class NfeImportItemPreview
{
    public int NumeroItem { get; set; }

    /// <summary>Quando preenchido, o item já existe no cadastro (mesmo GTIN) e a importação vai somar ao estoque em vez de criar produto novo.</summary>
    public string? ProdutoExistenteId { get; set; }
    public string? ProdutoExistenteNome { get; set; }

    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string Gtin { get; set; } = string.Empty;
    public string Ncm { get; set; } = string.Empty;
    public string? Cest { get; set; }
    public string UnidadeComercial { get; set; } = string.Empty;

    // Strings pt-BR (HorusMoneyFormat), já prontas para exibir/editar no formulário.
    public string Quantidade { get; set; } = string.Empty;
    public string PrecoCusto { get; set; } = string.Empty;
    public string PrecoVendaSugerido { get; set; } = string.Empty;
}

public class NfeImportPreviewModel
{
    public string NumeroNota { get; set; } = string.Empty;
    public string Serie { get; set; } = string.Empty;
    public NfeImportFornecedorPreview Fornecedor { get; set; } = new();
    public List<NfeImportItemPreview> Itens { get; set; } = [];
}

public class NfeImportResultModel
{
    public bool FornecedorCriado { get; set; }
    public int ProdutosCriados { get; set; }
    public int ProdutosAtualizados { get; set; }
}
