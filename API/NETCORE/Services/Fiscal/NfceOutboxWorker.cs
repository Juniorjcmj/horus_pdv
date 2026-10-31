/**
 * Arquivo: API/NETCORE/Services/Fiscal/NfceOutboxWorker.cs
 * Objetivo: processa a fila de documentos fiscais pendentes fora da thread de requisição,
 *           aplicando retentativa com backoff e decidindo entrada/saída de contingência.
 * Entradas esperadas: lê DocumentosFiscais com Status em (Assinado, Transmitindo, ContingenciaPendente).
 *
 * Por que existe: a API do Hercules.NET.NFe.NFCe é síncrona e uma chamada à SEFAZ pode levar
 * dezenas de segundos. Emitir dentro do request bloqueia thread do pool e derruba a frente de
 * caixa junto com a SEFAZ. Aqui, a venda é gravada e liberada na hora; a nota é transmitida em
 * seguida.
 *
 * Contingência offline (tpEmis=9): a NFC-e é emitida localmente, o DANFE sai com a tarja de
 * contingência e o XML é transmitido quando o serviço voltar. O prazo de transmissão é de 24h.
 * Por isso a fila vive no banco, não em memória — reinício de processo não pode perder documento.
 */

using HORUSPDV_API.Repositories.DatabaseAccess;

namespace HORUSPDV_API.Services.Fiscal;

