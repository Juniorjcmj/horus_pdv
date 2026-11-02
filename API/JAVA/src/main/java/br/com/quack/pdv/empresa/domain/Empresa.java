package br.com.quack.pdv.empresa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * Tabela Empresas, so com as colunas usadas por esta fase (situacao, aprovacao, motivo, CNPJ, contato e SMTP).
 * As demais colunas ficam para a fase do modulo Empresa.
 */
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

    @Column(name = "Status", length = 20, nullable = false)
    private String status = "pendente";

    @Column(name = "CreatedAt", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "RejectionReason", length = 500)
    private String rejectionReason;

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
