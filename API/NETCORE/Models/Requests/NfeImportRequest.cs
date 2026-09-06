/**
 * Arquivo: API/NETCORE/Models/Requests/NfeImportRequest.cs
 * Objetivo: define contrato de entrada da importação de produtos a partir de XML de NF-e de compra.
 * Entradas esperadas: recebe dados serializados do frontend nas ações de pré-visualizar/confirmar.
 *
 * Fluxo em duas etapas: /preview só lê o XML e devolve uma sugestão editável (nada é gravado);
 * /confirmar recebe de volta os mesmos itens, já revisados/editados pelo operador, e grava.
 */
namespace HORUSPDV_API.Models.Requests;

public class NfeImportPreviewRequest
{
    /// <summary>Conteúdo do arquivo .xml da NF-e, em base64.</summary>
    public string XmlBase64 { get; set; } = string.Empty;
}

public class NfeImportFornecedorInput
{
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

public class NfeImportItemInput
{
    public int NumeroItem { get; set; }

    /// <summary>Preenchido quando o item já bate com um produto existente (mesmo GTIN) — nesse caso o item vira entrada de estoque, não um cadastro novo.</summary>
    public string? ProdutoExistenteId { get; set; }

    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public string Gtin { get; set; } = "SEM GTIN";
    public string Ncm { get; set; } = "00000000";
    public string? Cest { get; set; }
    public string UnidadeComercial { get; set; } = "UN";

    // Strings pt-BR, como o resto do contrato HTTP (ver HorusMoneyFormat).
    public string Quantidade { get; set; } = "0";
    public string PrecoCusto { get; set; } = "0";
    public string PrecoVenda { get; set; } = "0";
}

public class NfeImportConfirmRequest
{
    public NfeImportFornecedorInput Fornecedor { get; set; } = new();
    public List<NfeImportItemInput> Itens { get; set; } = [];
}
