package br.com.quack.pdv.promocao.promocao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "Promocoes")
public class Promocao {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "Nome", length = 180, nullable = false)
    public String nome = "";

    @Column(name = "Tipo", length = 30, nullable = false)
    public String tipo = "";

    @Column(name = "ValorDesconto", precision = 18, scale = 2)
    public BigDecimal valorDesconto;

    @Column(name = "PrecoFixo", precision = 18, scale = 2)
    public BigDecimal precoFixo;

    @Column(name = "QuantidadeLeva")
    public Integer quantidadeLeva;

    @Column(name = "QuantidadePaga")
    public Integer quantidadePaga;

    @Column(name = "QuantidadeMinima")
    public Integer quantidadeMinima;

    @Column(name = "InicioVigencia", nullable = false)
    public OffsetDateTime inicioVigencia;

    @Column(name = "FimVigencia", nullable = false)
    public OffsetDateTime fimVigencia;

    @Column(name = "Ativa", nullable = false)
    public boolean ativa = true;

    @Column(name = "CategoriaId", length = 40)
    public String categoriaId;

    @Column(name = "CriadoPor", length = 180, nullable = false)
    public String criadoPor = "";

    @Column(name = "CriadoEm", nullable = false)
    public OffsetDateTime criadoEm;
}

