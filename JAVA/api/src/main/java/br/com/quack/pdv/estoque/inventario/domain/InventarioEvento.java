package br.com.quack.pdv.estoque.inventario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "InventarioEventos")
public class InventarioEvento {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "InventarioId", length = 40, nullable = false)
    public String inventarioId = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "Tipo", length = 30, nullable = false)
    public String tipo = "";

    @Column(name = "OcorridoEm", nullable = false)
    public OffsetDateTime ocorridoEm;

    @Column(name = "UsuarioId", length = 40, nullable = false)
    public String usuarioId = "";

    @Column(name = "UsuarioNome", length = 180, nullable = false)
    public String usuarioNome = "";

    @Column(name = "SupervisorId", length = 40)
    public String supervisorId;

    @Column(name = "SupervisorNome", length = 180)
    public String supervisorNome;

    @Column(name = "Ip", length = 64)
    public String ip;

    @Column(name = "Terminal", length = 80)
    public String terminal;

    @Column(name = "Descricao", length = 500)
    public String descricao;
}

