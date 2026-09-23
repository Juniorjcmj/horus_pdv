/**
 * Arquivo: API/NETCORE/Controllers/Fiscal/NfeController.cs
 * Objetivo: expõe endpoints para emissão, consulta e cancelamento de NF-e modelo 55
 *           (venda para empresas com CNPJ).
 * A emissão é assíncrona via NfceOutboxWorker (mesmo worker, modelo diferente).
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Fiscal;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Fiscal;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador", "gerente", "atendente")]
public class NfeController(
    DocumentoFiscalAB documentoFiscalAB,
    EmitenteFiscalStore emitenteFiscalStore,
    IFiscalProvider fiscalProvider) : ControllerBase
{
    /// <summary>
    /// Enfileira uma NF-e modelo 55 para emissão a partir de uma venda já registrada.
    /// O worker processa a fila e transmite à SEFAZ.
    /// </summary>
    [HttpPost("emitir")]
    public async Task<IActionResult> Emitir([FromBody] EmitirNfeHttpRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.SaleNumber))
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Número da venda é obrigatório." });

        var dest = request.Destinatario;
        if (string.IsNullOrWhiteSpace(dest.CpfCnpj) || dest.CpfCnpj.Length < 11)
            return BadRequest(new ApiResponse<object> { Success = false, Message = "CPF/CNPJ do destinatário é obrigatório." });

        if (string.IsNullOrWhiteSpace(dest.Nome))
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Nome/Razão Social do destinatário é obrigatório." });

        if (string.IsNullOrWhiteSpace(dest.Logradouro) || string.IsNullOrWhiteSpace(dest.Bairro) ||
            string.IsNullOrWhiteSpace(dest.NomeMunicipio) || string.IsNullOrWhiteSpace(dest.Uf))
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Endereço completo do destinatário é obrigatório para NF-e." });

        // Verifica se o emitente tem certificado configurado
        var emitente = await emitenteFiscalStore.ObterAsync(currentUser.CompanyId);
        if (emitente is null)
            return BadRequest(new ApiResponse<object>
            {
                Success = false,
                Message = "Empresa sem certificado digital A1 (.pfx) ou CSC configurado. Configure em Minha Empresa antes de emitir NF-e."
            });

        try
        {
            // Serializa o destinatário para persistir na fila
            var destinatarioJson = System.Text.Json.JsonSerializer.Serialize(new DestinatarioFiscal
            {
                CpfCnpj = new string(dest.CpfCnpj.Where(char.IsDigit).ToArray()),
                Nome = dest.Nome,
                IndIeDest = dest.IndIeDest,
                InscricaoEstadual = dest.InscricaoEstadual,
                Logradouro = dest.Logradouro,
                Numero = dest.Numero,
                Complemento = dest.Complemento,
                Bairro = dest.Bairro,
                CodigoMunicipioIbge = dest.CodigoMunicipioIbge,
                NomeMunicipio = dest.NomeMunicipio,
                Uf = dest.Uf,
                Cep = new string(dest.Cep.Where(char.IsDigit).ToArray()),
                Fone = dest.Fone,
                Email = dest.Email
            });

            // Constrói o VendaId a partir do SaleNumber (formato: sale-{companyId}-{saleNumber})
            var vendaId = $"sale-{currentUser.CompanyId}-{request.SaleNumber}";

            var docId = await documentoFiscalAB.EnfileirarNfeAsync(
                currentUser.CompanyId,
                vendaId,
                destinatarioJson,
                request.NaturezaOperacao,
                request.ModalidadeFrete);

            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "NF-e enfileirada para emissão. O documento será transmitido à SEFAZ em instantes.",
                Data = new { documentoId = docId }
            });
        }
        catch (Exception ex)
        {
            return BadRequest(new ApiResponse<object> { Success = false, Message = ex.Message });
        }
    }

    /// <summary>Lista documentos fiscais modelo 55 da empresa.</summary>
    [HttpGet]
    public async Task<IActionResult> Listar()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var rows = await documentoFiscalAB.ListarAsync(currentUser.CompanyId);
        // Filtra apenas NF-e modelo 55 (a listagem geral inclui ambos)
        // Como ListarAsync não filtra por modelo, filtramos aqui pelo prefixo do Id
        var nfeRows = rows.Where(r => r.Id.StartsWith("nfe-")).ToList();
        return Ok(new ApiResponse<List<DocumentoFiscalResumo>>
        {
            Success = true,
            Message = "NF-e listadas com sucesso.",
            Data = nfeRows
        });
    }

    /// <summary>Cancela uma NF-e modelo 55 autorizada.</summary>
    [HttpPost("{id}/cancelar")]
    public async Task<IActionResult> Cancelar(string id, [FromBody] CancelamentoNfceRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        if (string.IsNullOrWhiteSpace(request.Justificativa) || request.Justificativa.Length < 15)
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Justificativa deve ter no mínimo 15 caracteres." });

        var dados = await documentoFiscalAB.ObterParaCancelamentoAsync(currentUser.CompanyId, id);
        if (dados is null)
            return NotFound(new ApiResponse<object> { Success = false, Message = "NF-e não encontrada ou não está autorizada." });

        var emitente = await emitenteFiscalStore.ObterAsync(currentUser.CompanyId);
        if (emitente is null)
            return BadRequest(new ApiResponse<object> { Success = false, Message = "Empresa sem certificado configurado." });

        var resultado = await fiscalProvider.CancelarAsync(new CancelamentoRequest
        {
            Emitente = emitente,
            ChaveAcesso = dados.Value.ChaveAcesso,
            Protocolo = dados.Value.Protocolo,
            Justificativa = request.Justificativa,
            SequenciaEvento = 1
        });

        if (resultado.Status == StatusDocumentoFiscal.Cancelado)
        {
            await documentoFiscalAB.MarcarCanceladoAsync(id, resultado);
            return Ok(new ApiResponse<object>
            {
                Success = true,
                Message = "NF-e cancelada com sucesso na SEFAZ."
            });
        }

        return BadRequest(new ApiResponse<object>
        {
            Success = false,
            Message = $"Falha ao cancelar: {resultado.MotivoStatus}"
        });
    }

    /// <summary>Download do XML de uma NF-e.</summary>
    [HttpGet("{id}/xml")]
    public async Task<IActionResult> DownloadXml(string id)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
            return Unauthorized(new ApiResponse<object> { Success = false, Message = "Sessão não encontrada." });

        var dados = await documentoFiscalAB.ObterXmlAsync(currentUser.CompanyId, id);
        if (dados is null || string.IsNullOrWhiteSpace(dados.Value.Xml))
            return NotFound(new ApiResponse<object> { Success = false, Message = "XML não disponível." });

        var chave = dados.Value.ChaveAcesso ?? id;
        var bytes = System.Text.Encoding.UTF8.GetBytes(dados.Value.Xml);
        return File(bytes, "application/xml", $"nfe-{chave}.xml");
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
