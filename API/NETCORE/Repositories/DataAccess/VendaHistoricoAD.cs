/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/VendaHistoricoAD.cs
 * Objetivo: representa estrutura de dados de registro de vendas e itens do carrinho retornada
 *           pelo acesso ao banco — é também o contrato JSON devolvido ao frontend (não há
 *           camada Model/Service separada para histórico de vendas).
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 *
 * TotalAmount/UnitPrice/ItemTotal continuam string pt-BR no contrato (formatados por
 * HorusMoneyFormat a partir do `decimal` nativo das colunas). Quantity vira `decimal` nativo
 * (JSON number) — nunca foi mascarado no frontend, então o contrato não muda para quem só lê.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class VendaHistoricoAD
{
    public string SaleNumber { get; set; } = string.Empty;
    public string Status { get; set; } = "finalizada";
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal Quantity { get; set; }
    public string UnitPrice { get; set; } = string.Empty;
    public decimal Desconto { get; set; }
    public string? PromocaoId { get; set; }
    public string ItemTotal { get; set; } = string.Empty;
    public string SaleDate { get; set; } = string.Empty;
    public string? ClientSaleId { get; set; }
    public string? OfflineReference { get; set; }
    public string? FiscalDocId { get; set; }
    public int? FiscalModelo { get; set; }
    public int? FiscalNumeroNf { get; set; }
    public int? FiscalSerie { get; set; }
    public int? FiscalStatus { get; set; }
    public string? FiscalChaveAcesso { get; set; }
    public string? CanceladoEm { get; set; }
    public string? CanceladoPorOperadorNome { get; set; }
    public string? CanceladoPorSupervisorNome { get; set; }
    public string? CanceladoJustificativa { get; set; }
}

public class VendaDetalheCompletoAD
{
    public string VendaId { get; set; } = string.Empty;
    public string SaleNumber { get; set; } = string.Empty;
    public string Status { get; set; } = "finalizada";
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
    public string SaleDate { get; set; } = string.Empty;
    public string? ClientSaleId { get; set; }
    public string? OfflineReference { get; set; }
    public string? CanceladoEm { get; set; }
    public string? CanceladoPorOperadorNome { get; set; }
    public string? CanceladoPorSupervisorNome { get; set; }
    public string? CanceladoJustificativa { get; set; }
    public List<VendaHistoricoAD> Items { get; set; } = [];
    public List<VendaPagamentoAD> Payments { get; set; } = [];
}

public class VendaCanceladaResumoAD
{
    public string VendaId { get; set; } = string.Empty;
    public string SaleNumber { get; set; } = string.Empty;
    public string CustomerName { get; set; } = string.Empty;
    public string CustomerCpf { get; set; } = string.Empty;
    public string PaymentType { get; set; } = string.Empty;
    public string TotalAmount { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
    public string SaleDate { get; set; } = string.Empty;
    public string CanceladoEm { get; set; } = string.Empty;
    public string CanceladoPorOperadorNome { get; set; } = string.Empty;
    public string CanceladoPorSupervisorNome { get; set; } = string.Empty;
    public string CanceladoJustificativa { get; set; } = string.Empty;
    public int TotalItens { get; set; }
    public decimal TotalQuantidadeItens { get; set; }
    public string ItensResumo { get; set; } = string.Empty;
    public List<VendaHistoricoAD> Items { get; set; } = [];
}

public class VendaRegistroResultadoAD
{
    public string SaleNumber { get; set; } = string.Empty;
    public string VendaId { get; set; } = string.Empty;
    public string? ClientSaleId { get; set; }
    public bool IsReplay { get; set; }
    public List<VendaHistoricoAD> Rows { get; set; } = [];
    public List<VendaPagamentoAD> Payments { get; set; } = [];
    public List<EstoqueRupturaAvisoAD> Warnings { get; set; } = [];
}

public class EstoqueRupturaAvisoAD
{
    public string ProductCode { get; set; } = string.Empty;
    public string ProductName { get; set; } = string.Empty;
    public decimal EstoqueAnterior { get; set; }
    public decimal QuantidadeVendida { get; set; }
    public decimal SaldoResultante { get; set; }
}
