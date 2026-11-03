package br.com.quack.pdv.estoque.inventario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "InventarioContagens")
public class InventarioContagem {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "InventarioId", length = 40, nullable = false)
    public String inventarioId = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "ProdutoId", length = 40, nullable = false)
    public String produtoId = "";

    @Column(name = "Quantidade", precision = 15, scale = 4, nullable = false)
    public BigDecimal quantidade = BigDecimal.ZERO;

    @Column(name = "Local", length = 80)
    public String local;

    @Column(name = "UsuarioId", length = 40, nullable = false)
    public String usuarioId = "";

    @Column(name = "UsuarioNome", length = 180, nullable = false)
    public String usuarioNome = "";

    @Column(name = "Terminal", length = 80)
    public String terminal;

    @Column(name = "Ip", length = 64)
    public String ip;

    @Column(name = "SaldoSistema", precision = 15, scale = 4, nullable = false)
    public BigDecimal saldoSistema = BigDecimal.ZERO;

    @Column(name = "CriadoEm", nullable = false)
    public OffsetDateTime criadoEm;

    @Column(name = "AnuladaPorId", length = 40)
    public String anuladaPorId;

    @Column(name = "AnuladaPorNome", length = 180)
    public String anuladaPorNome;

    @Column(name = "AnuladaEm")
    public OffsetDateTime anuladaEm;

    @Column(name = "AnuladaMotivo", length = 300)
    public String anuladaMotivo;
}

