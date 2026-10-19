namespace HORUSPDV_API.Models.Requests;

public class CorrecaoVendaRequest
{
    /// <summary>Códigos dos produtos a remover da venda.</summary>
    public List<string> ProdutosParaRemover { get; set; } = [];
    /// <summary>Justificativa obrigatória (mín. 5 caracteres).</summary>
    public string Justificativa { get; set; } = "";
}
