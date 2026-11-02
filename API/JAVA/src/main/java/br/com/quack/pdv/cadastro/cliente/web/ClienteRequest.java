package br.com.quack.pdv.cadastro.cliente.web;
import java.math.BigDecimal;
public class ClienteRequest {
    public String customerName="";
    public String document="";
    public String birthDate="";
    public String age="";
    public String cep="";
    public String city="";
    public String state="";
    public String address="";
    public String neighborhood="";
    public String streetComplement="";
    public String number="";
    public String referencePoint="";
    public String telephone="";
    public String cellphone="";
    public String email="";
    public String inscricaoEstadual="";
    public String codigoMunicipioIbge="";
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=br.com.quack.pdv.shared.ByteDotnetDeserializer.class)
    public int indIeDest=9;
    @com.fasterxml.jackson.annotation.JsonSetter(nulls=com.fasterxml.jackson.annotation.Nulls.FAIL)
    public BigDecimal limiteCredito=BigDecimal.ZERO;
}
