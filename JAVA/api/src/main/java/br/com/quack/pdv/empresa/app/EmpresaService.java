package br.com.quack.pdv.empresa.app;

import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.empresa.web.EmpresaRequest;
import br.com.quack.pdv.security.SecretProtector;
import br.com.quack.pdv.shared.FormatoO;
import br.com.quack.pdv.shared.NegocioException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmpresaService {
    private final EmpresaRepository empresas;
    private final SecretProtector segredos;
    private final LeitorCertificado certificados;
    private final Clock clock;
    public EmpresaService(EmpresaRepository empresas, SecretProtector segredos, LeitorCertificado certificados, Clock clock) {
        this.empresas = empresas; this.segredos = segredos; this.certificados = certificados; this.clock = clock;
    }
    public EmpresaRequest obter(String companyId) { return resposta(atual(companyId)); }
    private Empresa atual(String companyId) {
        return empresas.findById(companyId).or(() -> empresas.findById("empresa-principal"))
            .orElseThrow(() -> new NegocioException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
    }
    @Transactional
    public EmpresaRequest salvar(String companyId, EmpresaRequest r) {
        if (texto(r.fantasyName).length() < 3) falhar("Nome fantasia e obrigatorio.");
        if (texto(r.cnpj).chars().filter(Character::isDigit).count() != 14) falhar("CNPJ invalido.");
        var propria = empresas.findById(companyId);
        Empresa corrente = propria.or(() -> empresas.findById("empresa-principal")).orElse(null);
        if (r.emailSmtpEnabled) {
            if (texto(r.emailSmtpHost).isEmpty()) falhar("Informe o host SMTP.");
            if (r.emailSmtpPort < 1 || r.emailSmtpPort > 65535) falhar("Informe uma porta SMTP valida.");
            if (!texto(r.emailSmtpUser).contains("@")) falhar("Informe o usuario SMTP.");
            if (!texto(r.emailSmtpFromEmail).contains("@")) falhar("Informe o e-mail remetente.");
            if (texto(r.emailSmtpPassword).isEmpty() && (corrente == null || segredo(corrente.getEmailSmtpPassword()).isBlank())) falhar("Informe a senha de app SMTP.");
            if (!texto(r.emailSmtpReplyTo).isEmpty() && !r.emailSmtpReplyTo.contains("@")) falhar("Informe um e-mail de resposta valido.");
        }
        if (r.crt < 1 || r.crt > 4) falhar("Regime tributario (CRT) invalido.");
        if (texto(r.cscId).length() > 6 || !texto(r.cscId).chars().allMatch(Character::isDigit)) falhar("CSC id deve ter só numeros, ate 6 digitos (e o \"Id do token\" do portal da SEFAZ-RJ).");
        LeitorCertificado.Dados certificado = null;
        if (!texto(r.certificadoPfxBase64).isEmpty()) {
            if (texto(r.certificadoSenha).isEmpty()) falhar("Informe a senha do certificado digital.");
            certificado = certificados.ler(r.certificadoPfxBase64, r.certificadoSenha);
        }
        // Fallback serves only the read/validation. Secrets are preserved from the target row, never copied from the principal.
        Empresa empresa = propria.orElseGet(Empresa::new);
        empresa.setId(companyId);
        if (empresa.getCreatedAt() == null) empresa.setCreatedAt(OffsetDateTime.now(clock));
        empresa.setFantasyName(texto(r.fantasyName));
        empresa.setCorporateName(texto(r.corporateName));
        empresa.setCnpj(texto(r.cnpj));
        empresa.setStateRegistration(texto(r.stateRegistration));
        empresa.setWebsite(texto(r.website));
        empresa.setEmail(texto(r.email));
        empresa.setSacPhone(texto(r.sacPhone));
        empresa.setPhone(texto(r.phone));
        empresa.setMobile(texto(r.mobile));
        empresa.setCep(texto(r.cep));
        empresa.setAddress(texto(r.address));
        empresa.setNumber(texto(r.number));
        empresa.setNeighborhood(texto(r.neighborhood));
        empresa.setCity(texto(r.city));
        empresa.setUf(texto(r.uf));
        empresa.setComplement(texto(r.complement));
        empresa.setEmailSmtpEnabled(r.emailSmtpEnabled);
        empresa.setEmailSmtpHost(texto(r.emailSmtpHost));
        empresa.setEmailSmtpPort(r.emailSmtpPort);
        empresa.setEmailSmtpEnableSsl(r.emailSmtpEnableSsl);
        empresa.setEmailSmtpUser(texto(r.emailSmtpUser));
        empresa.setEmailSmtpFromEmail(texto(r.emailSmtpFromEmail));
        empresa.setEmailSmtpFromName(texto(r.emailSmtpFromName));
        empresa.setEmailSmtpReplyTo(texto(r.emailSmtpReplyTo));
        empresa.setCrt((byte) r.crt);
        empresa.setCnaeFiscal(texto(r.cnaeFiscal));
        empresa.setCodigoMunicipioIbge(texto(r.codigoMunicipioIbge));
        empresa.setCodigoUfIbge((byte) r.codigoUfIbge);
        empresa.setAmbienteFiscal((byte) r.ambienteFiscal);
        empresa.setSerieNfce(r.serieNfce);
        empresa.setRespTecCnpj(texto(r.respTecCnpj));
        empresa.setRespTecContato(texto(r.respTecContato));
        empresa.setRespTecEmail(texto(r.respTecEmail));
        empresa.setRespTecFone(texto(r.respTecFone));
        empresa.setSerieNfce(r.serieNfce > 0 ? r.serieNfce : 2);
        if (!texto(r.cscId).isEmpty() || propria.isEmpty()) empresa.setCscId(texto(r.cscId));
        if (!texto(r.emailSmtpPassword).isEmpty()) empresa.setEmailSmtpPassword(segredos.protect(r.emailSmtpPassword));
        if (!texto(r.csc).isEmpty()) empresa.setCscCifrado(segredos.protect(r.csc));
        if (certificado != null) {
            empresa.setCertificadoPfxCifrado(segredos.protect(r.certificadoPfxBase64));
            empresa.setCertificadoSenhaCifrada(segredos.protect(r.certificadoSenha));
            empresa.setCertificadoThumbprint(certificado.thumbprint());
            empresa.setCertificadoValidoAte(certificado.validoAte());
        }
        return resposta(empresas.saveAndFlush(empresa));
    }
    private EmpresaRequest resposta(Empresa empresa) {
        EmpresaRequest r = new EmpresaRequest();
        r.fantasyName = lido(empresa.getFantasyName());
        r.corporateName = lido(empresa.getCorporateName());
        r.cnpj = lido(empresa.getCnpj());
        r.stateRegistration = lido(empresa.getStateRegistration());
        r.website = lido(empresa.getWebsite());
        r.email = lido(empresa.getEmail());
        r.sacPhone = lido(empresa.getSacPhone());
        r.phone = lido(empresa.getPhone());
        r.mobile = lido(empresa.getMobile());
        r.cep = lido(empresa.getCep());
        r.address = lido(empresa.getAddress());
        r.number = lido(empresa.getNumber());
        r.neighborhood = lido(empresa.getNeighborhood());
        r.city = lido(empresa.getCity());
        r.uf = lido(empresa.getUf());
        r.complement = lido(empresa.getComplement());
        r.emailSmtpEnabled = empresa.isEmailSmtpEnabled();
        r.emailSmtpHost = lido(empresa.getEmailSmtpHost());
        r.emailSmtpPort = empresa.getEmailSmtpPort();
        r.emailSmtpEnableSsl = empresa.isEmailSmtpEnableSsl();
        r.emailSmtpUser = lido(empresa.getEmailSmtpUser());
        r.emailSmtpFromEmail = lido(empresa.getEmailSmtpFromEmail());
        r.emailSmtpFromName = lido(empresa.getEmailSmtpFromName());
        r.emailSmtpReplyTo = lido(empresa.getEmailSmtpReplyTo());
        r.crt = Byte.toUnsignedInt(empresa.getCrt());
        r.cnaeFiscal = lido(empresa.getCnaeFiscal());
        r.codigoMunicipioIbge = lido(empresa.getCodigoMunicipioIbge());
        r.codigoUfIbge = Byte.toUnsignedInt(empresa.getCodigoUfIbge());
        r.ambienteFiscal = Byte.toUnsignedInt(empresa.getAmbienteFiscal());
        r.serieNfce = empresa.getSerieNfce();
        r.respTecCnpj = lido(empresa.getRespTecCnpj());
        r.respTecContato = lido(empresa.getRespTecContato());
        r.respTecEmail = lido(empresa.getRespTecEmail());
        r.respTecFone = lido(empresa.getRespTecFone());
        r.serieNfce = empresa.getSerieNfce() > 0 ? empresa.getSerieNfce() : 2;
        r.cscId = lido(empresa.getCscId());
        r.emailSmtpHasPassword = !segredo(empresa.getEmailSmtpPassword()).isBlank();
        r.cscHasValue = !segredo(empresa.getCscCifrado()).isBlank();
        r.certificadoHasValue = !segredo(empresa.getCertificadoPfxCifrado()).isBlank();
        r.certificadoThumbprint = lido(empresa.getCertificadoThumbprint());
        r.certificadoValidoAte = empresa.getCertificadoValidoAte() == null ? null : FormatoO.comOffset(empresa.getCertificadoValidoAte());
        return r;
    }
    private String segredo(String cifrado) {
        try { return segredos.unprotect(cifrado); } catch (RuntimeException e) { return ""; }
    }
    private static String lido(String v) { return v == null ? "" : v; }
    private static String texto(String v) { return v == null ? "" : v.trim(); }
    private static void falhar(String mensagem) { throw new NegocioException(HttpStatus.BAD_REQUEST, mensagem); }
}
