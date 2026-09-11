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
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Fiscal;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente", "caixa")]
public class NfceController(
    DocumentoFiscalAB documentoFiscalAB,
    EmitenteFiscalStore emitenteFiscalStore,
    IFiscalProvider fiscalProvider) : ControllerBase
{
    [HttpGet]
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

    [HttpPost("{id}/reemitir")]
    [HorusAuthorizeRoles("administrador", "gerente")]
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
    [HorusAuthorizeRoles("administrador", "gerente")]
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

    [HttpPost("inutilizar")]
    [HorusAuthorizeRoles("administrador", "gerente")]
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
    [HorusAuthorizeRoles("administrador", "gerente")]
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

        using var memoryStream = new MemoryStream();
        using (var archive = new ZipArchive(memoryStream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var doc in docs)
            {
                var chaveOuNum = !string.IsNullOrWhiteSpace(doc.ChaveAcesso) ? doc.ChaveAcesso : $"NFCe_S{doc.Serie}_N{doc.NumeroNf}";

                // 1. XML da NFC-e (autorizada ou contingência):
                var xmlNota = !string.IsNullOrWhiteSpace(doc.XmlProtocolado) ? doc.XmlProtocolado : doc.XmlAssinado;
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

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
