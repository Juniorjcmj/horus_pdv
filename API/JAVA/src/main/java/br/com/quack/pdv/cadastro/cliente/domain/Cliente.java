package br.com.quack.pdv.cadastro.cliente.domain;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="Clientes")
public class Cliente {
    @Id @Column(name="Id",length=40,nullable=false) public String id="";
    @Column(name="CompanyId",length=40,nullable=false) public String companyId="";
    @Column(name="CustomerName",length=180,nullable=false) public String customerName="";
    @Column(name="Document",length=30,nullable=false) public String document="";
    @Column(name="BirthDate",length=20,nullable=false) public String birthDate="";
    @Column(name="Age",length=10,nullable=false) public String age="";
    @Column(name="Cep",length=20,nullable=false) public String cep="";
    @Column(name="City",length=120,nullable=false) public String city="";
    @Column(name="State",length=2,nullable=false) public String state="";
    @Column(name="Address",length=180,nullable=false) public String address="";
    @Column(name="Neighborhood",length=120,nullable=false) public String neighborhood="";
    @Column(name="StreetComplement",length=180,nullable=false) public String streetComplement="";
    @Column(name="Number",length=30,nullable=false) public String number="";
    @Column(name="ReferencePoint",length=180,nullable=false) public String referencePoint="";
    @Column(name="Telephone",length=30,nullable=false) public String telephone="";
    @Column(name="Cellphone",length=30,nullable=false) public String cellphone="";
    @Column(name="Email",length=180,nullable=false) public String email="";
    @Column(name="InscricaoEstadual",length=20,nullable=true) public String inscricaoEstadual=null;
    @Column(name="CodigoMunicipioIbge",length=7,nullable=true) public String codigoMunicipioIbge=null;
    @Column(name="IndIeDest",nullable=false) public byte indIeDest=9;
    @Column(name="LimiteCredito",precision=18,scale=2,nullable=false) public BigDecimal limiteCredito=BigDecimal.ZERO;
    @Column(name="SaldoDevedor",precision=18,scale=2,nullable=false) public BigDecimal saldoDevedor=BigDecimal.ZERO;
}
