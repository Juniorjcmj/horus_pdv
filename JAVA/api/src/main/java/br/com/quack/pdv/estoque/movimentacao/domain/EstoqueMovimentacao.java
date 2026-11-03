package br.com.quack.pdv.estoque.movimentacao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "EstoqueMovimentacoes")
public class EstoqueMovimentacao {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 100, nullable = false)
    public String companyId = "";

    @Column(name = "ProdutoId", length = 40, nullable = false)
    public String produtoId = "";

    @Column(name = "Tipo", length = 20, nullable = false)
    public String tipo = "";

    @Column(name = "Quantidade", precision = 18, scale = 3, nullable = false)
    public BigDecimal quantidade = BigDecimal.ZERO;

    @Column(name = "EstoqueAnterior", precision = 18, scale = 3, nullable = false)
    public BigDecimal estoqueAnterior = BigDecimal.ZERO;

    @Column(name = "EstoqueNovo", precision = 18, scale = 3, nullable = false)
    public BigDecimal estoqueNovo = BigDecimal.ZERO;

    @Column(name = "Motivo", length = 500, nullable = false)
    public String motivo = "";

    @Column(name = "UsuarioId", length = 36, nullable = false)
    public String usuarioId = "sistema";

    @Column(name = "UsuarioNome", length = 200, nullable = false)
    public String usuarioNome = "Sistema";

    @Column(name = "CriadoEm", nullable = false)
    public LocalDateTime criadoEm;

    @Column(name = "CustoUnitario", precision = 18, scale = 4)
    public BigDecimal custoUnitario;

    @Column(name = "OrigemTipo", length = 20)
    public String origemTipo;

    @Column(name = "OrigemId", length = 80)
    public String origemId;

    @Column(name = "DepositoId", length = 40, nullable = false)
    public String depositoId = "loja";
}

