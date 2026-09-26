/**
 * Arquivo: API/NETCORE/Services/Caixa/HorusCaixaService.cs
 * Objetivo: centraliza regras de negócio de abertura, fechamento, sangria/reforço e status de
 *           caixa antes do acesso ao banco ou resposta HTTP.
 * Entradas esperadas: recebe requisições já validadas pelos controladores e aplica consistência operacional do domínio.
 *
 * Fechamento com conferência: o dinheiro esperado é sempre calculado a partir de dados que já
 * existem (abertura + vendas em dinheiro do turno + reforços - sangrias) — nunca confia em nada
 * que o operador digite sobre o histórico, só no valor final contado na gaveta. Qualquer
 * diferença entre o esperado e o contado exige justificativa (regra de negócio: nenhuma
 * diferença passa em branco, por menor que seja).
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;

namespace HORUSPDV_API.Services.Caixa;

public class HorusCaixaService(CaixaAB caixaAB, AuditLogAB auditLogAB)
{
    private static readonly TimeSpan MaxOpenPeriod = TimeSpan.FromHours(24);
    private const string FormaPagamentoDinheiro = "dinheiro";

    public CaixaStatusDto GetStatus(AuthenticatedUser currentUser, DateTimeOffset? reference = null)
        => GetStatusAsync(currentUser, reference).GetAwaiter().GetResult();

    public async Task<CaixaStatusDto> GetStatusAsync(AuthenticatedUser currentUser, DateTimeOffset? reference = null, CancellationToken cancellationToken = default)
    {
        var status = await BuildStatusAsync(currentUser.CompanyId, reference ?? HorusDateTime.Now, cancellationToken);
        if (HorusRoles.IsGerenteOuAdmin(currentUser.Role))
        {
            return status;
        }

        // Atendente só vê o próprio turno no histórico — gerente/administrador vê de todo mundo.
        bool PertenceAoUsuario(CaixaSessionDto session) =>
            session.OperatorId == currentUser.Id || session.ClosedById == currentUser.Id;

        status.History = status.History.Where(PertenceAoUsuario).ToList();
        if (status.LastSession is not null && !PertenceAoUsuario(status.LastSession))
        {
            status.LastSession = null;
        }

        return status;
    }

    public async Task<CaixaStatusDto> AbrirAsync(AbrirCaixaRequest request, AuthenticatedUser currentUser, string? ip = null, CancellationToken cancellationToken = default)
    {
        var now = HorusDateTime.Now;
        var openingAmount = HorusMoneyFormat.ParseDecimal(request.OpeningAmount);
        var sessionId = $"cx-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";

        var status = await caixaAB.AbrirIdempotenteAsync(
            sessionId,
            currentUser.CompanyId,
            now,
            openingAmount,
            currentUser.Id,
            currentUser.Name,
            request.EventId,
            request.PayloadHash,
            t => BuildStatusAsync(currentUser.CompanyId, t, cancellationToken),
            cancellationToken);

        if (!status.IsReplay)
        {
            await auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                AuditEventTypes.CaixaAbertura,
                $"Abriu o caixa com {HorusMoneyFormat.Format(openingAmount)} de fundo de troco.",
                entityType: "CaixaSessao",
                entityId: sessionId,
                ip: ip);
        }

        return status;
    }

    public CaixaStatusDto Abrir(AbrirCaixaRequest request, AuthenticatedUser currentUser, string? ip = null)
        => AbrirAsync(request, currentUser, ip).GetAwaiter().GetResult();

    public async Task<CaixaStatusDto> RegistrarMovimentoAsync(RegistrarMovimentoCaixaRequest request, AuthenticatedUser currentUser, string? ip = null, CancellationToken cancellationToken = default)
    {
        return await caixaAB.RegistrarMovimentoIdempotenteAsync(
            currentUser.CompanyId,
            request,
            currentUser,
            now => BuildStatus(currentUser.CompanyId, now),
            (companyId, session, now) => ComputeExpectedCash(companyId, session, now),
            EnsureResponsavelPeloCaixa,
            ip,
            cancellationToken);
    }

    public CaixaStatusDto RegistrarMovimento(RegistrarMovimentoCaixaRequest request, AuthenticatedUser currentUser, string? ip = null)
        => RegistrarMovimentoAsync(request, currentUser, ip).GetAwaiter().GetResult();

    public async Task<CaixaStatusDto> FecharAsync(FecharCaixaRequest request, AuthenticatedUser currentUser, string? ip = null, CancellationToken cancellationToken = default)
    {
        var now = HorusDateTime.Now;
        var openSession = await caixaAB.ObterSessaoAbertaAsync(currentUser.CompanyId, cancellationToken);
        if (openSession is null)
        {
            if (!string.IsNullOrWhiteSpace(request.EventId))
            {
                var existing = await caixaAB.ObterEventoProcessadoAsync(currentUser.CompanyId, request.EventId, request.PayloadHash, cancellationToken);
                if (existing != null)
                {
                    return existing;
                }
            }
            throw new InvalidOperationException("Não existe caixa aberto para fechamento.");
        }

        EnsureResponsavelPeloCaixa(openSession, currentUser);

        var closingAmount = HorusMoneyFormat.ParseDecimal(request.ClosingAmount);
        var expectedCashAmount = await ComputeExpectedCashAsync(currentUser.CompanyId, openSession, now, cancellationToken);
        var differenceAmount = Math.Round(closingAmount - expectedCashAmount, 2);
        var differenceReason = request.DifferenceReason?.Trim();

        if (differenceAmount != 0 && string.IsNullOrWhiteSpace(differenceReason))
        {
            var sinal = differenceAmount > 0 ? "sobra" : "falta";
            throw new InvalidOperationException(
                $"Há uma diferença de {HorusMoneyFormat.Format(Math.Abs(differenceAmount))} ({sinal}) em relação ao esperado " +
                $"({HorusMoneyFormat.Format(expectedCashAmount)}). Informe uma justificativa antes de fechar o caixa.");
        }

        var status = await caixaAB.FecharIdempotenteAsync(
            openSession.Id,
            currentUser.CompanyId,
            now,
            closingAmount,
            currentUser.Id,
            currentUser.Name,
            request.Note?.Trim() ?? string.Empty,
            expectedCashAmount,
            differenceAmount,
            differenceAmount == 0 ? null : differenceReason,
            request.EventId,
            request.PayloadHash,
            t => BuildStatusAsync(currentUser.CompanyId, t, cancellationToken),
            cancellationToken);

        if (!status.IsReplay)
        {
            var descricao = $"Fechou o caixa: esperado {HorusMoneyFormat.Format(expectedCashAmount)}, " +
                             $"contado {HorusMoneyFormat.Format(closingAmount)}, diferença {HorusMoneyFormat.Format(differenceAmount)}." +
                             (differenceAmount != 0 ? $" Motivo: {differenceReason}" : string.Empty);
            await auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                AuditEventTypes.CaixaFechamento,
                descricao,
                entityType: "CaixaSessao",
                entityId: openSession.Id,
                ip: ip);
        }

        return status;
    }

    public CaixaStatusDto Fechar(FecharCaixaRequest request, AuthenticatedUser currentUser, string? ip = null)
        => FecharAsync(request, currentUser, ip).GetAwaiter().GetResult();

    public void EnsureVendaPermitida(AuthenticatedUser currentUser, string? ip = null)
    {
        var status = BuildStatus(currentUser.CompanyId, HorusDateTime.Now);
        if (status.CanSell) return;

        auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                AuditEventTypes.VendaBloqueada,
                $"Tentou vender com o caixa {status.State} — {status.BlockReason}",
                ip: ip)
            .GetAwaiter()
            .GetResult();

        throw new InvalidOperationException(status.BlockReason);
    }

    /// <summary>
    /// Só quem abriu o caixa (ou um gerente/administrador, como cobertura) pode fechá-lo ou lançar
    /// sangria/reforço nele — evita que outra atendente mexa por engano no turno de outra pessoa.
    /// </summary>
    private static void EnsureResponsavelPeloCaixa(CaixaSessionAD session, AuthenticatedUser currentUser)
    {
        if (session.OperatorId == currentUser.Id || HorusRoles.IsGerenteOuAdmin(currentUser.Role))
        {
            return;
        }

        throw new InvalidOperationException(
            $"Esse caixa foi aberto por {session.OperatorName} — só ela ou um gerente/administrador podem mexer nele.");
    }

    /// <summary>Dinheiro esperado na gaveta agora: abertura + vendas em dinheiro do turno + reforços - sangrias.</summary>
    public async Task<decimal> ComputeExpectedCashAsync(string companyId, CaixaSessionAD session, DateTimeOffset now, CancellationToken cancellationToken = default)
    {
        var vendasPorFormaPagamento = await caixaAB.ObterTotaisPorFormaPagamentoAsync(companyId, session.OpenedAt, now, cancellationToken);
        var vendasDinheiro = vendasPorFormaPagamento.GetValueOrDefault(FormaPagamentoDinheiro, 0m);

        var movimentos = await caixaAB.ListarMovimentosAsync(companyId, session.Id, cancellationToken);
        var totalReforcos = movimentos.Where(item => item.Tipo == TipoMovimentoCaixa.Reforco).Sum(item => item.Valor);
        var totalSangrias = movimentos.Where(item => item.Tipo == TipoMovimentoCaixa.Sangria).Sum(item => item.Valor);

        return session.OpeningAmount + vendasDinheiro + totalReforcos - totalSangrias;
    }

    private decimal ComputeExpectedCash(string companyId, CaixaSessionAD session, DateTimeOffset now)
        => ComputeExpectedCashAsync(companyId, session, now).GetAwaiter().GetResult();

    private CaixaStatusDto BuildStatus(string companyId, DateTimeOffset now)
        => BuildStatusAsync(companyId, now).GetAwaiter().GetResult();

    private async Task<CaixaStatusDto> BuildStatusAsync(string companyId, DateTimeOffset now, CancellationToken cancellationToken = default)
    {
        var sessions = await caixaAB.ListarSessoesAsync(companyId, cancellationToken);
        var openSession = sessions.FirstOrDefault(item => item.ClosedAt is null);
        var lastSession = openSession ?? sessions.FirstOrDefault();
        var canSell = false;
        var blockReason = "Abra o caixa do dia antes de iniciar vendas.";
        var state = "fechado";

        if (openSession is not null)
        {
            var openedToday = openSession.OpenedAt.Date == now.Date;
            var withinPeriod = now - openSession.OpenedAt <= MaxOpenPeriod;

            // Regra de negócio do PDV: venda só é permitida com caixa aberto no dia atual e por até 24 horas.
            if (openedToday && withinPeriod)
            {
                canSell = true;
                blockReason = "";
                state = "aberto";
            }
            else
            {
                state = "expirado";
                blockReason = !openedToday
                    ? "O caixa aberto pertence a outro dia. Feche o caixa atual e abra o caixa do dia."
                    : "O caixa aberto ultrapassou 24 horas. Feche o caixa atual e abra um novo.";
            }
        }

        // Calcula o DTO da sessão aberta uma única vez (consulta ao vivo de vendas/movimentos) e
        // reaproveita nas três seções da resposta, em vez de repetir a mesma consulta 3x.
        var openSessionDto = openSession is null ? null : await ToDtoAsync(companyId, openSession, now, isCurrent: true, cancellationToken);

        CaixaSessionDto BuildDto(CaixaSessionAD session) =>
            session == openSession && openSessionDto is not null ? openSessionDto : BuildHistoricalDto(session);

        return new CaixaStatusDto
        {
            State = state,
            CanSell = canSell,
            BlockReason = blockReason,
            ServerNow = HorusDateTime.FormatIso(now),
            CurrentSession = openSessionDto,
            LastSession = lastSession is null ? null : BuildDto(lastSession),
            History = sessions.Take(12).Select(BuildDto).ToList()
        };
    }

    private static CaixaSessionDto BuildHistoricalDto(CaixaSessionAD source)
    {
        var closedAt = source.ClosedAt;
        var elapsed = (closedAt ?? HorusDateTime.Now) - source.OpenedAt;

        return new CaixaSessionDto
        {
            Id = source.Id,
            Status = closedAt is null ? "Aberto" : "Fechado",
            OpenedAt = HorusDateTime.FormatIso(source.OpenedAt),
            ClosedAt = source.ClosedAt.HasValue ? HorusDateTime.FormatIso(source.ClosedAt.Value) : null,
            OpeningAmount = HorusMoneyFormat.Format(source.OpeningAmount),
            ClosingAmount = HorusMoneyFormat.Format(source.ClosingAmount),
            OperatorId = source.OperatorId,
            OperatorName = source.OperatorName,
            ClosedById = source.ClosedById,
            ClosedByName = source.ClosedByName,
            Note = source.Note,
            ElapsedMinutes = Math.Max(0, (int)Math.Floor(elapsed.TotalMinutes)),
            ExpectedCashAmount = source.ExpectedCashAmount is { } exp ? HorusMoneyFormat.Format(exp) : null,
            DifferenceAmount = source.DifferenceAmount is { } diff ? HorusMoneyFormat.Format(diff) : null,
            DifferenceReason = source.DifferenceReason,
            Movimentos = [],
            PaymentBreakdown = null,
        };
    }

    private async Task<CaixaSessionDto> ToDtoAsync(string companyId, CaixaSessionAD source, DateTimeOffset now, bool isCurrent, CancellationToken cancellationToken = default)
    {
        var closedAt = source.ClosedAt;
        var elapsed = (closedAt ?? now) - source.OpenedAt;

        var movimentos = await caixaAB.ListarMovimentosAsync(companyId, source.Id, cancellationToken);
        var movimentosDto = movimentos.Select(item => new CaixaMovimentoDto
        {
            Id = item.Id,
            Tipo = item.Tipo.ToString(),
            Valor = HorusMoneyFormat.Format(item.Valor),
            Motivo = item.Motivo,
            CreatedAt = HorusDateTime.FormatIso(item.CreatedAt),
            OperatorName = item.OperatorName,
        }).ToList();

        List<PaymentBreakdownDto>? paymentBreakdown = null;
        string? expectedCashPreview = null;
        if (isCurrent && closedAt is null)
        {
            var totals = await caixaAB.ObterTotaisPorFormaPagamentoAsync(companyId, source.OpenedAt, now, cancellationToken);
            paymentBreakdown = totals
                .Select(item => new PaymentBreakdownDto { PaymentType = item.Key, Total = HorusMoneyFormat.Format(item.Value) })
                .ToList();
            var expected = await ComputeExpectedCashAsync(companyId, source, now, cancellationToken);
            expectedCashPreview = HorusMoneyFormat.Format(expected);
        }

        return new CaixaSessionDto
        {
            Id = source.Id,
            Status = closedAt is null ? "Aberto" : "Fechado",
            OpenedAt = HorusDateTime.FormatIso(source.OpenedAt),
            ClosedAt = source.ClosedAt.HasValue ? HorusDateTime.FormatIso(source.ClosedAt.Value) : null,
            OpeningAmount = HorusMoneyFormat.Format(source.OpeningAmount),
            ClosingAmount = HorusMoneyFormat.Format(source.ClosingAmount),
            OperatorId = source.OperatorId,
            OperatorName = source.OperatorName,
            ClosedById = source.ClosedById,
            ClosedByName = source.ClosedByName,
            Note = source.Note,
            ElapsedMinutes = Math.Max(0, (int)Math.Floor(elapsed.TotalMinutes)),
            ExpectedCashAmount = expectedCashPreview ?? (source.ExpectedCashAmount is { } exp ? HorusMoneyFormat.Format(exp) : null),
            DifferenceAmount = source.DifferenceAmount is { } diff ? HorusMoneyFormat.Format(diff) : null,
            DifferenceReason = source.DifferenceReason,
            Movimentos = movimentosDto,
            PaymentBreakdown = paymentBreakdown,
        };
    }
}

public class CaixaStatusDto
{
    public string State { get; set; } = "fechado";
    public bool CanSell { get; set; }
    public string BlockReason { get; set; } = "";
    public string ServerNow { get; set; } = "";
    public CaixaSessionDto? CurrentSession { get; set; }
    public CaixaSessionDto? LastSession { get; set; }
    public List<CaixaSessionDto> History { get; set; } = [];
    public bool IsReplay { get; set; }
}

public class CaixaSessionDto
{
    public string Id { get; set; } = "";
    public string Status { get; set; } = "";
    public string OpenedAt { get; set; } = "";
    public string? ClosedAt { get; set; }
    public string OpeningAmount { get; set; } = "0,00";
    public string ClosingAmount { get; set; } = "0,00";
    public string OperatorId { get; set; } = "";
    public string OperatorName { get; set; } = "";
    public string ClosedById { get; set; } = "";
    public string ClosedByName { get; set; } = "";
    public string Note { get; set; } = "";
    public int ElapsedMinutes { get; set; }

    /// <summary>Sessão fechada: valor calculado no momento do fechamento. Sessão aberta: projeção em tempo real.</summary>
    public string? ExpectedCashAmount { get; set; }
    public string? DifferenceAmount { get; set; }
    public string? DifferenceReason { get; set; }
    public List<CaixaMovimentoDto> Movimentos { get; set; } = [];

    /// <summary>Só preenchido para a sessão aberta — total vendido por forma de pagamento até agora.</summary>
    public List<PaymentBreakdownDto>? PaymentBreakdown { get; set; }
}

public class CaixaMovimentoDto
{
    public string Id { get; set; } = "";
    public string Tipo { get; set; } = "";
    public string Valor { get; set; } = "0,00";
    public string Motivo { get; set; } = "";
    public string CreatedAt { get; set; } = "";
    public string OperatorName { get; set; } = "";
}

public class PaymentBreakdownDto
{
    public string PaymentType { get; set; } = "";
    public string Total { get; set; } = "0,00";
}
