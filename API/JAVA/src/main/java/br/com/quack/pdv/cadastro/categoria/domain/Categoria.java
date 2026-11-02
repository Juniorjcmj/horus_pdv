package br.com.quack.pdv.cadastro.categoria.domain;
import jakarta.persistence.*;
@Entity @Table(name="Categorias")
public class Categoria {
    @Id @Column(name="Id",length=40,nullable=false) public String id;
    @Column(name="CompanyId",length=40,nullable=false) public String companyId;
    @Column(name="Nome",length=80,nullable=false) public String nome;
    @Column(name="CategoriaPaiId",length=40) public String categoriaPaiId;
    @Column(name="Ordem",nullable=false) public int ordem;
    @Column(name="Ativa",nullable=false) public boolean ativa=true;
}
