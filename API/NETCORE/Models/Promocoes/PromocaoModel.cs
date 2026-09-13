/**
 * Arquivo: API/NETCORE/Models/Promocoes/PromocaoModel.cs
 * Objetivo: modelo de domínio/apresentação de promoções e preços dinâmicos.
 */
namespace HORUSPDV_API.Models.Promocoes;

public class PromocaoModel
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = string.Empty;
    public string Nome { get; set; } = string.Empty;
    public string Tipo { get; set; } = string.Empty;
    public decimal? ValorDesconto { get; set; }
    public decimal? PrecoFixo { get; set; }
    public int? QuantidadeLeva { get; set; }
    public int? QuantidadePaga { get; set; }
    public int? QuantidadeMinima { get; set; }
    public DateTimeOffset InicioVigencia { get; set; }
    public DateTimeOffset FimVigencia { get; set; }
    public bool Ativa { get; set; } = true;
    public string? CategoriaId { get; set; }
    public string? CategoriaNome { get; set; }
    public string CriadoPor { get; set; } = string.Empty;
    public DateTimeOffset CriadoEm { get; set; }
    public List<string> ProdutoIds { get; set; } = [];
}

public class PromocaoResultadoModel
{
    public string PromocaoId { get; set; } = string.Empty;
    public string Nome { get; set; } = string.Empty;
    public int QuantidadeVendas { get; set; }
    public decimal ReceitaBruta { get; set; }
    public decimal ReceitaLiquida { get; set; }
    public decimal DescontoTotal { get; set; }
    public decimal MargemLiquida { get; set; }
}
