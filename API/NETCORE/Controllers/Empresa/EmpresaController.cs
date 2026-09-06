/**
 * Arquivo: API/NETCORE/Controllers/Empresa/EmpresaController.cs
 * Objetivo: expõe endpoints HTTP de dados cadastrais e configurações da empresa e padroniza respostas para o frontend.
 * Entradas esperadas: recebe requisições REST, valida dados básicos e delega regras para serviços/repositórios.
 */
using HORUSPDV_API.Models.Requests;
using HORUSPDV_API.Models.Response;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Repositories.DatabaseAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.AspNetCore.Mvc;

namespace HORUSPDV_API.Controllers.Empresa;

[ApiController]
[Route("api/[controller]")]
[HorusAuthorizeRoles("administrador")]
public class EmpresaController(EmpresaAB empresaAB) : ControllerBase
{
    [HttpGet]
    public async Task<IActionResult> Obter()
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<EmpresaRequest> { Success = false, Message = "Sessão não encontrada." });
        }

        var empresa = await empresaAB.ObterAsync(currentUser.CompanyId);
        if (empresa is null)
        {
            return NotFound(new ApiResponse<EmpresaRequest> { Success = false, Message = "Empresa não encontrada." });
        }

        return Ok(new ApiResponse<EmpresaRequest>
        {
            Success = true,
            Message = "Dados da empresa obtidos com sucesso.",
            Data = ToRequest(empresa)
        });
    }

    [HttpPut]
    public async Task<IActionResult> Atualizar([FromBody] EmpresaRequest request)
    {
        var currentUser = GetCurrentUser();
        if (currentUser is null)
        {
            return Unauthorized(new ApiResponse<EmpresaRequest> { Success = false, Message = "Sessão não encontrada." });
        }

        if (string.IsNullOrWhiteSpace(request.FantasyName) || request.FantasyName.Trim().Length < 3)
        {
            return BadRequest(new ApiResponse<EmpresaRequest> { Success = false, Message = "Nome fantasia e obrigatorio." });
        }

        if (request.Cnpj.Count(char.IsDigit) != 14)
        {
            return BadRequest(new ApiResponse<EmpresaRequest> { Success = false, Message = "CNPJ invalido." });
        }

        var current = await empresaAB.ObterAsync(currentUser.CompanyId);
        if (request.EmailSmtpEnabled)
        {
            var validationMessage = ValidateEmailConfiguration(
                request,
                !string.IsNullOrWhiteSpace(current?.EmailSmtpPassword));
            if (!string.IsNullOrWhiteSpace(validationMessage))
            {
                return BadRequest(new ApiResponse<EmpresaRequest> { Success = false, Message = validationMessage });
            }
        }

        if (request.Crt is < 1 or > 4)
        {
            return BadRequest(new ApiResponse<EmpresaRequest> { Success = false, Message = "Regime tributario (CRT) invalido." });
        }

        var cscId = request.CscId?.Trim() ?? string.Empty;
        if (cscId.Length > 6 || !cscId.All(char.IsDigit))
        {
            return BadRequest(new ApiResponse<EmpresaRequest>
            {
                Success = false,
                Message = "CSC id deve ter só numeros, ate 6 digitos (e o \"Id do token\" do portal da SEFAZ-RJ)."
            });
        }

        try
        {
            var saved = await empresaAB.SalvarAsync(currentUser.CompanyId, ToDataAccess(request));
            return Ok(new ApiResponse<EmpresaRequest>
            {
                Success = true,
                Message = "Dados da empresa atualizados com sucesso.",
                Data = ToRequest(saved)
            });
        }
        catch (InvalidOperationException ex)
        {
            return BadRequest(new ApiResponse<EmpresaRequest> { Success = false, Message = ex.Message });
        }
    }

    private static EmpresaAD ToDataAccess(EmpresaRequest source) => new()
    {
        FantasyName = source.FantasyName,
        CorporateName = source.CorporateName,
        Cnpj = source.Cnpj,
        StateRegistration = source.StateRegistration,
        Website = source.Website,
        Email = source.Email,
        SacPhone = source.SacPhone,
        Phone = source.Phone,
        Mobile = source.Mobile,
        Cep = source.Cep,
        Address = source.Address,
        Number = source.Number,
        Neighborhood = source.Neighborhood,
        City = source.City,
        Uf = source.Uf,
        Complement = source.Complement,
        EmailSmtpEnabled = source.EmailSmtpEnabled,
        EmailSmtpHost = source.EmailSmtpHost,
        EmailSmtpPort = source.EmailSmtpPort,
        EmailSmtpEnableSsl = source.EmailSmtpEnableSsl,
        EmailSmtpUser = source.EmailSmtpUser,
        EmailSmtpPassword = source.EmailSmtpPassword,
        EmailSmtpFromEmail = source.EmailSmtpFromEmail,
        EmailSmtpFromName = source.EmailSmtpFromName,
        EmailSmtpReplyTo = source.EmailSmtpReplyTo,
        Crt = source.Crt,
        CnaeFiscal = source.CnaeFiscal,
        CodigoMunicipioIbge = source.CodigoMunicipioIbge,
        CodigoUfIbge = source.CodigoUfIbge,
        AmbienteFiscal = source.AmbienteFiscal,
        CscId = source.CscId,
        Csc = source.Csc,
        CertificadoPfxBase64 = source.CertificadoPfxBase64,
        CertificadoSenha = source.CertificadoSenha,
        RespTecCnpj = source.RespTecCnpj,
        RespTecContato = source.RespTecContato,
        RespTecEmail = source.RespTecEmail,
        RespTecFone = source.RespTecFone
    };

    private static EmpresaRequest ToRequest(EmpresaAD source) => new()
    {
        FantasyName = source.FantasyName,
        CorporateName = source.CorporateName,
        Cnpj = source.Cnpj,
        StateRegistration = source.StateRegistration,
        Website = source.Website,
        Email = source.Email,
        SacPhone = source.SacPhone,
        Phone = source.Phone,
        Mobile = source.Mobile,
        Cep = source.Cep,
        Address = source.Address,
        Number = source.Number,
        Neighborhood = source.Neighborhood,
        City = source.City,
        Uf = source.Uf,
        Complement = source.Complement,
        EmailSmtpEnabled = source.EmailSmtpEnabled,
        EmailSmtpHost = source.EmailSmtpHost,
        EmailSmtpPort = source.EmailSmtpPort,
        EmailSmtpEnableSsl = source.EmailSmtpEnableSsl,
        EmailSmtpUser = source.EmailSmtpUser,
        EmailSmtpPassword = string.Empty,
        EmailSmtpHasPassword = !string.IsNullOrWhiteSpace(source.EmailSmtpPassword),
        EmailSmtpFromEmail = source.EmailSmtpFromEmail,
        EmailSmtpFromName = source.EmailSmtpFromName,
        EmailSmtpReplyTo = source.EmailSmtpReplyTo,
        Crt = source.Crt,
        CnaeFiscal = source.CnaeFiscal,
        CodigoMunicipioIbge = source.CodigoMunicipioIbge,
        CodigoUfIbge = source.CodigoUfIbge,
        AmbienteFiscal = source.AmbienteFiscal,
        CscId = source.CscId,
        Csc = string.Empty,
        CscHasValue = !string.IsNullOrWhiteSpace(source.Csc),
        CertificadoPfxBase64 = string.Empty,
        CertificadoSenha = string.Empty,
        CertificadoHasValue = !string.IsNullOrWhiteSpace(source.CertificadoPfxBase64),
        CertificadoThumbprint = source.CertificadoThumbprint,
        CertificadoValidoAte = source.CertificadoValidoAte?.ToString("o"),
        RespTecCnpj = source.RespTecCnpj,
        RespTecContato = source.RespTecContato,
        RespTecEmail = source.RespTecEmail,
        RespTecFone = source.RespTecFone
    };

    private static string ValidateEmailConfiguration(EmpresaRequest request, bool hasExistingPassword)
    {
        if (string.IsNullOrWhiteSpace(request.EmailSmtpHost))
        {
            return "Informe o host SMTP.";
        }

        if (request.EmailSmtpPort is < 1 or > 65535)
        {
            return "Informe uma porta SMTP valida.";
        }

        if (string.IsNullOrWhiteSpace(request.EmailSmtpUser) || !request.EmailSmtpUser.Contains('@'))
        {
            return "Informe o usuario SMTP.";
        }

        if (string.IsNullOrWhiteSpace(request.EmailSmtpFromEmail) || !request.EmailSmtpFromEmail.Contains('@'))
        {
            return "Informe o e-mail remetente.";
        }

        if (string.IsNullOrWhiteSpace(request.EmailSmtpPassword) && !hasExistingPassword)
        {
            return "Informe a senha de app SMTP.";
        }

        if (!string.IsNullOrWhiteSpace(request.EmailSmtpReplyTo) && !request.EmailSmtpReplyTo.Contains('@'))
        {
            return "Informe um e-mail de resposta valido.";
        }

        return string.Empty;
    }

    private AuthenticatedUser? GetCurrentUser()
        => HttpContext.Items["CurrentUser"] as AuthenticatedUser;
}
