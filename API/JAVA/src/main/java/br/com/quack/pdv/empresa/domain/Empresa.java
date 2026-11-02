package br.com.quack.pdv.empresa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Complete Empresas mapping: registration, SMTP, fiscal configuration and platform administration. */
@Entity
@Table(name = "Empresas")
public class Empresa {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    private String id;

    @Column(name = "FantasyName", length = 180, nullable = false)
    private String fantasyName = "";

    @Column(name = "CorporateName", length = 180, nullable = false)
    private String corporateName = "";

    @Column(name = "Cnpj", length = 30, nullable = false)
    private String cnpj = "";

    @Column(name = "Email", length = 180, nullable = false)
    private String email = "";

    @Column(name = "StateRegistration", length = 60, nullable = false)
    private String stateRegistration = "";

    @Column(name = "Website", length = 250, nullable = false)
    private String website = "";

    @Column(name = "SacPhone", length = 30, nullable = false)
    private String sacPhone = "";

    @Column(name = "Phone", length = 30, nullable = false)
    private String phone = "";

    @Column(name = "Mobile", length = 30, nullable = false)
    private String mobile = "";

    @Column(name = "Cep", length = 20, nullable = false)
    private String cep = "";

    @Column(name = "Address", length = 180, nullable = false)
    private String address = "";

    @Column(name = "Number", length = 30, nullable = false)
    private String number = "";

    @Column(name = "Neighborhood", length = 120, nullable = false)
    private String neighborhood = "";

    @Column(name = "City", length = 120, nullable = false)
    private String city = "";

    @Column(name = "Uf", length = 2, nullable = false)
    private String uf = "";

    @Column(name = "Complement", length = 180, nullable = false)
    private String complement = "";

    @Column(name = "EmailSmtpEnabled", nullable = false)
    private boolean emailSmtpEnabled;

    @Column(name = "EmailSmtpHost", length = 180, nullable = false)
    private String emailSmtpHost = "smtp-mail.outlook.com";

    @Column(name = "EmailSmtpPort", nullable = false)
    private int emailSmtpPort = 587;

    @Column(name = "EmailSmtpEnableSsl", nullable = false)
    private boolean emailSmtpEnableSsl = true;

    @Column(name = "EmailSmtpUser", length = 180, nullable = false)
    private String emailSmtpUser = "";

    @Column(name = "EmailSmtpPassword", length = 500, nullable = false)
    private String emailSmtpPassword = "";

    @Column(name = "EmailSmtpFromEmail", length = 180, nullable = false)
    private String emailSmtpFromEmail = "";

    @Column(name = "EmailSmtpFromName", length = 180, nullable = false)
    private String emailSmtpFromName = "";

    @Column(name = "EmailSmtpReplyTo", length = 180, nullable = false)
    private String emailSmtpReplyTo = "";

    @Column(name = "Crt", nullable = false)
    private byte crt = 1;

    @Column(name = "CnaeFiscal", length = 7, nullable = false)
    private String cnaeFiscal = "";

    @Column(name = "CodigoMunicipioIbge", length = 7, nullable = false)
    private String codigoMunicipioIbge = "3304557";

    @Column(name = "CodigoUfIbge", nullable = false)
    private byte codigoUfIbge = 33;

    @Column(name = "AmbienteFiscal", nullable = false)
    private byte ambienteFiscal = 2;

    @Column(name = "CscId", length = 6)
    private String cscId;

    @Column(name = "CscCifrado", length = 500)
    private String cscCifrado;

    @Column(name = "CertificadoPfxCifrado", columnDefinition = "nvarchar(max)")
    private String certificadoPfxCifrado;

    @Column(name = "CertificadoSenhaCifrada", length = 500)
    private String certificadoSenhaCifrada;

    @Column(name = "CertificadoThumbprint", length = 80)
    private String certificadoThumbprint;

    @Column(name = "CertificadoValidoAte")
    private OffsetDateTime certificadoValidoAte;

    @Column(name = "RespTecCnpj", length = 14)
    private String respTecCnpj;

    @Column(name = "RespTecContato", length = 120)
    private String respTecContato;

    @Column(name = "RespTecEmail", length = 180)
    private String respTecEmail;

    @Column(name = "RespTecFone", length = 20)
    private String respTecFone;

    @Column(name = "SerieNfce", nullable = false)
    private int serieNfce = 2;

    @Column(name = "ReviewedAt")
    private OffsetDateTime reviewedAt;

    @Column(name = "ReviewedBy", length = 180)
    private String reviewedBy;

    @Column(name = "RequireApprovalForNewCompanies", nullable = false)
    private boolean requireApprovalForNewCompanies = true;

    @Column(name = "SerieNfe", nullable = false)
    private int serieNfe = 2;

    @Column(name = "Status", length = 20, nullable = false)
    private String status = "pendente";

    @Column(name = "CreatedAt", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "RejectionReason", length = 500)
    private String rejectionReason;

