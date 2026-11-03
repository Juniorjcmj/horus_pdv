package br.com.quack.pdv.estoque.inventario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "InventarioItens")
@IdClass(InventarioItemId.class)
public class InventarioItem {

    @Id
    @Column(name = "InventarioId", length = 40, nullable = false)
    public String inventarioId = "";

    @Id
    @Column(name = "ProdutoId", length = 40, nullable = false)
    public String produtoId = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "Codigo", length = 80, nullable = false)
    public String codigo = "";

    @Column(name = "Nome", length = 180, nullable = false)
    public String nome = "";

    @Column(name = "Unidade", length = 6, nullable = false)
    public String unidade = "UN";

    @Column(name = "Custo", precision = 15, scale = 4, nullable = false)
    public BigDecimal custo = BigDecimal.ZERO;

    @Column(name = "SaldoNa1aContagem", precision = 15, scale = 4)
    public BigDecimal saldoNa1aContagem;

    @Column(name = "PrimeiraContagemEm")
    public OffsetDateTime primeiraContagemEm;

    @Column(name = "UltimaContagemEm")
    public OffsetDateTime ultimaContagemEm;

    @Column(name = "Recontar", nullable = false)
    public boolean recontar;

    @Column(name = "Contado", precision = 15, scale = 4)
    public BigDecimal contado;

    @Column(name = "SaldoAntes", precision = 15, scale = 4)
    public BigDecimal saldoAntes;

    @Column(name = "Delta", precision = 15, scale = 4)
    public BigDecimal delta;

    @Column(name = "SaldoDepois", precision = 15, scale = 4)
    public BigDecimal saldoDepois;

    @Column(name = "ValorDelta", precision = 15, scale = 2)
    public BigDecimal valorDelta;

    @Column(name = "PisoAplicado", precision = 15, scale = 4)
    public BigDecimal pisoAplicado;
}

