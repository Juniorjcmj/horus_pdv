package br.com.quack.pdv.usuario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.DynamicUpdate;

/** Tabela Usuarios. As datas CreatedAt/LastLoginAt sao texto, como na API .NET. */
@Entity
@Table(name = "Usuarios")
@DynamicUpdate
public class Usuario {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    private String id;

    @Column(name = "CompanyId", length = 40, nullable = false)
    private String companyId = "empresa-principal";

    @Column(name = "Cpf", length = 30, nullable = false)
    private String cpf;

    @Column(name = "Name", length = 180, nullable = false)
    private String name;

    @Column(name = "Email", length = 180, nullable = false)
    private String email;

    @Column(name = "Phone", length = 30, nullable = false)
    private String phone = "";

    @Column(name = "Role", length = 60, nullable = false)
    private String role = "atendente";

    @Column(name = "Status", length = 20, nullable = false)
    private String status = "ativo";

    @Column(name = "CreatedAt", length = 30, nullable = false)
    private String createdAt;

    @Column(name = "LastLoginAt", length = 60, nullable = false)
    private String lastLoginAt = "-";

    @Column(name = "PasswordHash", length = 300, nullable = false)
    private String passwordHash;

    @Column(name = "MustChangePassword", nullable = false)
    private boolean mustChangePassword = true;

    @Column(name = "PerfisAdicionais", length = 200, nullable = false)
    private String perfisAdicionais = "";

    @Column(name = "FailedLoginAttempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "FirstFailedLoginAt")
    private OffsetDateTime firstFailedLoginAt;

    @Column(name = "LockoutEnd")
    private OffsetDateTime lockoutEnd;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCompanyId() { return companyId; }
    public void setCompanyId(String companyId) { this.companyId = companyId; }
    public String getCpf() { return cpf; }
    public void setCpf(String cpf) { this.cpf = cpf; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(String lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public String getPerfisAdicionais() { return perfisAdicionais; }
    public void setPerfisAdicionais(String perfisAdicionais) { this.perfisAdicionais = perfisAdicionais == null ? "" : perfisAdicionais; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public OffsetDateTime getFirstFailedLoginAt() { return firstFailedLoginAt; }
    public void setFirstFailedLoginAt(OffsetDateTime firstFailedLoginAt) { this.firstFailedLoginAt = firstFailedLoginAt; }
    public OffsetDateTime getLockoutEnd() { return lockoutEnd; }
    public void setLockoutEnd(OffsetDateTime lockoutEnd) { this.lockoutEnd = lockoutEnd; }
}
