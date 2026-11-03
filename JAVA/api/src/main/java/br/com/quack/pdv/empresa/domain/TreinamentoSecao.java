package br.com.quack.pdv.empresa.domain;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
@Entity @Table(name="TreinamentoSecoes")
public class TreinamentoSecao {
    @Id @Column(name="Id",nullable=false,length=40) public String id;
    @Column(name="Nome",nullable=false,length=120) public String nome;
    @Column(name="Descricao",nullable=true,length=400) public String descricao;
    @Column(name="Ordem",nullable=false) public int ordem;
    @Column(name="CriadoEm",nullable=false) public OffsetDateTime criadoEm;
}
