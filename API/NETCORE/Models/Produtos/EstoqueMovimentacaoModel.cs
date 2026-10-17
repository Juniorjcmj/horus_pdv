namespace HORUSPDV_API.Models.Produtos;

public class EstoqueMovimentacaoModel
{
    public string Id { get; set; } = string.Empty;
    public string ProdutoId { get; set; } = string.Empty;
    public string ProdutoNome { get; set; } = string.Empty;
    public string ProdutoCodigo { get; set; } = string.Empty;
    public string Tipo { get; set; } = string.Empty;
    public decimal Quantidade { get; set; }
    public decimal EstoqueAnterior { get; set; }
    public decimal EstoqueNovo { get; set; }
    public string Motivo { get; set; } = string.Empty;
    public string UsuarioId { get; set; } = string.Empty;
    public string UsuarioNome { get; set; } = string.Empty;
    public string CriadoEm { get; set; } = string.Empty;
}
