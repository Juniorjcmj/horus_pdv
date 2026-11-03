package br.com.quack.pdv.produto.lote.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "LoteConsumos")
public class LoteConsumo {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "RefId", length = 60, nullable = false)
    public String refId = "";

    @Column(name = "Origem", length = 20, nullable = false)
    public String origem = "";

    @Column(name = "ProdutoId", length = 40, nullable = false)
    public String produtoId = "";

    @Column(name = "LoteId", length = 40)
    public String loteId;

    @Column(name = "Quantidade", precision = 15, scale = 4, nullable = false)
    public BigDecimal quantidade = BigDecimal.ZERO;

    @Column(name = "Estornado", nullable = false)
    public boolean estornado;

    @Column(name = "CriadoEm", nullable = false)
    public OffsetDateTime criadoEm;
}

