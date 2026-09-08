/**
 * Arquivo: API/NETCORE/Repositories/DataAccess/EmpresaAD.cs
 * Objetivo: representa estrutura de dados de dados cadastrais, configurações e dados fiscais
 *           (emitente NFC-e) da empresa retornada pelo acesso ao banco.
 * Entradas esperadas: recebe valores lidos do SQL Server e alimenta serviços/repositórios superiores.
 *
 * CertificadoPfxBase64/CertificadoSenha/Csc chegam aqui já descriptografados por EmpresaAB
 * (mesmo padrão do EmailSmtpPassword) — nunca ficam cifrados fora da camada de acesso a dados.
 */
namespace HORUSPDV_API.Repositories.DataAccess;

public class EmpresaAD
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
    public string EmailSmtpFromEmail { get; set; } = string.Empty;
    public string EmailSmtpFromName { get; set; } = string.Empty;
    public string EmailSmtpReplyTo { get; set; } = string.Empty;

    // Dados fiscais (emitente NFC-e modelo 65) — ver DataBase/Migrations/02_estrutura_fiscal.sql
    /// <summary>1 Simples, 2 Simples excesso sublimite, 3 Regime Normal, 4 MEI.</summary>
    public byte Crt { get; set; } = 1;
    public string CnaeFiscal { get; set; } = string.Empty;
    public string CodigoMunicipioIbge { get; set; } = "3304557";
    public byte CodigoUfIbge { get; set; } = 33;
    /// <summary>1 Produção, 2 Homologação.</summary>
    public byte AmbienteFiscal { get; set; } = 2;
    /// <summary>Série da NFC-e (padrão 2 para evitar colisão com sistemas legados na série 1).</summary>
    public int SerieNfce { get; set; } = 2;
    public string CscId { get; set; } = string.Empty;
    public string Csc { get; set; } = string.Empty;
    public string CertificadoPfxBase64 { get; set; } = string.Empty;
    public string CertificadoSenha { get; set; } = string.Empty;
    public string CertificadoThumbprint { get; set; } = string.Empty;
    public DateTimeOffset? CertificadoValidoAte { get; set; }
    public string RespTecCnpj { get; set; } = string.Empty;
    public string RespTecContato { get; set; } = string.Empty;
    public string RespTecEmail { get; set; } = string.Empty;
    public string RespTecFone { get; set; } = string.Empty;
}
