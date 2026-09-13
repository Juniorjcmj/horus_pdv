/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/FiadoMovimentoAD.cs
 * Objetivo: representa as movimentações financeiras de conta corrente/fiado e dados de devedores.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class FiadoMovimentoAD
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = string.Empty;
    public string ClienteId { get; set; } = string.Empty;
    public string ClienteNome { get; set; } = string.Empty;
    public string ClienteDocument { get; set; } = string.Empty;
    public byte Tipo { get; set; } // 1 = Débito (compra fiado), 2 = Crédito (recebimento/pagamento)
    public decimal Valor { get; set; }
    public decimal SaldoAnterior { get; set; }
    public decimal SaldoAtual { get; set; }
    public string? VendaId { get; set; }
    public string? FormaPagamento { get; set; }
    public string? Observacao { get; set; }
    public string OperadorNome { get; set; } = string.Empty;
    public DateTimeOffset CriadoEm { get; set; }
}

public class FiadoDevedorAD
{
    public string ClienteId { get; set; } = string.Empty;
    public string ClienteNome { get; set; } = string.Empty;
    public string Document { get; set; } = string.Empty;
    public string Telephone { get; set; } = string.Empty;
    public string Cellphone { get; set; } = string.Empty;
    public decimal LimiteCredito { get; set; }
    public decimal SaldoDevedor { get; set; }
    public DateTimeOffset? UltimaCompra { get; set; }
    public int? DiasSemPagamento { get; set; }
}

public class FiadoResumoAD
{
    public decimal TotalAReceber { get; set; }
    public int QuantidadeDevedores { get; set; }
    public decimal Inadimplencia30Dias { get; set; }
    public decimal Inadimplencia60Dias { get; set; }
    public decimal MaiorDebito { get; set; }
}
