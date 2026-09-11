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

        var pendentes = await documentos.ObterPendentesAsync(LoteMaximo, ct);
        if (pendentes.Count == 0) return;

        foreach (var doc in pendentes)
        {
            ct.ThrowIfCancellationRequested();

            try
            {
                var emitente = await emitentes.ObterAsync(doc.CompanyId, ct);
                if (emitente is null)
                {
                    logger.LogWarning(
                        "NFC-e do documento {Id} (venda {VendaId}) não transmitida: Empresa sem certificado digital A1 (.pfx) ou CSC configurado em Minha Empresa.",
                        doc.Id, doc.VendaId);
                    await documentos.MarcarErroAsync(
                        doc.Id, "Empresa sem certificado ou CSC configurado.", proximaTentativa: null, ct);
                    continue;
                }

                var request = await documentos.MontarRequisicaoAsync(doc, emitente, ct);
                var resultado = await provider.EmitirNfceAsync(request, ct);

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

                    case StatusDocumentoFiscal.Rejeitado when resultado.CodigoStatus == 539:
                        // Duplicidade de NF-e com chave diferente — o número já foi queimado
                        // na SEFAZ. Renumera automaticamente e recoloca na fila.
                        await documentos.MarcarRejeitadoAsync(doc.Id, resultado, ct);
                        var renumerou = await documentos.ReemitirAsync(doc.CompanyId, doc.Id);
                        if (renumerou)
                        {
                            logger.LogWarning(
                                "NFC-e rejeitada por duplicidade (539). Documento {Id} renumerado automaticamente e reenfileirado.",
                                doc.Id);
                        }
                        else
                        {
                            logger.LogError(
                                "NFC-e rejeitada por duplicidade (539). Documento {Id} não pôde ser renumerado — requer intervenção manual.",
                                doc.Id);
                        }
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
        if (tentativas >= 3 && doc.TipoEmissao == TipoEmissaoFiscal.Normal)
        {
            var disponivel = await provider.ServicoDisponivelAsync(emitente, ct);
            if (!disponivel)
            {
                await documentos.PromoverParaContingenciaAsync(
                    doc.Id,
                    justificativa: "SEFAZ indisponivel apos tentativas consecutivas de transmissao.",
                    ct);

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
                proximaTentativa: null,
                ct);

            logger.LogError(
                "Documento {Id} esgotou as tentativas e saiu da fila. Requer intervencao manual.",
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
    public required int Serie { get; init; }
    public required int NumeroNf { get; init; }
    public required TipoEmissaoFiscal TipoEmissao { get; init; }
    public required int Tentativas { get; init; }
    public DateTimeOffset? DhContingencia { get; init; }
    public string? JustContingencia { get; init; }
}
