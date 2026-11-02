package br.com.quack.pdv.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Tabela PasswordResetTokens (so o hash do token fica no banco). */
@Entity
@Table(name = "PasswordResetTokens")
public class PasswordResetToken {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    private String id;

    @Column(name = "UserId", length = 40, nullable = false)
    private String userId;

    @Column(name = "Email", length = 180, nullable = false)
    private String email;

    @Column(name = "Cnpj", length = 30)
    private String cnpj;

    @Column(name = "TokenHash", length = 128, nullable = false)
    private String tokenHash;

    @Column(name = "CreatedAt", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "RequestedAt", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "ExpiresAt", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "ConsumedAt")
    private OffsetDateTime consumedAt;

    @Column(name = "RequestedIp", length = 64)
    private String requestedIp;

    @Column(name = "RequestedUserAgent", length = 500)
    private String requestedUserAgent;

    @Column(name = "RequestedDevice", length = 120)
    private String requestedDevice;

    @Column(name = "ResetIp", length = 64)
    private String resetIp;

    @Column(name = "ResetUserAgent", length = 500)
    private String resetUserAgent;

    @Column(name = "ResetDevice", length = 120)
    private String resetDevice;

    @Column(name = "UpdatedAt", nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getCnpj() { return cnpj; }
    public void setCnpj(String v) { this.cnpj = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(OffsetDateTime v) { this.requestedAt = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(OffsetDateTime v) { this.consumedAt = v; }
    public String getRequestedIp() { return requestedIp; }
    public void setRequestedIp(String v) { this.requestedIp = v; }
    public String getRequestedUserAgent() { return requestedUserAgent; }
    public void setRequestedUserAgent(String v) { this.requestedUserAgent = v; }
    public String getRequestedDevice() { return requestedDevice; }
    public void setRequestedDevice(String v) { this.requestedDevice = v; }
    public String getResetIp() { return resetIp; }
    public void setResetIp(String v) { this.resetIp = v; }
    public String getResetUserAgent() { return resetUserAgent; }
    public void setResetUserAgent(String v) { this.resetUserAgent = v; }
    public String getResetDevice() { return resetDevice; }
    public void setResetDevice(String v) { this.resetDevice = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
