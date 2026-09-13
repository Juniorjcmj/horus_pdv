/**
 * Arquivo: API/NETCORE/Services/Fiado/FiadoService.cs
 * Objetivo: implementação das regras de negócio de fiado, pagamentos e extrato de clientes.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Fiado;

public class FiadoService(FiadoAB fiadoAb, AuditLogAB auditLogAb) : IFiadoService
{
    public async Task<FiadoMovimentoAD> ReceberAsync(
        string companyId,
        string operadorId,
        string operadorNome,
        RecebimentoFiadoRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.ClienteId))
        {
            throw new InvalidOperationException("Cliente é obrigatório para registrar recebimento de fiado.");
        }

        if (request.Valor <= 0)
        {
            throw new InvalidOperationException("O valor recebido deve ser maior que zero.");
        }

        var formaPagamento = string.IsNullOrWhiteSpace(request.FormaPagamento) ? "dinheiro" : request.FormaPagamento.Trim();

        var mov = await fiadoAb.RegistrarCreditoAsync(
            companyId,
            request.ClienteId.Trim(),
            request.Valor,
            formaPagamento,
            request.Observacao,
            operadorNome);

        // Registro de auditoria
        _ = auditLogAb.RegistrarAsync(
            companyId,
            operadorId,
            operadorNome,
            AuditEventTypes.FiadoRecebimento,
            $"Recebeu {HorusMoneyFormat.Format(request.Valor)} do cliente {mov.ClienteNome} via {formaPagamento}. Saldo atual: {HorusMoneyFormat.Format(mov.SaldoAtual)}.",
            entityType: "Cliente",
            entityId: mov.ClienteId);

        return mov;
    }

    public Task<List<FiadoMovimentoAD>> ObterExtratoAsync(
        string companyId,
        string clienteId,
        DateTimeOffset? dataInicio = null,
        DateTimeOffset? dataFim = null)
    {
        return fiadoAb.ObterExtratoAsync(companyId, clienteId, dataInicio, dataFim);
    }

    public Task<List<FiadoDevedorAD>> ListarDevedoresAsync(
        string companyId,
        string? busca = null)
    {
        return fiadoAb.ListarDevedoresAsync(companyId, busca);
    }

    public Task<FiadoResumoAD> ObterResumoAsync(string companyId)
    {
        return fiadoAb.ObterResumoAsync(companyId);
    }
}
