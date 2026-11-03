package br.com.quack.pdv.produto.entrada.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "MapeamentoProdutoFornecedor")
public class MapeamentoProdutoFornecedor {

    @Id
    @Column(name = "Id", length = 100, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 100, nullable = false)
    public String companyId = "";

    @Column(name = "FornecedorCnpj", length = 20, nullable = false)
    public String fornecedorCnpj = "";

    @Column(name = "CodigoProdutoFornecedor", length = 100, nullable = false)
    public String codigoProdutoFornecedor = "";

    @Column(name = "GtinFornecedor", length = 50)
    public String gtinFornecedor;

    @Column(name = "DescricaoFornecedor", length = 300)
    public String descricaoFornecedor;

    @Column(name = "ProdutoId", length = 100, nullable = false)
    public String produtoId = "";

    @Column(name = "CreatedAt", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "UpdatedAt", nullable = false)
    public LocalDateTime updatedAt;
}

