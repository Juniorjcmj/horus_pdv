package br.com.quack.pdv.cadastro.mercado.domain;
import jakarta.persistence.*;
@Entity @Table(name="ModulosMercado")
public class ModuloMercado {
    @Id @Column(name="Id",length=80,nullable=false) public String id="";
    @Column(name="Title",length=180,nullable=false) public String title="";
}
