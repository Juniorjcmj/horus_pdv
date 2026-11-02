package br.com.quack.pdv.cadastro.mercado.domain;
import jakarta.persistence.*;
@Entity @Table(name="ModuloMercadoRegistros")
public class ModuloMercadoRegistro {
    @Id @Column(name="Id",length=100,nullable=false) public String id="";
    @Column(name="CompanyId",length=40,nullable=false) public String companyId="";
    @Column(name="ModuleId",length=80,nullable=false) public String moduleId="";
    @Column(name="Title",length=180,nullable=false) public String title="";
    @Column(name="Description",length=500,nullable=false) public String description="";
    @Column(name="Status",length=80,nullable=false) public String status="";
    @Column(name="Amount",length=40,nullable=false) public String amount="";
    @Column(name="Meta",length=180,nullable=false) public String meta="";
}
