namespace HORUSPDV_API.Models.Requests;

public class AjusteEstoqueRequest
{
    public string Tipo { get; set; } = "entrada"; // "entrada" ou "saida"
    public decimal Quantidade { get; set; }
    public string Motivo { get; set; } = string.Empty;
}
