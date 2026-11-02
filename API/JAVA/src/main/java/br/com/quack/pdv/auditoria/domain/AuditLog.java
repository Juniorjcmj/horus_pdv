package br.com.quack.pdv.auditoria.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Tabela AuditLog (id IDENTITY gerado pelo banco). */
@Entity
@Table(name = "AuditLog")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "Id")
    private Long id;

    @Column(name = "CompanyId", length = 40, nullable = false)
    private String companyId;

    @Column(name = "OccurredAt", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "UserId", length = 40, nullable = false)
    private String userId = "";

    @Column(name = "UserName", length = 180, nullable = false)
    private String userName = "";

    @Column(name = "EventType", length = 60, nullable = false)
    private String eventType;

    @Column(name = "EntityType", length = 60)
    private String entityType;

    @Column(name = "EntityId", length = 80)
    private String entityId;

    @Column(name = "Description", length = 500, nullable = false)
    private String description = "";

    @Column(name = "Ip", length = 64)
    private String ip;

    public Long getId() { return id; }
    public void setId(Long v) { this.id = v; }
    public String getCompanyId() { return companyId; }
    public void setCompanyId(String v) { this.companyId = v; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(OffsetDateTime v) { this.occurredAt = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getUserName() { return userName; }
    public void setUserName(String v) { this.userName = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String v) { this.entityType = v; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String v) { this.entityId = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getIp() { return ip; }
    public void setIp(String v) { this.ip = v; }
}
