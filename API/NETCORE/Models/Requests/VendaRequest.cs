/**
 * Arquivo: API/NETCORE/Models/Requests/VendaRequest.cs
 * Objetivo: define contrato de entrada para operações de registro de vendas e itens do carrinho.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 *
 * Quantity é `decimal` (não `int`) para suportar produtos vendidos por peso/volume (ex.: 0,452
 * kg) — o frontend já envia esse campo como número JSON puro (nunca foi mascarado como texto
 * pt-BR), então o model binder do ASP.NET Core aceita o mesmo payload sem mudança de formato.
 */
namespace HORUSPDV_API.Models.Requests;

public class VendaRequest
{
    public string? ClientSaleId { get; set; }
    public string? EventId { get; set; }
    public string? EventType { get; set; }
    public string? OfflineReference { get; set; }
    public DateTimeOffset? OccurredAt { get; set; }
    public string? PayloadHash { get; set; }

    /// <summary>
    /// true somente quando a venda foi feita sem conexão e está sendo reenviada pela sincronização do caixa.
    /// (eventId/offlineReference/occurredAt vão em TODA venda, então não servem para distinguir.)
    /// Nesse caso o servidor não recusa preço diferente do cadastrado sem autorização, pois o caixa offline
    /// não consegue validar a senha do gerente.
    /// </summary>
    public bool ReenvioOffline { get; set; }

    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
    public List<VendaItemRequest> Items { get; set; } = [];
    public List<VendaPagamentoRequest> Payments { get; set; } = [];
}

public class VendaPagamentoRequest
{
    public string PaymentType { get; set; } = string.Empty;
    public decimal Amount { get; set; }
    public decimal CashGiven { get; set; }
    public decimal ChangeAmount { get; set; }
}

public class VendaItemRequest
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public decimal UnitPrice { get; set; }
    public decimal Desconto { get; set; }
    public string? PromocaoId { get; set; }

    /// <summary>
    /// Autorização de gerente (POST api/HistoricoVendas/autorizar-preco) para este item ter preço
    /// diferente do cadastrado. Obrigatória, em venda online, quando o preço é menor que o cadastrado.
    /// </summary>
    public string? AutorizacaoPrecoId { get; set; }
}

public class AutorizarPrecoRequest
{
    public string SupervisorId { get; set; } = string.Empty;
    public string SupervisorPassword { get; set; } = string.Empty;
    public string ProductCode { get; set; } = string.Empty;
    /// <summary>Preço unitário autorizado (maior que zero).</summary>
    public decimal PrecoNovo { get; set; }
    public string? Motivo { get; set; }
}
