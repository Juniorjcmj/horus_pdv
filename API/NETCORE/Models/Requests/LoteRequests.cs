/**
 * Arquivo: API/NETCORE/Models/Requests/LoteRequests.cs
 * Objetivo: contratos de entrada do controle de validade por lote.
 * Entradas esperadas: recebe dados serializados do frontend (registro manual de lote e prazos por categoria).
 */
namespace HORUSPDV_API.Models.Requests;

public class RegistrarLoteRequest
{
    public string ProdutoId { get; set; } = string.Empty;
    /// <summary>AAAA-MM-DD (data impressa na embalagem).</summary>
    public string DataValidade { get; set; } = string.Empty;
    /// <summary>Quantidade do lote. Não altera o estoque: a entrada de estoque é feita pela nota/compra.</summary>
    public decimal Quantidade { get; set; }
    public string? NumeroLote { get; set; }
}

public class CategoriaValidadeRequest
{
    /// <summary>Dias de validade sugeridos na entrada (null = sem prazo padrão).</summary>
    public int? PrazoPadraoDias { get; set; }
    /// <summary>Quantos dias antes do vencimento o lote entra em alerta (null = usa o alerta do produto).</summary>
    public int? DiasAlerta { get; set; }
}
