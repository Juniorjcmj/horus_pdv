/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/CaixaSessionAD.cs
 * Objetivo: representa estrutura de dados de abertura, fechamento e status de caixa retornada pelo acesso ao banco.
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 *
 * OpeningAmount/ClosingAmount são `decimal` nativo (coluna DECIMAL desde a migração 01) — a
 * formatação pt-BR do contrato HTTP acontece em HorusCaixaService, via HorusMoneyFormat.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class CaixaSessionAD
{
    public string Id { get; set; } = string.Empty;
    public DateTimeOffset OpenedAt { get; set; }
    public DateTimeOffset? ClosedAt { get; set; }
    public decimal OpeningAmount { get; set; }
    public decimal ClosingAmount { get; set; }
    public string OperatorName { get; set; } = string.Empty;
    public string ClosedByName { get; set; } = string.Empty;
    public string Note { get; set; } = string.Empty;
}
