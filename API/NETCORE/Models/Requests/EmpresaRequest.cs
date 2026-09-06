/**
 * Arquivo: API/NETCORE/Models/Requests/EmpresaRequest.cs
 * Objetivo: define contrato de entrada para operações de dados cadastrais, configurações e
 *           dados fiscais (emitente NFC-e) da empresa.
 * Entradas esperadas: recebe dados serializados do frontend nas ações da API.
 *
 * CertificadoPfxBase64/CertificadoSenha/Csc seguem o mesmo contrato do EmailSmtpPassword:
 * string vazia enviada pelo frontend significa "manter o valor já salvo" — os campos
 * "*HasValue"/"*Thumbprint" só existem para o frontend saber o que já está configurado sem
 * nunca receber o segredo de volta.
 */
namespace HORUSPDV_API.Models.Requests;

public class EmpresaRequest
{
    public string FantasyName { get; set; } = string.Empty;
    public string CorporateName { get; set; } = string.Empty;
    public string Cnpj { get; set; } = string.Empty;
    public string StateRegistration { get; set; } = string.Empty;
    public string Website { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string SacPhone { get; set; } = string.Empty;
    public string Phone { get; set; } = string.Empty;
    public string Mobile { get; set; } = string.Empty;
    public string Cep { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
    public string Number { get; set; } = string.Empty;
    public string Neighborhood { get; set; } = string.Empty;
    public string City { get; set; } = string.Empty;
    public string Uf { get; set; } = string.Empty;
    public string Complement { get; set; } = string.Empty;
    public bool EmailSmtpEnabled { get; set; }
    public string EmailSmtpHost { get; set; } = string.Empty;
    public int EmailSmtpPort { get; set; } = 587;
    public bool EmailSmtpEnableSsl { get; set; } = true;
    public string EmailSmtpUser { get; set; } = string.Empty;
    public string EmailSmtpPassword { get; set; } = string.Empty;
    public bool EmailSmtpHasPassword { get; set; }
    public string EmailSmtpFromEmail { get; set; } = string.Empty;
    public string EmailSmtpFromName { get; set; } = string.Empty;
    public string EmailSmtpReplyTo { get; set; } = string.Empty;

    // Dados fiscais (emitente NFC-e modelo 65)
    public byte Crt { get; set; } = 1;
    public string CnaeFiscal { get; set; } = string.Empty;
    public string CodigoMunicipioIbge { get; set; } = "3304557";
    public byte CodigoUfIbge { get; set; } = 33;
    public byte AmbienteFiscal { get; set; } = 2;
    public string CscId { get; set; } = string.Empty;
    public string Csc { get; set; } = string.Empty;
    public bool CscHasValue { get; set; }
    /// <summary>Certificado .pfx como base64. Vazio = manter o certificado já salvo.</summary>
    public string CertificadoPfxBase64 { get; set; } = string.Empty;
    public string CertificadoSenha { get; set; } = string.Empty;
    public bool CertificadoHasValue { get; set; }
    public string CertificadoThumbprint { get; set; } = string.Empty;
    public string? CertificadoValidoAte { get; set; }
    public string RespTecCnpj { get; set; } = string.Empty;
    public string RespTecContato { get; set; } = string.Empty;
    public string RespTecEmail { get; set; } = string.Empty;
    public string RespTecFone { get; set; } = string.Empty;
}
