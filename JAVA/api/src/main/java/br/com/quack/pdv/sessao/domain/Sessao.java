package br.com.quack.pdv.sessao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/** Tabela Sessoes (uma linha por login ativo). */
@Entity
@Table(name = "Sessoes")
public class Sessao {

    @Id
    @Column(name = "Id", length = 80, nullable = false)
    private String id;

    @Column(name = "UserId", length = 40, nullable = false)
    private String userId;

    @Column(name = "Device", length = 120, nullable = false)
    private String device = "";

    @Column(name = "Location", length = 150, nullable = false)
    private String location = "";

    @Column(name = "Ip", length = 64, nullable = false)
    private String ip = "";

    @Column(name = "LastActive", length = 80, nullable = false)
    private String lastActive = "Agora mesmo";

    @Column(name = "Platform", length = 20, nullable = false)
    private String platform = "desktop";

    @Column(name = "CreatedAt", nullable = false)
    private OffsetDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getLastActive() { return lastActive; }
    public void setLastActive(String lastActive) { this.lastActive = lastActive; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
