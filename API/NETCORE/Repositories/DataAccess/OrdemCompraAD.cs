/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/OrdemCompraAD.cs
 * Objetivo: estruturas de dados de ordens de compra retornadas pelo acesso ao banco.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public enum OrdemCompraStatus
{
    Pendente = 0,
    Recebido = 1,
    Cancelado = 2,
    RecebidoParcial = 3
}

public class OrdemCompraAD
{
    public string Id { get; set; } = string.Empty;
    public string CompanyId { get; set; } = string.Empty;
    public string OrderNumber { get; set; } = string.Empty;
    public string? SupplierId { get; set; }
    public string SupplierName { get; set; } = string.Empty;
    public string SupplierCnpj { get; set; } = string.Empty;
    public OrdemCompraStatus Status { get; set; } = OrdemCompraStatus.Pendente;
    public string? CreatedBy { get; set; }
    public string CreatedByName { get; set; } = string.Empty;
    public DateTimeOffset CreatedAt { get; set; }
    public DateTimeOffset? ReceivedAt { get; set; }
    public DateTimeOffset? CanceledAt { get; set; }
    public string Note { get; set; } = string.Empty;
    public decimal TotalEstimado { get; set; }
    public List<OrdemCompraItemAD> Itens { get; set; } = [];
}

public class OrdemCompraItemAD
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public decimal UnitCost { get; set; }
    public decimal ItemTotal { get; set; }
    public decimal QuantityReceived { get; set; }
}

public class SugestaoReposicaoAD
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal CurrentStock { get; set; }
    public decimal MinStock { get; set; }
    public decimal MaxStock { get; set; }
    public string? SupplierId { get; set; }
    public string SupplierName { get; set; } = string.Empty;
    public decimal UnitCost { get; set; }
}
