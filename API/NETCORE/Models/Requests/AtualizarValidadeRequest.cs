namespace HORUSPDV_API.Models.Requests;

public class AtualizarValidadeRequest
{
    /// <summary>Data no formato ISO YYYY-MM-DD ou nula/vazia.</summary>
    public string? DataValidade { get; set; }
}
