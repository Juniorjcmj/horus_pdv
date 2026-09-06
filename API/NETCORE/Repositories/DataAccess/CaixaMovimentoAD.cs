/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/CaixaMovimentoAD.cs
 * Objetivo: representa uma sangria (retirada) ou reforço (suprimento) de dinheiro do caixa
 *           durante o turno, retornada pelo acesso ao banco.
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public enum TipoMovimentoCaixa : byte
{
    Reforco = 1,
    Sangria = 2,
}

public class CaixaMovimentoAD
{
    public string Id { get; set; } = string.Empty;
    public string CaixaSessaoId { get; set; } = string.Empty;
    public TipoMovimentoCaixa Tipo { get; set; }
    public decimal Valor { get; set; }
    public string Motivo { get; set; } = string.Empty;
    public DateTimeOffset CreatedAt { get; set; }
    public string OperatorId { get; set; } = string.Empty;
    public string OperatorName { get; set; } = string.Empty;
}
