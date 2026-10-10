/**
 * Arquivo: API/NETCORE/Controllers/Fiscal/NfceController.cs
 * Objetivo: expõe endpoints HTTP de consulta, reemissão, cancelamento e inutilização de NFC-e
 *           e padroniza respostas para o frontend.
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras para
 *           o módulo fiscal (DocumentoFiscalAB / IFiscalProvider).
 *
 * A emissão em si nunca acontece aqui — só no NfceOutboxWorker, fora da thread de requisição.
 * Este controller enfileira e consulta; cancelamento e inutilização são operações raras e
 * relativamente rápidas, por isso rodam síncronas na própria requisição.
 */
using System.IO.Compression;
using System.Text;
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Security;
using HORUSPDV_API.Services.Shared;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Fiscal;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
public class NfceController(
    DocumentoFiscalAB documentoFiscalAB,
    EmitenteFiscalStore emitenteFiscalStore,
    IFiscalProvider fiscalProvider,
    HorusSecurityStore securityStore) : ControllerBase
{
    [HttpGet]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> Listar()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var rows = await documentoFiscalAB.ListarAsync(currentUser.CompanyId);
        return Ok(new ApiResponse<List<DocumentoFiscalResumo>>
        {
            Success = true,
            Message = "Documentos fiscais obtidos com sucesso.",
            Data = rows
        });
    }

    [HttpGet("{saleNumber}")]
    public async Task<IActionResult> ObterPorVenda(string saleNumber)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var detalhe = await documentoFiscalAB.ObterDetalhePorVendaAsync(currentUser.CompanyId, saleNumber);
        if (detalhe is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Documento fiscal não encontrado para esta venda." });
        }

        return Ok(new ApiResponse<DocumentoFiscalDetalhe>
        {
            Success = true,
            Message = "Documento fiscal obtido com sucesso.",
            Data = detalhe
        });
    }

    [HttpGet("buscar/{codigo}")]
    public async Task<IActionResult> ObterPorCodigo(string codigo)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var detalhe = await documentoFiscalAB.ObterDetalhePorCodigoAsync(currentUser.CompanyId, codigo);
        if (detalhe is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Nenhum documento fiscal encontrado para o código/chave informado." });
        }

        return Ok(new ApiResponse<DocumentoFiscalDetalhe>
        {
            Success = true,
            Message = "Documento fiscal obtido com sucesso.",
            Data = detalhe
        });
    }

    [HttpPost("{id}/reemitir")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> Reemitir(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var ok = await documentoFiscalAB.ReemitirAsync(currentUser.CompanyId, id);
        return ok
            ? Ok(new ApiResponse<object> { Success = true, Message = "Documento reenfileirado para nova tentativa de emissão." })
            : NotFound(new ApiResponse<object> { Success = false, Message = "Documento não encontrado ou não está com rejeição definitiva." });
    }

    [HttpPost("{id}/cancelar")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> Cancelar(string id, [FromBody] CancelamentoNfceRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.Justificativa) || request.Justificativa.Trim().Length < 15)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Justificativa deve ter no minimo 15 caracteres." });
        }

        var dados = await documentoFiscalAB.ObterParaCancelamentoAsync(currentUser.CompanyId, id);
        if (dados is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Documento não encontrado ou não está autorizado." });
        }

        var emitente = await emitenteFiscalStore.ObterAsync(currentUser.CompanyId);
        if (emitente is null)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa sem certificado ou CSC configurado." });
        }

        var resultado = await fiscalProvider.CancelarAsync(new CancelamentoRequest
        {
            Emitente = emitente,
            ChaveAcesso = dados.Value.ChaveAcesso,
            Protocolo = dados.Value.Protocolo,
            Justificativa = request.Justificativa.Trim(),
            SequenciaEvento = 1
        });

        if (resultado.Status != StatusDocumentoFiscal.Cancelado)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = resultado.MotivoStatus });
        }

        await documentoFiscalAB.MarcarCanceladoAsync(id, resultado);
        return Ok(new ApiResponse<object> { Success = true, Message = "NFC-e cancelada com sucesso." });
    }

    [HttpPost("{id}/cancelar-com-supervisor")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
    public async Task<IActionResult> CancelarComSupervisor(string id, [FromBody] CancelamentoComSupervisorRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.Justificativa) || request.Justificativa.Trim().Length < 15)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Justificativa deve ter no mínimo 15 caracteres." });
        }

        // Valida credenciais do supervisor
        var (valido, msgSupervisor, supervisor) = securityStore.ValidateSupervisorCredentials(
            request.SupervisorId,
            request.SupervisorPassword,
            currentUser.CompanyId);

        if (!valido || supervisor is null)
        {
            return StatusCode(StatusCodes.Status403Forbidden, new ApiResponse<object>
            {
                Success = false,
                Message = msgSupervisor
            });
        }

        var dados = await documentoFiscalAB.ObterParaCancelamentoAsync(currentUser.CompanyId, id);
        if (dados is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Documento não encontrado ou não está autorizado." });
        }

        var emitente = await emitenteFiscalStore.ObterAsync(currentUser.CompanyId);
        if (emitente is null)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa sem certificado ou CSC configurado." });
        }

        var resultado = await fiscalProvider.CancelarAsync(new CancelamentoRequest
        {
            Emitente = emitente,
            ChaveAcesso = dados.Value.ChaveAcesso,
            Protocolo = dados.Value.Protocolo,
            Justificativa = request.Justificativa.Trim(),
            SequenciaEvento = 1
        });

        if (resultado.Status != StatusDocumentoFiscal.Cancelado)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = resultado.MotivoStatus });
        }

        await documentoFiscalAB.MarcarCanceladoComAuditoriaAsync(
            currentUser.CompanyId,
            id,
            resultado,
            supervisor.Id,
            supervisor.Name,
            currentUser.Name,
            request.Justificativa.Trim());

        return Ok(new ApiResponse<object>
        {
            Success = true,
            Message = "NFC-e cancelada com sucesso perante a SEFAZ.",
            Data = new
            {
                documentoId = id,
                protocoloCancelamento = resultado.Protocolo,
                motivoStatus = resultado.MotivoStatus,
                supervisorNome = supervisor.Name
            }
        });
    }

    [HttpPost("inutilizar")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> Inutilizar([FromBody] InutilizacaoNfceRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.Justificativa) || request.Justificativa.Trim().Length < 15)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Justificativa deve ter no minimo 15 caracteres." });
        }

        if (request.NumeroInicial <= 0 || request.NumeroFinal < request.NumeroInicial)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Faixa de numeração inválida." });
        }

        var emitente = await emitenteFiscalStore.ObterAsync(currentUser.CompanyId);
        if (emitente is null)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa sem certificado ou CSC configurado." });
        }

        var resultado = await fiscalProvider.InutilizarAsync(new InutilizacaoRequest
        {
            Emitente = emitente,
            Serie = request.Serie,
            NumeroInicial = request.NumeroInicial,
            NumeroFinal = request.NumeroFinal,
            Justificativa = request.Justificativa.Trim()
        });

        return resultado.Status == StatusDocumentoFiscal.Inutilizado
            ? Ok(new ApiResponse<object> { Success = true, Message = "Faixa de numeração inutilizada com sucesso." })
            : BadRequest(new ApiResponse<object> { Success = false, Message = resultado.MotivoStatus });
    }

    [HttpGet("exportar-mes")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> ExportarXmlsMes([FromQuery] int ano, [FromQuery] int mes, CancellationToken ct = default)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (mes < 1 || mes > 12)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Mês deve estar entre 1 e 12." });
        }

        if (ano < 2000 || ano > DateTime.UtcNow.Year + 1)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Ano informado é inválido." });
        }

        var docs = await documentoFiscalAB.ObterXmlsPorMesAsync(currentUser.CompanyId, ano, mes, ct);
        if (docs.Count == 0)
        {
            return NotFound(new ApiResponse<object>
            {
                Success = false,
                Message = $"Nenhum documento fiscal autorizado ou cancelado encontrado em {mes:D2}/{ano}."
            });
        }

        var completeXmls = new Dictionary<string, string>();
        foreach (var doc in docs)
        {
            try
            {
                var xml = FiscalXmlArchive.Complete(doc.XmlAssinado, doc.XmlProtocolado,
                    doc.Status is StatusDocumentoFiscal.Autorizado or StatusDocumentoFiscal.Cancelado);
                if (string.IsNullOrWhiteSpace(xml)) throw new InvalidOperationException("XML assinado não disponível.");
                completeXmls.Add(doc.Id, xml);
            }
            catch (Exception ex) when (ex is InvalidOperationException or System.Xml.XmlException)
            {
                return Conflict(new ApiResponse<object> { Success = false,
                    Message = $"Não foi possível exportar a nota {doc.NumeroNf}, série {doc.Serie}: {ex.Message}" });
            }
        }

        using var memoryStream = new MemoryStream();
        using (var archive = new ZipArchive(memoryStream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var doc in docs)
            {
                var chaveOuNum = !string.IsNullOrWhiteSpace(doc.ChaveAcesso) ? doc.ChaveAcesso : $"NFCe_S{doc.Serie}_N{doc.NumeroNf}";

                // 1. XML da NFC-e (autorizada ou contingência):
                var xmlNota = completeXmls[doc.Id];
                if (!string.IsNullOrWhiteSpace(xmlNota))
                {
                    var entry = archive.CreateEntry($"{chaveOuNum}-nfe.xml", CompressionLevel.Optimal);
                    using var entryStream = entry.Open();
                    using var writer = new StreamWriter(entryStream, Encoding.UTF8);
                    writer.Write(xmlNota);
                }

                // 2. Se for cancelada, inclui também o XML do evento de cancelamento da SEFAZ:
                if (doc.Status == StatusDocumentoFiscal.Cancelado && !string.IsNullOrWhiteSpace(doc.XmlCancelamento))
                {
                    var entryCanc = archive.CreateEntry($"{chaveOuNum}-procEventoCanc.xml", CompressionLevel.Optimal);
                    using var entryStreamCanc = entryCanc.Open();
                    using var writerCanc = new StreamWriter(entryStreamCanc, Encoding.UTF8);
                    writerCanc.Write(doc.XmlCancelamento);
                }
            }
        }

        memoryStream.Seek(0, SeekOrigin.Begin);
        var zipBytes = memoryStream.ToArray();
        var nomeZip = $"NFCe_XMLs_{ano}_{mes:D2}.zip";

        return File(zipBytes, "application/zip", nomeZip);
    }

    [HttpGet("{id}/xml")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> BaixarXml(string id, [FromQuery] string? tipo = null)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var isCancelamento = string.Equals(tipo, "cancelamento", StringComparison.OrdinalIgnoreCase);
        (string? Xml, string? ChaveAcesso, StatusDocumentoFiscal Status, string? XmlCancelamento)? dados;
        try { dados = await documentoFiscalAB.ObterXmlAsync(currentUser.CompanyId, id, isCancelamento); }
        catch (Exception ex) when (ex is InvalidOperationException or System.Xml.XmlException)
        { return Conflict(new ApiResponse<object> { Success = false, Message = ex.Message }); }
        if (dados is null)
        {
            return NotFound(new ApiResponse<object> { Success = false, Message = "Documento fiscal não encontrado." });
        }

        var xml = isCancelamento ? dados.Value.XmlCancelamento : dados.Value.Xml;

        if (string.IsNullOrWhiteSpace(xml))
        {
            return NotFound(new ApiResponse<object>
            {
                Success = false,
                Message = isCancelamento
                    ? "XML de cancelamento não disponível para este documento."
                    : "XML do documento fiscal ainda não disponível."
            });
        }

        var chave = !string.IsNullOrWhiteSpace(dados.Value.ChaveAcesso) ? dados.Value.ChaveAcesso : id;
        var sufixo = isCancelamento ? "-procEventoCanc.xml" : "-nfe.xml";
        var fileName = $"{chave}{sufixo}";

        return File(Encoding.UTF8.GetBytes(xml), "application/xml", fileName);
    }

    [HttpGet("{id}/itens")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> ObterItens(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var itens = await documentoFiscalAB.ObterItensDocumentoAsync(currentUser.CompanyId, id);
        return Ok(new ApiResponse<List<DocumentoFiscalItemResumo>>
        {
            Success = true,
            Message = "Itens do documento fiscal obtidos com sucesso.",
            Data = itens
        });
    }

    [HttpGet("contingencia/pendentes")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> ObterContingenciasPendentes(CancellationToken ct = default)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var pendentes = await documentoFiscalAB.ObterContingenciasPendentesAsync(currentUser.CompanyId, ct);
        return Ok(new ApiResponse<List<DocumentoFiscalContingenciaResumo>>
        {
            Success = true,
            Message = "Documentos em contingência obtidos com sucesso.",
            Data = pendentes
        });
    }

    [HttpPost("contingencia/transmitir-pendentes")]
    [HorusAuthorizeRoles(HorusRoles.Administrador, HorusRoles.Financeiro)]
    public async Task<IActionResult> TransmitirContingenciasPendentes(CancellationToken ct = default)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var resultado = await documentoFiscalAB.TransmitirContingenciasAsync(
            currentUser.CompanyId, fiscalProvider, emitenteFiscalStore, ct);

        return Ok(new ApiResponse<TransmitirContingenciasResultado>
        {
            Success = true,
            Message = $"Transmissão concluída. {resultado.TotalAutorizadas} autorizada(s), {resultado.TotalFalhas} falha(s).",
            Data = resultado
        });
    }

    [HttpPost("contingencia/emitir")]
    [HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
    public async Task<IActionResult> EmitirContingenciaManual(
        [FromBody] EmitirContingenciaManualRequest request, CancellationToken ct = default)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null) return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.VendaId))
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Identificador da venda obrigatório." });
        }

        var emitente = await emitenteFiscalStore.ObterAsync(currentUser.CompanyId, ct);
        if (emitente is null)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa sem certificado digital A1 (.pfx) ou CSC configurado." });
        }

        var (numeroNf, serie, ambiente) = await documentoFiscalAB.AlocarNumeroNfceAsync(currentUser.CompanyId, ct);
        var docPendente = new DocumentoFiscalPendente
        {
            Id = $"temp-{DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()}",
            CompanyId = currentUser.CompanyId,
            VendaId = request.VendaId,
            Modelo = 65,
            Serie = serie,
            NumeroNf = numeroNf,
            TipoEmissao = TipoEmissaoFiscal.ContingenciaOffline,
            Tentativas = 0,
            DhContingencia = HorusDateTime.Now,
            JustContingencia = request.Justificativa
        };

        var nfceRequest = await documentoFiscalAB.MontarRequisicaoAsync(docPendente, emitente, ct);
        var reqComContingencia = nfceRequest with
        {
            TipoEmissao = TipoEmissaoFiscal.ContingenciaOffline,
            DhContingencia = docPendente.DhContingencia,
            JustificativaContingencia = docPendente.JustContingencia
        };

        var resultado = await fiscalProvider.EmitirContingenciaNfceAsync(reqComContingencia, ct);
        if (resultado.Status == StatusDocumentoFiscal.ContingenciaPendente && !string.IsNullOrWhiteSpace(resultado.XmlAssinado))
        {
            var docId = await documentoFiscalAB.EnfileirarContingenciaAsync(
                currentUser.CompanyId,
                request.VendaId,
                serie,
                numeroNf,
                ambiente,
                resultado.ChaveAcesso ?? string.Empty,
                resultado.XmlAssinado,
                request.Justificativa ?? "Emissao em contingencia offline por indisponibilidade SEFAZ",
                docPendente.DhContingencia.Value,
                resultado.QrCodeUrl,
                ct);

            return Ok(new ApiResponse<ResultadoFiscal>
            {
                Success = true,
                Message = "NFC-e emitida em contingência offline com sucesso.",
                Data = resultado with { Protocolo = docId }
            });
        }

        return BadRequest(new ApiResponse<object>
        {
            Success = false,
            Message = resultado.MotivoStatus
        });
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}

public sealed record EmitirContingenciaManualRequest
{
    public required string VendaId { get; init; }
    public string? Justificativa { get; init; }
}

