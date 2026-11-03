package br.com.quack.pdv.cadastro.cliente.app;
import br.com.quack.pdv.cadastro.cliente.domain.Cliente;
import br.com.quack.pdv.cadastro.cliente.repo.ClienteRepository;
import br.com.quack.pdv.cadastro.cliente.web.*;
import br.com.quack.pdv.shared.NegocioException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
/** Transaction boundary ends before the facade records audit events. */
@Service
public class ClienteTransacao {
    public record Alteracao(ClienteDto cliente,String nomeAnterior,BigDecimal limiteAnterior,BigDecimal saldoAnterior) {}
    public record Snapshot(List<ClienteDto> customers,List<String> acceptedClientSaleIds) {}
    private final ClienteRepository clientes;private final Clock clock;private final ObjectMapper json;
    public ClienteTransacao(ClienteRepository clientes,Clock clock,ObjectMapper json) {this.clientes=clientes;this.clock=clock;this.json=json;}
    public List<ClienteDto> listar(String companyId) {return clientes.findByCompanyIdOrderByCustomerName(companyId).stream().map(ClienteTransacao::dto).toList();}
    @Transactional public Alteracao criar(String companyId,ClienteRequest r) {validar(r);duplicado(companyId,null,r.document);var e=new Cliente();e.id="cl-"+clock.millis();e.companyId=companyId;copiar(r,e);clientes.saveAndFlush(e);return new Alteracao(dto(e),null,BigDecimal.ZERO,BigDecimal.ZERO);}
    @Transactional public Alteracao atualizar(String companyId,String id,ClienteRequest r,boolean podeFiado) {
        validar(r);var e=obter(companyId,id);String anterior=e.customerName;BigDecimal limite=e.limiteCredito;
        if(!podeFiado&&r.limiteCredito.compareTo(limite)!=0)falhar("Seu perfil só consulta fiado/crediário: o limite de crédito do cliente não pode ser alterado.");
        duplicado(companyId,id,r.document);
        BigDecimal saldo=clientes.saldoBloqueado(companyId,id).orElse(e.saldoDevedor);
        BigDecimal delegado=clientes.delegadoCentavos(companyId,id).movePointLeft(2);
        if(r.limiteCredito.compareTo(saldo.add(delegado))<0)falhar("Recolha e reconcilie os orçamentos antes de reduzir o limite abaixo dos compromissos.");
        copiar(r,e);e.saldoDevedor=saldo;clientes.saveAndFlush(e);
        // SalvarAsync returns MapRequest (zero debt); the stored debt remains unchanged.
        var resposta=dto(e);resposta.saldoDevedor=BigDecimal.ZERO;return new Alteracao(resposta,anterior,limite,saldo);
    }
    @Transactional public Alteracao excluir(String companyId,String id,boolean podeFiado) {var e=obter(companyId,id);if(!podeFiado&&e.saldoDevedor.signum()>0)falhar("Seu perfil só consulta fiado/crediário: cliente com saldo devedor não pode ser excluído.");var resposta=dto(e);clientes.delete(e);clientes.flush();return new Alteracao(resposta,e.customerName,e.limiteCredito,e.saldoDevedor);}
    @Transactional public Snapshot snapshot(String companyId,List<String> ids) {if(ids.size()>5000)falhar("Muitas identidades para conferir crédito.");String lista;try{lista=json.writeValueAsString(ids);}catch(Exception e){throw new IllegalArgumentException(e);}var todos=clientes.snapshotBloqueado(companyId).stream().map(ClienteTransacao::dto).toList();return new Snapshot(todos,clientes.vendasAceitas(companyId,lista));}
    private Cliente obter(String companyId,String id) {return clientes.findByCompanyIdAndId(companyId,id).orElseThrow(()->new NegocioException(HttpStatus.NOT_FOUND,"Cliente não encontrado."));}
    private void duplicado(String companyId,String id,String doc) {String digits=digitos(doc);if(digits.isEmpty())return;if(clientes.findByCompanyIdOrderByCustomerName(companyId).stream().anyMatch(e->!e.id.equals(id)&&digitos(e.document).equals(digits)))falhar("Já existe cliente com este documento.");}
    private static void validar(ClienteRequest r) {if(r.customerName==null||r.customerName.trim().length()<3)falhar("Nome do cliente deve ter no minimo 3 caracteres.");int doc=digitos(r.document).length();if(!texto(r.document).isBlank()&&doc!=11&&doc!=14)falhar("Documento do cliente invalido.");if(texto(r.telephone).isBlank()&&texto(r.cellphone).isBlank())falhar("Informe um telefone ou celular com DDD.");for(String p:new String[]{r.telephone,r.cellphone}){int n=digitos(p).length();if(!texto(p).isBlank()&&n!=10&&n!=11)falhar("Informe um telefone ou celular válido com DDD (10 ou 11 dígitos).");}if(!texto(r.email).isBlank()&&!r.email.contains("@"))falhar("E-mail do cliente invalido.");}
    private static void copiar(ClienteRequest r,Cliente e) {
        e.customerName=texto(r.customerName).trim();
        e.document=texto(r.document).trim();
        e.birthDate=texto(r.birthDate);
        e.age=texto(r.age);
        e.cep=texto(r.cep);
        e.city=texto(r.city);
        e.state=texto(r.state);
        e.address=texto(r.address);
        e.neighborhood=texto(r.neighborhood);
        e.streetComplement=texto(r.streetComplement);
        e.number=texto(r.number);
        e.referencePoint=texto(r.referencePoint);
        e.telephone=texto(r.telephone).trim();
        e.cellphone=texto(r.cellphone).trim();
        e.email=texto(r.email);
        e.inscricaoEstadual=normalizar(r.inscricaoEstadual);
        e.codigoMunicipioIbge=normalizar(r.codigoMunicipioIbge);
        e.indIeDest=(byte)r.indIeDest;e.limiteCredito=r.limiteCredito;
    }
    private static ClienteDto dto(Cliente e) {var r=new ClienteDto();r.id=e.id;
        r.customerName=e.customerName;
        r.document=e.document;
        r.birthDate=e.birthDate;
        r.age=e.age;
        r.cep=e.cep;
        r.city=e.city;
        r.state=e.state;
        r.address=e.address;
        r.neighborhood=e.neighborhood;
        r.streetComplement=e.streetComplement;
        r.number=e.number;
        r.referencePoint=e.referencePoint;
        r.telephone=e.telephone;
        r.cellphone=e.cellphone;
        r.email=e.email;
        r.inscricaoEstadual=e.inscricaoEstadual;
        r.codigoMunicipioIbge=e.codigoMunicipioIbge;
        r.indIeDest=Byte.toUnsignedInt(e.indIeDest);r.limiteCredito=e.limiteCredito;r.saldoDevedor=e.saldoDevedor;return r;
    }
    private static String digitos(String v) {return texto(v).replaceAll("[^\\p{Nd}]","");}
    private static String texto(String v) {return v==null?"":v;}
    private static String normalizar(String v) {return v==null||v.isBlank()?null:v.trim();}
    private static void falhar(String m) {throw new NegocioException(HttpStatus.BAD_REQUEST,m);}
}
