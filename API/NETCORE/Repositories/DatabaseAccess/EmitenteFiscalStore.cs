/**
 * Arquivo: API/NETCORE/Repositories/DatabaseAccess/EmitenteFiscalStore.cs
 * Objetivo: monta o ContextoEmitente (dados do emitente já descriptografados) usado pelo
 *           IFiscalProvider, a partir dos dados cadastrais/fiscais já persistidos em Empresas.
 * Entradas esperadas: recebe o CompanyId; devolve null quando a empresa ainda não tem
 *           certificado ou CSC configurado (o NfceOutboxWorker trata esse caso sem derrubar a fila).
 */
using HORUSPDV_API.Services.Fiscal;

namespace HORUSPDV_API.Repositories.DatabaseAccess;

public class EmitenteFiscalStore(EmpresaAB empresaAB)
{
    public async Task<ContextoEmitente?> ObterAsync(string companyId, CancellationToken ct = default)
    {
        var empresa = await empresaAB.ObterAsync(companyId);
        if (empresa is null) return null;

        if (string.IsNullOrWhiteSpace(empresa.CertificadoPfxBase64) ||
            string.IsNullOrWhiteSpace(empresa.CertificadoSenha) ||
            string.IsNullOrWhiteSpace(empresa.CscId) ||
            string.IsNullOrWhiteSpace(empresa.Csc))
        {
            return null;
        }

        byte[] certificadoPfx;
        try
        {
            certificadoPfx = Convert.FromBase64String(empresa.CertificadoPfxBase64);
        }
        catch (FormatException)
        {
            return null;
        }

        return new ContextoEmitente
        {
            CompanyId = companyId,
            Cnpj = new string(empresa.Cnpj.Where(char.IsDigit).ToArray()),
            InscricaoEstadual = empresa.StateRegistration,
            RazaoSocial = empresa.CorporateName,
            NomeFantasia = string.IsNullOrWhiteSpace(empresa.FantasyName) ? empresa.CorporateName : empresa.FantasyName,
            Crt = empresa.Crt,
            Cnae = empresa.CnaeFiscal,
            Logradouro = empresa.Address,
            Numero = empresa.Number,
            Complemento = empresa.Complement,
            Bairro = empresa.Neighborhood,
            CodigoMunicipioIbge = empresa.CodigoMunicipioIbge,
            NomeMunicipio = empresa.City,
            Uf = empresa.Uf,
            Cep = new string(empresa.Cep.Where(char.IsDigit).ToArray()),
            Fone = new string(empresa.Phone.Where(char.IsDigit).ToArray()),
            Ambiente = empresa.AmbienteFiscal,
            CscId = empresa.CscId,
            Csc = empresa.Csc,
            CertificadoPfx = certificadoPfx,
            CertificadoSenha = empresa.CertificadoSenha,
            RespTecCnpj = new string(empresa.RespTecCnpj.Where(char.IsDigit).ToArray()),
            RespTecContato = empresa.RespTecContato,
            RespTecEmail = empresa.RespTecEmail,
            RespTecFone = new string(empresa.RespTecFone.Where(char.IsDigit).ToArray())
        };
    }
}
