package br.com.quack.pdv.promocao.promocao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "PromocaoProdutos")
public class PromocaoProduto {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "PromocaoId", length = 40, nullable = false)
    public String promocaoId = "";

    @Column(name = "ProdutoId", length = 40, nullable = false)
    public String produtoId = "";
}

