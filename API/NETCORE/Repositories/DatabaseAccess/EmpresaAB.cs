/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EmpresaAB.cs
 * Objetivo: concentra comandos SQL e persistência de dados cadastrais, configurações e dados
 *           fiscais (emitente NFC-e) da empresa.
 * Entradas esperadas: recebe conexão configurada, parâmetros normalizados e executa leitura/escrita no SQL Server.
 *
 * CSC, senha do certificado e o próprio certificado (.pfx, como base64) seguem o mesmo padrão
 * já usado para EmailSmtpPassword: cifrados em repouso via HorusSecretProtector, e uma string
 * vazia recebida no request significa "manter o valor já salvo" (nunca apaga sem intenção).
 */
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using HORUSPDV_API.Repositories.DataAccess;
using HORUSPDV_API.Services.Security;
using Microsoft.Data.SqlClient;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class EmpresaAB(Connection connection, HorusSecretProtector secretProtector)
{
    private const string Columns = """
        FantasyName, CorporateName, Cnpj, StateRegistration, Website, Email, SacPhone,
        Phone, Mobile, Cep, Address, Number, Neighborhood, City, Uf, Complement,
        EmailSmtpEnabled, EmailSmtpHost, EmailSmtpPort, EmailSmtpEnableSsl, EmailSmtpUser,
        EmailSmtpPassword, EmailSmtpFromEmail, EmailSmtpFromName, EmailSmtpReplyTo,
        Crt, CnaeFiscal, CodigoMunicipioIbge, CodigoUfIbge, AmbienteFiscal,
        CscId, CscCifrado, CertificadoPfxCifrado, CertificadoSenhaCifrada,
        CertificadoThumbprint, CertificadoValidoAte, RespTecCnpj, RespTecContato,
        RespTecEmail, RespTecFone
        """;

    public Task<EmpresaAD?> ObterPrincipalAsync()
        => ObterAsync("empresa-principal");

    public async Task<EmpresaAD?> ObterAsync(string companyId)
    {
        var sql = $"""
            SELECT {Columns}
            FROM Empresas
            WHERE Id = @Id;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", companyId);
        await using var reader = await command.ExecuteReaderAsync();
        return await reader.ReadAsync() ? Map(reader) : null;
    }

    public Task<EmpresaAD> SalvarPrincipalAsync(EmpresaAD empresa)
        => SalvarAsync("empresa-principal", empresa);

    public async Task<EmpresaAD> SalvarAsync(string companyId, EmpresaAD empresa)
    {
        // Certificado novo enviado nesta chamada -> valida e recalcula thumbprint/validade.
        // Nenhum certificado enviado -> mantém os quatro campos derivados como estão no banco
        // (a mesma condição de "string vazia" decide isso no SQL abaixo).
        if (!string.IsNullOrWhiteSpace(empresa.CertificadoPfxBase64))
        {
            if (string.IsNullOrWhiteSpace(empresa.CertificadoSenha))
            {
                throw new InvalidOperationException("Informe a senha do certificado digital.");
            }

            try
            {
                var pfxBytes = Convert.FromBase64String(empresa.CertificadoPfxBase64);
                using var certificate = new X509Certificate2(
                    pfxBytes,
                    empresa.CertificadoSenha,
                    X509KeyStorageFlags.EphemeralKeySet | X509KeyStorageFlags.Exportable);
                empresa.CertificadoThumbprint = certificate.Thumbprint;
                empresa.CertificadoValidoAte = new DateTimeOffset(certificate.NotAfter.ToUniversalTime(), TimeSpan.Zero);
            }
            catch (Exception ex) when (ex is CryptographicException or FormatException)
            {
                throw new InvalidOperationException("Certificado digital ou senha inválidos.");
            }
        }

        const string sql = """
            IF EXISTS (SELECT 1 FROM Empresas WHERE Id = @Id)
            BEGIN
                UPDATE Empresas
                   SET FantasyName = @FantasyName,
                       CorporateName = @CorporateName,
                       Cnpj = @Cnpj,
                       StateRegistration = @StateRegistration,
                       Website = @Website,
                       Email = @Email,
                       SacPhone = @SacPhone,
                       Phone = @Phone,
                       Mobile = @Mobile,
                       Cep = @Cep,
                       Address = @Address,
                       Number = @Number,
                       Neighborhood = @Neighborhood,
                       City = @City,
                       Uf = @Uf,
                       Complement = @Complement,
                       EmailSmtpEnabled = @EmailSmtpEnabled,
                       EmailSmtpHost = @EmailSmtpHost,
                       EmailSmtpPort = @EmailSmtpPort,
                       EmailSmtpEnableSsl = @EmailSmtpEnableSsl,
                       EmailSmtpUser = @EmailSmtpUser,
                       EmailSmtpPassword = CASE
                           WHEN @EmailSmtpPassword = N'' THEN EmailSmtpPassword
                           ELSE @EmailSmtpPassword
                       END,
                       EmailSmtpFromEmail = @EmailSmtpFromEmail,
                       EmailSmtpFromName = @EmailSmtpFromName,
                       EmailSmtpReplyTo = @EmailSmtpReplyTo,
                       Crt = @Crt,
                       CnaeFiscal = @CnaeFiscal,
                       CodigoMunicipioIbge = @CodigoMunicipioIbge,
                       CodigoUfIbge = @CodigoUfIbge,
                       AmbienteFiscal = @AmbienteFiscal,
                       CscId = CASE WHEN @CscId = N'' THEN CscId ELSE @CscId END,
                       CscCifrado = CASE WHEN @CscCifrado = N'' THEN CscCifrado ELSE @CscCifrado END,
                       CertificadoPfxCifrado = CASE WHEN @CertificadoPfxCifrado = N'' THEN CertificadoPfxCifrado ELSE @CertificadoPfxCifrado END,
                       CertificadoSenhaCifrada = CASE WHEN @CertificadoPfxCifrado = N'' THEN CertificadoSenhaCifrada ELSE @CertificadoSenhaCifrada END,
                       CertificadoThumbprint = CASE WHEN @CertificadoPfxCifrado = N'' THEN CertificadoThumbprint ELSE @CertificadoThumbprint END,
                       CertificadoValidoAte = CASE WHEN @CertificadoPfxCifrado = N'' THEN CertificadoValidoAte ELSE @CertificadoValidoAte END,
                       RespTecCnpj = @RespTecCnpj,
                       RespTecContato = @RespTecContato,
                       RespTecEmail = @RespTecEmail,
                       RespTecFone = @RespTecFone
                 WHERE Id = @Id;
            END
            ELSE
            BEGIN
                INSERT INTO Empresas
                    (Id, FantasyName, CorporateName, Cnpj, StateRegistration, Website, Email, SacPhone,
                     Phone, Mobile, Cep, Address, Number, Neighborhood, City, Uf, Complement,
                     EmailSmtpEnabled, EmailSmtpHost, EmailSmtpPort, EmailSmtpEnableSsl, EmailSmtpUser,
                     EmailSmtpPassword, EmailSmtpFromEmail, EmailSmtpFromName, EmailSmtpReplyTo,
                     Crt, CnaeFiscal, CodigoMunicipioIbge, CodigoUfIbge, AmbienteFiscal,
                     CscId, CscCifrado, CertificadoPfxCifrado, CertificadoSenhaCifrada,
                     CertificadoThumbprint, CertificadoValidoAte, RespTecCnpj, RespTecContato,
                     RespTecEmail, RespTecFone)
                VALUES
                    (@Id, @FantasyName, @CorporateName, @Cnpj, @StateRegistration, @Website,
                     @Email, @SacPhone, @Phone, @Mobile, @Cep, @Address, @Number, @Neighborhood, @City, @Uf, @Complement,
                     @EmailSmtpEnabled, @EmailSmtpHost, @EmailSmtpPort, @EmailSmtpEnableSsl, @EmailSmtpUser,
                     @EmailSmtpPassword, @EmailSmtpFromEmail, @EmailSmtpFromName, @EmailSmtpReplyTo,
                     @Crt, @CnaeFiscal, @CodigoMunicipioIbge, @CodigoUfIbge, @AmbienteFiscal,
                     @CscId, @CscCifrado, @CertificadoPfxCifrado, @CertificadoSenhaCifrada,
                     @CertificadoThumbprint, @CertificadoValidoAte, @RespTecCnpj, @RespTecContato,
                     @RespTecEmail, @RespTecFone);
            END;
            """;

        await using var db = await connection.OpenConnectionAsync();
        await using var command = new SqlCommand(sql, db);
        command.Parameters.AddWithValue("@Id", companyId);
        AddParameters(command, empresa);
        await command.ExecuteNonQueryAsync();
        return await ObterAsync(companyId) ?? empresa;
    }

    private void AddParameters(SqlCommand command, EmpresaAD source)
    {
        command.Parameters.AddWithValue("@FantasyName", source.FantasyName.Trim());
        command.Parameters.AddWithValue("@CorporateName", source.CorporateName.Trim());
        command.Parameters.AddWithValue("@Cnpj", source.Cnpj.Trim());
        command.Parameters.AddWithValue("@StateRegistration", source.StateRegistration.Trim());
        command.Parameters.AddWithValue("@Website", source.Website.Trim());
        command.Parameters.AddWithValue("@Email", source.Email.Trim());
        command.Parameters.AddWithValue("@SacPhone", source.SacPhone.Trim());
        command.Parameters.AddWithValue("@Phone", source.Phone.Trim());
        command.Parameters.AddWithValue("@Mobile", source.Mobile.Trim());
        command.Parameters.AddWithValue("@Cep", source.Cep.Trim());
        command.Parameters.AddWithValue("@Address", source.Address.Trim());
        command.Parameters.AddWithValue("@Number", source.Number.Trim());
        command.Parameters.AddWithValue("@Neighborhood", source.Neighborhood.Trim());
        command.Parameters.AddWithValue("@City", source.City.Trim());
        command.Parameters.AddWithValue("@Uf", source.Uf.Trim());
        command.Parameters.AddWithValue("@Complement", source.Complement.Trim());
        command.Parameters.AddWithValue("@EmailSmtpEnabled", source.EmailSmtpEnabled);
        command.Parameters.AddWithValue("@EmailSmtpHost", source.EmailSmtpHost.Trim());
        command.Parameters.AddWithValue("@EmailSmtpPort", source.EmailSmtpPort);
        command.Parameters.AddWithValue("@EmailSmtpEnableSsl", source.EmailSmtpEnableSsl);
        command.Parameters.AddWithValue("@EmailSmtpUser", source.EmailSmtpUser.Trim());
        command.Parameters.AddWithValue(
            "@EmailSmtpPassword",
            string.IsNullOrWhiteSpace(source.EmailSmtpPassword)
                ? string.Empty
                : secretProtector.Protect(source.EmailSmtpPassword));
        command.Parameters.AddWithValue("@EmailSmtpFromEmail", source.EmailSmtpFromEmail.Trim());
        command.Parameters.AddWithValue("@EmailSmtpFromName", source.EmailSmtpFromName.Trim());
        command.Parameters.AddWithValue("@EmailSmtpReplyTo", source.EmailSmtpReplyTo.Trim());

        command.Parameters.AddWithValue("@Crt", source.Crt);
        command.Parameters.AddWithValue("@CnaeFiscal", source.CnaeFiscal.Trim());
        command.Parameters.AddWithValue("@CodigoMunicipioIbge", source.CodigoMunicipioIbge.Trim());
        command.Parameters.AddWithValue("@CodigoUfIbge", source.CodigoUfIbge);
        command.Parameters.AddWithValue("@AmbienteFiscal", source.AmbienteFiscal);
        command.Parameters.AddWithValue("@CscId", source.CscId.Trim());
        command.Parameters.AddWithValue(
            "@CscCifrado",
            string.IsNullOrWhiteSpace(source.Csc) ? string.Empty : secretProtector.Protect(source.Csc));
        command.Parameters.AddWithValue(
            "@CertificadoPfxCifrado",
            string.IsNullOrWhiteSpace(source.CertificadoPfxBase64) ? string.Empty : secretProtector.Protect(source.CertificadoPfxBase64));
        command.Parameters.AddWithValue(
            "@CertificadoSenhaCifrada",
            string.IsNullOrWhiteSpace(source.CertificadoSenha) ? string.Empty : secretProtector.Protect(source.CertificadoSenha));
        command.Parameters.AddWithValue(
            "@CertificadoThumbprint",
            string.IsNullOrWhiteSpace(source.CertificadoThumbprint) ? (object)DBNull.Value : source.CertificadoThumbprint);
        command.Parameters.AddWithValue(
            "@CertificadoValidoAte",
            source.CertificadoValidoAte.HasValue ? source.CertificadoValidoAte.Value : (object)DBNull.Value);
        command.Parameters.AddWithValue("@RespTecCnpj", source.RespTecCnpj.Trim());
        command.Parameters.AddWithValue("@RespTecContato", source.RespTecContato.Trim());
        command.Parameters.AddWithValue("@RespTecEmail", source.RespTecEmail.Trim());
        command.Parameters.AddWithValue("@RespTecFone", source.RespTecFone.Trim());
    }

    private EmpresaAD Map(SqlDataReader source) => new()
    {
        FantasyName = ReadString(source, "FantasyName"),
        CorporateName = ReadString(source, "CorporateName"),
        Cnpj = ReadString(source, "Cnpj"),
        StateRegistration = ReadString(source, "StateRegistration"),
        Website = ReadString(source, "Website"),
        Email = ReadString(source, "Email"),
        SacPhone = ReadString(source, "SacPhone"),
        Phone = ReadString(source, "Phone"),
        Mobile = ReadString(source, "Mobile"),
        Cep = ReadString(source, "Cep"),
        Address = ReadString(source, "Address"),
        Number = ReadString(source, "Number"),
        Neighborhood = ReadString(source, "Neighborhood"),
        City = ReadString(source, "City"),
        Uf = ReadString(source, "Uf"),
        Complement = ReadString(source, "Complement"),
        EmailSmtpEnabled = ReadBool(source, "EmailSmtpEnabled"),
        EmailSmtpHost = ReadString(source, "EmailSmtpHost"),
        EmailSmtpPort = ReadInt(source, "EmailSmtpPort"),
        EmailSmtpEnableSsl = ReadBool(source, "EmailSmtpEnableSsl"),
        EmailSmtpUser = ReadString(source, "EmailSmtpUser"),
        EmailSmtpPassword = UnprotectSecret(ReadString(source, "EmailSmtpPassword")),
        EmailSmtpFromEmail = ReadString(source, "EmailSmtpFromEmail"),
        EmailSmtpFromName = ReadString(source, "EmailSmtpFromName"),
        EmailSmtpReplyTo = ReadString(source, "EmailSmtpReplyTo"),

        Crt = (byte)ReadInt(source, "Crt"),
        CnaeFiscal = ReadString(source, "CnaeFiscal"),
        CodigoMunicipioIbge = ReadString(source, "CodigoMunicipioIbge"),
        CodigoUfIbge = (byte)ReadInt(source, "CodigoUfIbge"),
        AmbienteFiscal = (byte)ReadInt(source, "AmbienteFiscal"),
        CscId = ReadString(source, "CscId"),
        Csc = UnprotectSecret(ReadString(source, "CscCifrado")),
        CertificadoPfxBase64 = UnprotectSecret(ReadString(source, "CertificadoPfxCifrado")),
        CertificadoSenha = UnprotectSecret(ReadString(source, "CertificadoSenhaCifrada")),
        CertificadoThumbprint = ReadString(source, "CertificadoThumbprint"),
        CertificadoValidoAte = ReadNullableDateTimeOffset(source, "CertificadoValidoAte"),
        RespTecCnpj = ReadString(source, "RespTecCnpj"),
        RespTecContato = ReadString(source, "RespTecContato"),
        RespTecEmail = ReadString(source, "RespTecEmail"),
        RespTecFone = ReadString(source, "RespTecFone")
    };

    private static string ReadString(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? string.Empty : reader.GetString(ordinal);
    }

    private static bool ReadBool(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return !reader.IsDBNull(ordinal) && reader.GetBoolean(ordinal);
    }

    private static int ReadInt(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? 0 : Convert.ToInt32(reader.GetValue(ordinal));
    }

    private static DateTimeOffset? ReadNullableDateTimeOffset(SqlDataReader reader, string name)
    {
        var ordinal = reader.GetOrdinal(name);
        return reader.IsDBNull(ordinal) ? null : reader.GetDateTimeOffset(ordinal);
    }

    private string UnprotectSecret(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return string.Empty;
        if (!value.StartsWith("enc:v1:", StringComparison.Ordinal)) return value;

        try
        {
            return secretProtector.Unprotect(value);
        }
        catch
        {
            return string.Empty;
        }
    }
}
