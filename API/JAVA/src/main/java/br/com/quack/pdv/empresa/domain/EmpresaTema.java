package br.com.quack.pdv.empresa.domain;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name="EmpresaTema")
public class EmpresaTema {
    @Id @Column(name="CompanyId", length=100, nullable=false) private String companyId;
    @Column(name="SystemAccent", length=32) private String systemAccent;
    @Column(name="PdvAccent", length=32) private String pdvAccent;
    @Column(name="UpdatedAt", nullable=false) private LocalDateTime updatedAt;
    @Column(name="UpdatedBy", length=100) private String updatedBy;
    public String getCompanyId() { return companyId; }
    public void setCompanyId(String v) { companyId = v; }
    public String getSystemAccent() { return systemAccent; }
    public void setSystemAccent(String v) { systemAccent = v; }
    public String getPdvAccent() { return pdvAccent; }
    public void setPdvAccent(String v) { pdvAccent = v; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime v) { updatedAt = v; }
    public void setUpdatedBy(String v) { updatedBy = v; }
}