    public String getStateRegistration() { return stateRegistration; }
    public void setStateRegistration(String v) { this.stateRegistration = v; }
    public String getWebsite() { return website; }
    public void setWebsite(String v) { this.website = v; }
    public String getSacPhone() { return sacPhone; }
    public void setSacPhone(String v) { this.sacPhone = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getMobile() { return mobile; }
    public void setMobile(String v) { this.mobile = v; }
    public String getCep() { return cep; }
    public void setCep(String v) { this.cep = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getNumber() { return number; }
    public void setNumber(String v) { this.number = v; }
    public String getNeighborhood() { return neighborhood; }
    public void setNeighborhood(String v) { this.neighborhood = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getUf() { return uf; }
    public void setUf(String v) { this.uf = v; }
    public String getComplement() { return complement; }
    public void setComplement(String v) { this.complement = v; }
    public byte getCrt() { return crt; }
    public void setCrt(byte v) { this.crt = v; }
    public String getCnaeFiscal() { return cnaeFiscal; }
    public void setCnaeFiscal(String v) { this.cnaeFiscal = v; }
    public String getCodigoMunicipioIbge() { return codigoMunicipioIbge; }
    public void setCodigoMunicipioIbge(String v) { this.codigoMunicipioIbge = v; }
    public byte getCodigoUfIbge() { return codigoUfIbge; }
    public void setCodigoUfIbge(byte v) { this.codigoUfIbge = v; }
    public byte getAmbienteFiscal() { return ambienteFiscal; }
    public void setAmbienteFiscal(byte v) { this.ambienteFiscal = v; }
    public String getCscId() { return cscId; }
    public void setCscId(String v) { this.cscId = v; }
    public String getCscCifrado() { return cscCifrado; }
    public void setCscCifrado(String v) { this.cscCifrado = v; }
    public String getCertificadoPfxCifrado() { return certificadoPfxCifrado; }
    public void setCertificadoPfxCifrado(String v) { this.certificadoPfxCifrado = v; }
    public String getCertificadoSenhaCifrada() { return certificadoSenhaCifrada; }
    public void setCertificadoSenhaCifrada(String v) { this.certificadoSenhaCifrada = v; }
    public String getCertificadoThumbprint() { return certificadoThumbprint; }
    public void setCertificadoThumbprint(String v) { this.certificadoThumbprint = v; }
    public OffsetDateTime getCertificadoValidoAte() { return certificadoValidoAte; }
    public void setCertificadoValidoAte(OffsetDateTime v) { this.certificadoValidoAte = v; }
    public String getRespTecCnpj() { return respTecCnpj; }
    public void setRespTecCnpj(String v) { this.respTecCnpj = v; }
    public String getRespTecContato() { return respTecContato; }
    public void setRespTecContato(String v) { this.respTecContato = v; }
    public String getRespTecEmail() { return respTecEmail; }
    public void setRespTecEmail(String v) { this.respTecEmail = v; }
    public String getRespTecFone() { return respTecFone; }
    public void setRespTecFone(String v) { this.respTecFone = v; }
    public int getSerieNfce() { return serieNfce; }
    public void setSerieNfce(int v) { this.serieNfce = v; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(OffsetDateTime v) { this.reviewedAt = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public boolean isRequireApprovalForNewCompanies() { return requireApprovalForNewCompanies; }
    public void setRequireApprovalForNewCompanies(boolean v) { this.requireApprovalForNewCompanies = v; }
    public int getSerieNfe() { return serieNfe; }
    public void setSerieNfe(int v) { this.serieNfe = v; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFantasyName() { return fantasyName; }
    public void setFantasyName(String v) { this.fantasyName = v; }
    public String getCorporateName() { return corporateName; }
    public void setCorporateName(String v) { this.corporateName = v; }
    public String getCnpj() { return cnpj; }
    public void setCnpj(String v) { this.cnpj = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public boolean isEmailSmtpEnabled() { return emailSmtpEnabled; }
    public void setEmailSmtpEnabled(boolean v) { this.emailSmtpEnabled = v; }
    public String getEmailSmtpHost() { return emailSmtpHost; }
    public void setEmailSmtpHost(String v) { this.emailSmtpHost = v; }
    public int getEmailSmtpPort() { return emailSmtpPort; }
    public void setEmailSmtpPort(int v) { this.emailSmtpPort = v; }
    public boolean isEmailSmtpEnableSsl() { return emailSmtpEnableSsl; }
    public void setEmailSmtpEnableSsl(boolean v) { this.emailSmtpEnableSsl = v; }
    public String getEmailSmtpUser() { return emailSmtpUser; }
    public void setEmailSmtpUser(String v) { this.emailSmtpUser = v; }
    public String getEmailSmtpPassword() { return emailSmtpPassword; }
    public void setEmailSmtpPassword(String v) { this.emailSmtpPassword = v; }
    public String getEmailSmtpFromEmail() { return emailSmtpFromEmail; }
    public void setEmailSmtpFromEmail(String v) { this.emailSmtpFromEmail = v; }
    public String getEmailSmtpFromName() { return emailSmtpFromName; }
    public void setEmailSmtpFromName(String v) { this.emailSmtpFromName = v; }
    public String getEmailSmtpReplyTo() { return emailSmtpReplyTo; }
    public void setEmailSmtpReplyTo(String v) { this.emailSmtpReplyTo = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
}
