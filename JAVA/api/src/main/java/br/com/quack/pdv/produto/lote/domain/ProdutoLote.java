package br.com.quack.pdv.produto.lote.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ProdutoLotes")
public class ProdutoLote {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "ProdutoId", length = 40, nullable = false)
    public String produtoId = "";

    @Column(name = "NumeroLote", length = 60)
    public String numeroLote;

    @Column(name = "DataValidade", nullable = false)
    public LocalDate dataValidade;

    @Column(name = "QtdInicial", precision = 15, scale = 4, nullable = false)
    public BigDecimal qtdInicial = BigDecimal.ZERO;

    @Column(name = "Origem", length = 20, nullable = false)
    public String origem = "";

    @Column(name = "ValidadePadrao", nullable = false)
    public boolean validadePadrao;

    @Column(name = "CriadoEm", nullable = false)
    public OffsetDateTime criadoEm;

    @Column(name = "CriadoPorNome", length = 120)
    public String criadoPorNome;

    @Column(name = "QtdAtual", precision = 15, scale = 4)
    public BigDecimal qtdAtual;
}

