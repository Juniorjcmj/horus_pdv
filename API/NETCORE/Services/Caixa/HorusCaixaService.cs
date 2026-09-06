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

    public CaixaStatusDto GetStatus(string companyId, DateTimeOffset? reference = null)
        => BuildStatus(companyId, reference ?? DateTimeOffset.Now);

    public CaixaStatusDto Abrir(AbrirCaixaRequest request, AuthenticatedUser currentUser)
    {
        var now = DateTimeOffset.Now;
        var openSession = caixaAB.ObterSessaoAbertaAsync(currentUser.CompanyId).GetAwaiter().GetResult();
        if (openSession is not null)
        {
            var status = BuildStatus(currentUser.CompanyId, now);
            if (status.CanSell)
            {
                throw new InvalidOperationException("Já existe um caixa aberto para venda.");
            }

            throw new InvalidOperationException(
                "Existe um caixa aberto fora do período permitido. Feche o caixa atual antes de abrir um novo.");
        }

        var openingAmount = HorusMoneyFormat.ParseDecimal(request.OpeningAmount);
        var sessionId = $"cx-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}";
        caixaAB.AbrirAsync(sessionId, currentUser.CompanyId, now, openingAmount, currentUser.Id, currentUser.Name)
            .GetAwaiter()
            .GetResult();

        auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                AuditEventTypes.CaixaAbertura,
                $"Abriu o caixa com {HorusMoneyFormat.Format(openingAmount)} de fundo de troco.",
                entityType: "CaixaSessao",
                entityId: sessionId)
            .GetAwaiter()
            .GetResult();

        return BuildStatus(currentUser.CompanyId, now);
    }

    public CaixaStatusDto RegistrarMovimento(RegistrarMovimentoCaixaRequest request, AuthenticatedUser currentUser)
    {
        var now = DateTimeOffset.Now;
        var openSession = caixaAB.ObterSessaoAbertaAsync(currentUser.CompanyId).GetAwaiter().GetResult();
        if (openSession is null)
        {
            throw new InvalidOperationException("Não existe caixa aberto para lançar movimento.");
        }

        if (!Enum.TryParse<TipoMovimentoCaixa>(request.Tipo, ignoreCase: true, out var tipo))
        {
            throw new InvalidOperationException("Tipo de movimento inválido — use \"Reforco\" ou \"Sangria\".");
        }

        var valor = HorusMoneyFormat.ParseDecimal(request.Valor);
        if (valor <= 0)
        {
            throw new InvalidOperationException("Valor do movimento deve ser maior que zero.");
        }

        if (string.IsNullOrWhiteSpace(request.Motivo) || request.Motivo.Trim().Length < 3)
        {
            throw new InvalidOperationException("Informe o motivo do movimento (mínimo 3 caracteres).");
        }

        if (tipo == TipoMovimentoCaixa.Sangria)
        {
            var caixaAtual = ComputeExpectedCash(currentUser.CompanyId, openSession, now);
            if (valor > caixaAtual)
            {
                throw new InvalidOperationException(
                    $"Sangria maior que o dinheiro em caixa (disponível: {HorusMoneyFormat.Format(caixaAtual)}).");
            }
        }

        var movimento = new CaixaMovimentoAD
        {
            Id = $"cxm-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}",
            CaixaSessaoId = openSession.Id,
            Tipo = tipo,
            Valor = valor,
            Motivo = request.Motivo.Trim(),
            CreatedAt = now,
            OperatorId = currentUser.Id,
            OperatorName = currentUser.Name,
        };
        caixaAB.RegistrarMovimentoAsync(currentUser.CompanyId, movimento).GetAwaiter().GetResult();

        var eventType = tipo == TipoMovimentoCaixa.Reforco ? AuditEventTypes.CaixaReforco : AuditEventTypes.CaixaSangria;
        var acao = tipo == TipoMovimentoCaixa.Reforco ? "Reforço" : "Sangria";
        auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                eventType,
                $"{acao} de {HorusMoneyFormat.Format(valor)} — {movimento.Motivo}",
                entityType: "CaixaSessao",
                entityId: openSession.Id)
            .GetAwaiter()
            .GetResult();

        return BuildStatus(currentUser.CompanyId, now);
    }

    public CaixaStatusDto Fechar(FecharCaixaRequest request, AuthenticatedUser currentUser)
    {
        var now = DateTimeOffset.Now;
        var openSession = caixaAB.ObterSessaoAbertaAsync(currentUser.CompanyId).GetAwaiter().GetResult();
        if (openSession is null)
        {
            throw new InvalidOperationException("Não existe caixa aberto para fechamento.");
        }

        var closingAmount = HorusMoneyFormat.ParseDecimal(request.ClosingAmount);
        var expectedCashAmount = ComputeExpectedCash(currentUser.CompanyId, openSession, now);
        var differenceAmount = Math.Round(closingAmount - expectedCashAmount, 2);
        var differenceReason = request.DifferenceReason?.Trim();

        if (differenceAmount != 0 && string.IsNullOrWhiteSpace(differenceReason))
        {
            var sinal = differenceAmount > 0 ? "sobra" : "falta";
            throw new InvalidOperationException(
                $"Há uma diferença de {HorusMoneyFormat.Format(Math.Abs(differenceAmount))} ({sinal}) em relação ao esperado " +
                $"({HorusMoneyFormat.Format(expectedCashAmount)}). Informe uma justificativa antes de fechar o caixa.");
        }

        caixaAB.FecharAsync(
                openSession.Id,
                currentUser.CompanyId,
                now,
                closingAmount,
                currentUser.Id,
                currentUser.Name,
                request.Note.Trim(),
                expectedCashAmount,
                differenceAmount,
                differenceAmount == 0 ? null : differenceReason)
            .GetAwaiter()
            .GetResult();

        var descricao = $"Fechou o caixa: esperado {HorusMoneyFormat.Format(expectedCashAmount)}, " +
                         $"contado {HorusMoneyFormat.Format(closingAmount)}, diferença {HorusMoneyFormat.Format(differenceAmount)}." +
                         (differenceAmount != 0 ? $" Motivo: {differenceReason}" : string.Empty);
        auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                AuditEventTypes.CaixaFechamento,
                descricao,
                entityType: "CaixaSessao",
                entityId: openSession.Id)
            .GetAwaiter()
            .GetResult();

        return BuildStatus(currentUser.CompanyId, now);
    }

    public void EnsureVendaPermitida(AuthenticatedUser currentUser)
    {
        var status = BuildStatus(currentUser.CompanyId, DateTimeOffset.Now);
        if (status.CanSell) return;

        auditLogAB.RegistrarAsync(
                currentUser.CompanyId,
                currentUser.Id,
                currentUser.Name,
                AuditEventTypes.VendaBloqueada,
                $"Tentou vender com o caixa {status.State} — {status.BlockReason}")
            .GetAwaiter()
            .GetResult();

        throw new InvalidOperationException(status.BlockReason);
    }

    /// <summary>Dinheiro esperado na gaveta agora: abertura + vendas em dinheiro do turno + reforços - sangrias.</summary>
    private decimal ComputeExpectedCash(string companyId, CaixaSessionAD session, DateTimeOffset now)
    {
        var vendasPorFormaPagamento = caixaAB.ObterTotaisPorFormaPagamentoAsync(companyId, session.OpenedAt, now)
            .GetAwaiter()
            .GetResult();
        var vendasDinheiro = vendasPorFormaPagamento.GetValueOrDefault(FormaPagamentoDinheiro, 0m);

        var movimentos = caixaAB.ListarMovimentosAsync(companyId, session.Id).GetAwaiter().GetResult();
        var totalReforcos = movimentos.Where(item => item.Tipo == TipoMovimentoCaixa.Reforco).Sum(item => item.Valor);
        var totalSangrias = movimentos.Where(item => item.Tipo == TipoMovimentoCaixa.Sangria).Sum(item => item.Valor);

        return session.OpeningAmount + vendasDinheiro + totalReforcos - totalSangrias;
    }

    private CaixaStatusDto BuildStatus(string companyId, DateTimeOffset now)
    {
        var sessions = caixaAB.ListarSessoesAsync(companyId).GetAwaiter().GetResult();
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
        var openSessionDto = openSession is null ? null : ToDto(companyId, openSession, now, isCurrent: true);

        CaixaSessionDto BuildDto(CaixaSessionAD session) =>
            session == openSession && openSessionDto is not null ? openSessionDto : ToDto(companyId, session, now, isCurrent: false);

        return new CaixaStatusDto
        {
            State = state,
            CanSell = canSell,
            BlockReason = blockReason,
            ServerNow = now.ToString("o"),
            CurrentSession = openSessionDto,
            LastSession = lastSession is null ? null : BuildDto(lastSession),
            History = sessions.Take(12).Select(BuildDto).ToList()
        };
    }

    private CaixaSessionDto ToDto(string companyId, CaixaSessionAD source, DateTimeOffset now, bool isCurrent)
    {
        var closedAt = source.ClosedAt;
        var elapsed = (closedAt ?? now) - source.OpenedAt;

        var movimentos = caixaAB.ListarMovimentosAsync(companyId, source.Id).GetAwaiter().GetResult();
        var movimentosDto = movimentos.Select(item => new CaixaMovimentoDto
        {
            Id = item.Id,
            Tipo = item.Tipo.ToString(),
            Valor = HorusMoneyFormat.Format(item.Valor),
            Motivo = item.Motivo,
            CreatedAt = item.CreatedAt.ToString("o"),
            OperatorName = item.OperatorName,
        }).ToList();

        List<PaymentBreakdownDto>? paymentBreakdown = null;
        string? expectedCashPreview = null;
        if (isCurrent && closedAt is null)
        {
            var totals = caixaAB.ObterTotaisPorFormaPagamentoAsync(companyId, source.OpenedAt, now).GetAwaiter().GetResult();
            paymentBreakdown = totals
                .Select(item => new PaymentBreakdownDto { PaymentType = item.Key, Total = HorusMoneyFormat.Format(item.Value) })
                .ToList();
            expectedCashPreview = HorusMoneyFormat.Format(ComputeExpectedCash(companyId, source, now));
        }

        return new CaixaSessionDto
        {
            Id = source.Id,
            Status = closedAt is null ? "Aberto" : "Fechado",
            OpenedAt = source.OpenedAt.ToString("o"),
            ClosedAt = closedAt?.ToString("o"),
            OpeningAmount = HorusMoneyFormat.Format(source.OpeningAmount),
            ClosingAmount = HorusMoneyFormat.Format(source.ClosingAmount),
            OperatorName = source.OperatorName,
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
}

public class CaixaSessionDto
{
    public string Id { get; set; } = "";
    public string Status { get; set; } = "";
    public string OpenedAt { get; set; } = "";
    public string? ClosedAt { get; set; }
    public string OpeningAmount { get; set; } = "0,00";
    public string ClosingAmount { get; set; } = "0,00";
    public string OperatorName { get; set; } = "";
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