public sealed class NfceOutboxWorker(
    IServiceScopeFactory scopeFactory,
    ILogger<NfceOutboxWorker> logger) : BackgroundService
{
    private static readonly TimeSpan Intervalo = TimeSpan.FromSeconds(1);
    private const int LoteMaximo = 20;
    private const int MaxTentativas = 12;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        logger.LogInformation("NfceOutboxWorker iniciado.");

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await ProcessarLoteAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                // Nunca deixar a exceção derrubar o worker: sem ele, para de emitir.
                logger.LogError(ex, "Erro no ciclo do NfceOutboxWorker.");
            }

            await Task.Delay(Intervalo, stoppingToken);
        }
    }

    private async Task ProcessarLoteAsync(CancellationToken ct)
    {
        using var scope = scopeFactory.CreateScope();
        var documentos = scope.ServiceProvider.GetRequiredService<DocumentoFiscalAB>();
        var emitentes = scope.ServiceProvider.GetRequiredService<EmitenteFiscalStore>();
        var provider = scope.ServiceProvider.GetRequiredService<IFiscalProvider>();
        var auditoria = scope.ServiceProvider.GetRequiredService<AuditLogAB>();

        // Retoma também o intervalo entre decidir a emissão e gravar a fila, inclusive
        // após reinício da API. Documentos rejeitados continuam para revisão manual.
        foreach (var sale in await documentos.ObterVendasSemDocumentoFiscalAsync(LoteMaximo, ct))
        {
            try { await documentos.EnfileirarAsync(sale.CompanyId, sale.VendaId, ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception ex) { logger.LogError(ex, "Falha ao retomar fila fiscal da venda {VendaId}.", sale.VendaId); }
        }

        var pendentes = await documentos.ObterPendentesAsync(LoteMaximo, ct);
        if (pendentes.Count == 0) return;

        foreach (var queued in pendentes)
        {
            var doc = queued;
            ct.ThrowIfCancellationRequested();

            try
            {
                // Venda cancelada antes de a nota ser assinada: não emite (o número é inutilizado).
                var cancelamento = await documentos.ObterCancelamentoDaVendaAsync(doc.CompanyId, doc.VendaId, ct);
                if (cancelamento is not null && string.IsNullOrWhiteSpace(doc.XmlAssinado))
                {
                    await NaoEmitirAsync(documentos, emitentes, provider, auditoria, doc, cancelamento, ct);
                    continue;
                }

                var emitente = await emitentes.ObterAsync(doc.CompanyId, ct);
                if (emitente is null)
                {
                    var tipoDoc = doc.Modelo == 55 ? "NF-e" : "NFC-e";
                    logger.LogWarning(
                        "{TipoDoc} do documento {Id} (venda {VendaId}) não transmitida: Empresa sem certificado digital A1 (.pfx) ou CSC configurado em Minha Empresa.",
                        tipoDoc, doc.Id, doc.VendaId);
                    await documentos.MarcarErroAsync(
                        doc.Id, "Empresa sem certificado ou CSC configurado.", proximaTentativa: null, ct);
                    continue;
                }

                ResultadoFiscal resultado;
                if (!string.IsNullOrWhiteSpace(doc.XmlAssinado))
                {
                    logger.LogInformation(
                        "Consultando a chave original antes de transmitir. Documento {Id} Chave {Chave}",
                        doc.Id, doc.ChaveAcesso);
                    resultado = await provider.TransmitirDocumentoAsync(emitente, doc.XmlAssinado, ct);
                }
                else
                {
                    if (doc.Modelo == 55)
                    {
                        var nfeRequest = await documentos.MontarRequisicaoNfeAsync(doc, emitente, ct);
                        resultado = await provider.PrepararNfeAsync(nfeRequest, ct);
                    }
                    else
                    {
                        var nfceRequest = await documentos.MontarRequisicaoAsync(doc, emitente, ct);
                        resultado = await provider.PrepararNfceAsync(nfceRequest, ct);
                    }
                    if (!string.IsNullOrWhiteSpace(resultado.XmlAssinado))
                    {
                        // The original survives a timeout, restart, product edit or lost HTTP response.
                        await documentos.GuardarAssinaturaAsync(doc.CompanyId, doc.Id, resultado, ct);
                        doc = doc with { XmlAssinado = resultado.XmlAssinado, ChaveAcesso = resultado.ChaveAcesso };
                        resultado = await provider.TransmitirDocumentoAsync(emitente, doc.XmlAssinado, ct);
                    }
                }

                switch (resultado.Status)
                {
                    case StatusDocumentoFiscal.Autorizado:
                        await documentos.MarcarAutorizadoAsync(doc.Id, resultado, ct);
                        logger.LogInformation(
                            "NFC-e autorizada. Chave {Chave} protocolo {Protocolo}",
                            resultado.ChaveAcesso, resultado.Protocolo);
                        // A venda foi cancelada enquanto a nota estava na fila: cancela a nota na SEFAZ agora
                        // (dentro do prazo de 30 minutos da NFC-e).
                        var canceladaDepois = await documentos.ObterCancelamentoDaVendaAsync(doc.CompanyId, doc.VendaId, ct);
                        if (canceladaDepois is not null)
                        {
                            await CancelarAposAutorizacaoAsync(documentos, provider, auditoria, emitente, doc, resultado,
                                canceladaDepois, ct);
                        }
                        break;

                    case StatusDocumentoFiscal.Denegado:
                        // Numeração queimada. Não reaproveitar o número, não retentar.
                        await documentos.MarcarDenegadoAsync(doc.Id, resultado, ct);
                        break;

                    case StatusDocumentoFiscal.Cancelado:
                        await documentos.MarcarCancelamentoConsultadoAsync(doc.Id, resultado, ct);
                        break;

                    case StatusDocumentoFiscal.Rejeitado when !resultado.Retentavel:
                        // Erro de conteúdo. Reenviar só gastaria numeração — vai para
                        // revisão manual e o número deve ser inutilizado depois.
                        await documentos.MarcarRejeitadoAsync(doc.Id, resultado, ct);
                        logger.LogWarning(
                            "NFC-e rejeitada definitivamente. Documento {Id} cStat {CStat}: {Motivo}",
                            doc.Id, resultado.CodigoStatus, resultado.MotivoStatus);
                        break;

                    default:
                        await TratarFalhaTransitoriaAsync(documentos, provider, emitente, doc, resultado, ct);
                        break;
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                // Erro inesperado ao montar a requisição (ex.: produto excluído após a venda).
                // Não deixa um documento ruim travar o restante do lote a cada ciclo — aplica
                // backoff curto e segue para o próximo.
                logger.LogError(ex, "Falha inesperada ao processar o documento fiscal {Id}.", doc.Id);
                await documentos.MarcarErroAsync(
                    doc.Id, ex.Message, DateTimeOffset.UtcNow.AddSeconds(30), ct);
            }
        }
    }

    /// <summary>
    /// Nota de venda cancelada que nunca foi assinada: inutiliza o número na SEFAZ (NFC-e com certificado) e tira da
    /// fila. Sem certificado, NF-e ou falha definitiva: tira da fila como rejeitada, com o aviso para inutilizar o
    /// número manualmente. Falha transitória (SEFAZ fora): tenta de novo depois.
    /// </summary>
    private async Task NaoEmitirAsync(
        DocumentoFiscalAB documentos,
        EmitenteFiscalStore emitentes,
        IFiscalProvider provider,
        AuditLogAB auditoria,
        DocumentoFiscalPendente doc,
        DocumentoFiscalAB.CancelamentoDaVenda cancelamento,
        CancellationToken ct)
    {
        var tipo = doc.Modelo == 55 ? "NF-e" : "NFC-e";
        var venda = cancelamento.SaleNumber ?? doc.VendaId;
        var justificativa = JustificativaSefaz("Venda cancelada no caixa antes da emissao do documento fiscal");
        string motivo;
        var status = StatusDocumentoFiscal.Rejeitado;

        var emitente = await emitentes.ObterAsync(doc.CompanyId, ct);
        if (doc.Modelo != 55 && emitente is not null)
        {
            var r = await provider.InutilizarAsync(new InutilizacaoRequest
            {
                Emitente = emitente,
                Serie = doc.Serie,
                NumeroInicial = doc.NumeroNf,
                NumeroFinal = doc.NumeroNf,
                Justificativa = justificativa,
            }, ct);
            if (r.Status == StatusDocumentoFiscal.Inutilizado)
            {
                status = StatusDocumentoFiscal.Inutilizado;
                motivo = $"{tipo} não emitida: a venda #{venda} foi cancelada antes da emissão. Número {doc.NumeroNf} " +
                         $"(série {doc.Serie}) inutilizado na SEFAZ.";
            }
            else if (r.Retentavel)
            {
                await documentos.MarcarErroAsync(doc.Id,
                    $"Venda cancelada: inutilização do número adiada ({r.MotivoStatus}).",
                    DateTimeOffset.UtcNow.AddMinutes(2), ct);
                return;
            }
            else
            {
                motivo = $"{tipo} não emitida: a venda #{venda} foi cancelada antes da emissão. A SEFAZ recusou a " +
                         $"inutilização do número {doc.NumeroNf} (série {doc.Serie}): {r.MotivoStatus}. Inutilize manualmente.";
            }
        }
        else
        {
            motivo = $"{tipo} não emitida: a venda #{venda} foi cancelada antes da emissão. Inutilize o número " +
                     $"{doc.NumeroNf} (série {doc.Serie}) na SEFAZ" +
                     (emitente is null ? " (empresa sem certificado configurado)." : ".");
        }

        if (!await documentos.EncerrarSemEmissaoAsync(doc.CompanyId, doc.Id, status, motivo, ct))
        {
            return; // a nota mudou (foi assinada nesse meio tempo): o fluxo normal segue e cancela após autorizar
        }
        logger.LogWarning("Documento {Id}: {Motivo}", doc.Id, motivo);
        await RegistrarAsync(auditoria, doc.CompanyId, cancelamento, AuditEventTypes.NfceNaoEmitida, motivo, doc.Id);
    }

    /// <summary>Nota autorizada de uma venda já cancelada: cancela na SEFAZ e grava a trilha.</summary>
    private async Task CancelarAposAutorizacaoAsync(
        DocumentoFiscalAB documentos,
        IFiscalProvider provider,
        AuditLogAB auditoria,
        ContextoEmitente emitente,
        DocumentoFiscalPendente doc,
        ResultadoFiscal autorizacao,
        DocumentoFiscalAB.CancelamentoDaVenda cancelamento,
        CancellationToken ct)
    {
        var tipo = doc.Modelo == 55 ? "NF-e" : "NFC-e";
        var venda = cancelamento.SaleNumber ?? doc.VendaId;
        var justificativa = JustificativaSefaz("Venda cancelada no caixa: " + cancelamento.Justificativa);
        string? erro = null;
        try
        {
            var r = await provider.CancelarAsync(new CancelamentoRequest
            {
                Emitente = emitente,
                ChaveAcesso = autorizacao.ChaveAcesso ?? doc.ChaveAcesso ?? string.Empty,
                Protocolo = autorizacao.Protocolo ?? string.Empty,
                Justificativa = justificativa,
                SequenciaEvento = 1,
            }, ct);
            if (r.Status == StatusDocumentoFiscal.Cancelado)
            {
                await documentos.MarcarCanceladoComAuditoriaAsync(doc.CompanyId, doc.Id, r,
                    cancelamento.SupervisorId ?? string.Empty, cancelamento.SupervisorNome ?? string.Empty,
                    cancelamento.OperadorNome ?? string.Empty, justificativa, ct,
                    operadorId: cancelamento.OperadorId, terminal: "fila fiscal (automático)");
                logger.LogInformation("{Tipo} {Id} da venda cancelada {Venda} cancelada na SEFAZ automaticamente.",
                    tipo, doc.Id, venda);
                return;
            }
            erro = r.MotivoStatus;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            erro = ex.Message;
        }

        var motivo = $"{tipo} nº {doc.NumeroNf} autorizada para a venda #{venda}, que está cancelada, e o cancelamento " +
                     $"automático na SEFAZ falhou: {erro}. Cancele a nota manualmente (prazo de 30 minutos da autorização).";
        logger.LogError("Documento {Id}: {Motivo}", doc.Id, motivo);
        await RegistrarAsync(auditoria, doc.CompanyId, cancelamento, AuditEventTypes.NfceCancelamentoPendente, motivo, doc.Id);
    }

    private static async Task RegistrarAsync(AuditLogAB auditoria, string companyId,
        DocumentoFiscalAB.CancelamentoDaVenda cancelamento, string evento, string descricao, string docId)
    {
        try
        {
            await auditoria.RegistrarAsync(companyId, cancelamento.OperadorId ?? string.Empty,
                cancelamento.OperadorNome ?? "Fila fiscal", evento,
                descricao.Length <= 500 ? descricao : descricao[..500], "DocumentosFiscais", docId, "fila fiscal");
        }
        catch
        {
            // trilha não pode derrubar o worker
        }
    }

    /// <summary>A SEFAZ exige de 15 a 255 caracteres, sem quebra de linha.</summary>
    private static string JustificativaSefaz(string texto)
    {
        var t = string.Join(' ', texto.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries));
        if (t.Length < 15) t = (t + " - venda cancelada no caixa").Trim();
        return t.Length <= 255 ? t : t[..255];
    }

    private async Task TratarFalhaTransitoriaAsync(
        DocumentoFiscalAB documentos,
        IFiscalProvider provider,
        ContextoEmitente emitente,
        DocumentoFiscalPendente doc,
        ResultadoFiscal resultado,
        CancellationToken ct)
    {
        var tentativas = doc.Tentativas + 1;

        // Após 3 falhas seguidas, confirma com o status do serviço antes de
        // assumir contingência. Um timeout isolado não justifica tpEmis=9.
        if (tentativas >= 3 && doc.TipoEmissao == TipoEmissaoFiscal.Normal && string.IsNullOrWhiteSpace(doc.XmlAssinado))
        {
            var disponivel = await provider.ServicoDisponivelAsync(emitente, ct);
            if (!disponivel)
            {
                await documentos.PromoverParaContingenciaAsync(
                    doc.Id,
                    justificativa: "SEFAZ indisponivel apos tentativas consecutivas de transmissao.",
                    ct: ct);

                logger.LogWarning(
                    "Documento {Id} promovido para contingencia offline (empresa {CompanyId}).",
                    doc.Id, doc.CompanyId);
                return;
            }
        }

        if (tentativas >= MaxTentativas)
        {
            await documentos.MarcarErroAsync(
                doc.Id,
                $"Excedidas {MaxTentativas} tentativas. Ultimo erro: {resultado.MotivoStatus}",
                proximaTentativa: DateTimeOffset.UtcNow.AddMinutes(5),
                ct);

            logger.LogError(
                "Documento {Id} atingiu o limite de tentativas. XML original preservado; consultas a cada cinco minutos, com revisão manual recomendada.",
                doc.Id);
            return;
        }

        // Backoff exponencial com teto de 5 minutos.
        var atraso = TimeSpan.FromSeconds(Math.Min(300, Math.Pow(2, tentativas)));
        await documentos.MarcarErroAsync(
            doc.Id,
            resultado.MotivoStatus,
            proximaTentativa: DateTimeOffset.UtcNow.Add(atraso),
            ct);
    }
}

/// <summary>Projeção mínima da fila, para o worker não carregar XML à toa.</summary>
public sealed record DocumentoFiscalPendente
{
    public required string Id { get; init; }
    public required string CompanyId { get; init; }
    public required string VendaId { get; init; }
    public short Modelo { get; init; } = 65;
    public required int Serie { get; init; }
    public required int NumeroNf { get; init; }
    public required TipoEmissaoFiscal TipoEmissao { get; init; }
    public required int Tentativas { get; init; }
    public DateTimeOffset? DhContingencia { get; init; }
    public string? JustContingencia { get; init; }
    public string? XmlAssinado { get; init; }
    public string? ChaveAcesso { get; init; }
    public StatusDocumentoFiscal Status { get; init; } = StatusDocumentoFiscal.Assinado;

    // Campos exclusivos de NF-e modelo 55
    public string? DestinatarioJson { get; init; }
    public string? NaturezaOperacao { get; init; }
    public byte ModalidadeFrete { get; init; } = 9;
}
