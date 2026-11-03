package br.com.quack.pdv.empresa.domain;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
@Entity @Table(name="TreinamentoVideos")
public class TreinamentoVideo {
    @Id @Column(name="Id",nullable=false,length=40) public String id;
    @Column(name="SecaoId",nullable=false,length=40) public String secaoId;
    @Column(name="Titulo",nullable=false,length=160) public String titulo;
    @Column(name="Descricao",nullable=true,length=600) public String descricao;
    @Column(name="Instrucoes",nullable=true,columnDefinition="nvarchar(max)") public String instrucoes;
    @Column(name="YoutubeId",nullable=false,length=20) public String youtubeId;
    @Column(name="Ordem",nullable=false) public int ordem;
    @Column(name="CriadoEm",nullable=false) public OffsetDateTime criadoEm;
}
