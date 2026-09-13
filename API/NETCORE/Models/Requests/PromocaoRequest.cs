/**
 * Arquivo: API/NETCORE/Models/Requests/PromocaoRequest.cs
 * Objetivo: define contratos de entrada para criação, edição e alteração de status de promoções.
 */
namespace HORUSPDV_API.Models.Requests;

public class PromocaoRequest
{
    public string? Id { get; set; }
    public string Nome { get; set; } = string.Empty;
    public string Tipo { get; set; } = string.Empty; // desconto_percentual, desconto_valor, preco_fixo, leve_x_pague_y, combo_quantidade, preco_atacado
    public decimal? ValorDesconto { get; set; }
    public decimal? PrecoFixo { get; set; }
    public int? QuantidadeLeva { get; set; }
    public int? QuantidadePaga { get; set; }
    public int? QuantidadeMinima { get; set; }
    public DateTimeOffset InicioVigencia { get; set; }
    public DateTimeOffset FimVigencia { get; set; }
    public bool Ativa { get; set; } = true;
    public string? CategoriaId { get; set; }
    public List<string> ProdutoIds { get; set; } = [];
}

public class PromocaoStatusRequest
{
    public bool Ativa { get; set; }
}
