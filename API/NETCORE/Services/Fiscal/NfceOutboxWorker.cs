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
