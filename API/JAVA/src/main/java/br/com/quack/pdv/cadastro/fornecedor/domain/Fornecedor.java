package br.com.quack.pdv.cadastro.fornecedor.domain;
import jakarta.persistence.*;
@Entity @Table(name="Fornecedores")
public class Fornecedor {
    @Id @Column(name="Id",length=40,nullable=false) public String id = "";
    @Column(name="CompanyId",length=40,nullable=false) public String companyId = "";
    @Column(name="CompanyName",length=180,nullable=false) public String companyName = "";
    @Column(name="FantasyName",length=180,nullable=false) public String fantasyName = "";
    @Column(name="Cnpj",length=30,nullable=false) public String cnpj = "";
    @Column(name="Cep",length=20,nullable=false) public String cep = "";
    @Column(name="City",length=120,nullable=false) public String city = "";
    @Column(name="State",length=2,nullable=false) public String state = "";
    @Column(name="Address",length=180,nullable=false) public String address = "";
    @Column(name="Neighborhood",length=120,nullable=false) public String neighborhood = "";
    @Column(name="StreetComplement",length=180,nullable=false) public String streetComplement = "";
    @Column(name="Number",length=30,nullable=false) public String number = "";
    @Column(name="ReferencePoint",length=180,nullable=false) public String referencePoint = "";
    @Column(name="Telephone",length=30,nullable=false) public String telephone = "";
    @Column(name="Cellphone",length=30,nullable=false) public String cellphone = "";
    @Column(name="Email",length=180,nullable=false) public String email = "";
}
