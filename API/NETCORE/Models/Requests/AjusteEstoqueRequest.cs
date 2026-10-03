namespace HORUSPDV_API.Models.Requests;

public class AjusteEstoqueRequest
{
    public string Tipo { get; set; } = "entrada"; // "entrada" ou "saida"
    public decimal Quantidade { get; set; }
    public string Motivo { get; set; } = string.Empty;

    /// <summary>Só vale para entrada: validade do lote recebido (AAAA-MM-DD). Sem ela, usa o prazo padrão da categoria, se houver.</summary>
    public string? DataValidade { get; set; }

    /// <summary>Só vale para entrada: número/código do lote impresso na embalagem, opcional.</summary>
    public string? NumeroLote { get; set; }
}
