namespace HORUSPDV_API.Models.Requests;

public sealed class EntradaManualRequest
{
    public string OperacaoId { get; set; } = "";
    public string DataCompra { get; set; } = "";
    public string? FornecedorId { get; set; }
    public string TipoDocumento { get; set; } = "cupom";
    public string NumeroDocumento { get; set; } = "";
    public string? ChaveAcesso { get; set; }
    public string Observacao { get; set; } = "";
    public EntradaManualComprovante? Comprovante { get; set; }
    public List<EntradaManualItem> Itens { get; set; } = [];
}

public sealed class EntradaManualComprovante
{
    public string Nome { get; set; } = "";
    public string Base64 { get; set; } = "";
}

public sealed class EntradaManualItem : NfeImportItemInput
{
    public string QuantidadeCompra { get; set; } = "";
    public string UnidadeCompra { get; set; } = "UN";
    public string FatorConversao { get; set; } = "1";
    public string CustoCompra { get; set; } = "";
    public bool AlterarPrecoVenda { get; set; }
    public string? EstoqueAntes { get; set; }
    public string? EstoqueDepois { get; set; }
}
