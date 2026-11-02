package br.com.quack.pdv.cadastro.fornecedor.app;
import br.com.quack.pdv.cadastro.fornecedor.domain.Fornecedor;
import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.cadastro.fornecedor.web.*;
import br.com.quack.pdv.shared.NegocioException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
@Service
public class FornecedorService {
    private final FornecedorRepository fornecedores; private final Clock clock;
    public FornecedorService(FornecedorRepository fornecedores,Clock clock) { this.fornecedores=fornecedores;this.clock=clock; }
    public List<FornecedorDto> listar(String companyId) { return fornecedores.findByCompanyIdOrderByFantasyName(companyId).stream().map(this::dto).toList(); }
    @Transactional public FornecedorDto criar(String companyId,FornecedorRequest r) { normalizar(r);duplicado(companyId,null,r.cnpj);var e=new Fornecedor();e.id="fr-"+clock.millis();e.companyId=companyId;copiar(r,e);return dto(fornecedores.saveAndFlush(e)); }
    @Transactional public FornecedorDto atualizar(String companyId,String id,FornecedorRequest r) { normalizar(r);var e=obter(companyId,id);duplicado(companyId,id,r.cnpj);copiar(r,e);return dto(fornecedores.saveAndFlush(e)); }
    @Transactional public void excluir(String companyId,String id) { var e=obter(companyId,id);if(fornecedores.produtosVinculados(companyId,e.fantasyName,e.companyName)>0) falhar("Fornecedor possui produtos vinculados e não pode ser excluído.");fornecedores.delete(e); }
    private Fornecedor obter(String companyId,String id) { return fornecedores.findByCompanyIdAndId(companyId,id).orElseThrow(()->new NegocioException(HttpStatus.NOT_FOUND,"Fornecedor não encontrado.")); }
    private void duplicado(String companyId,String id,String cnpj) { if(texto(cnpj).isEmpty())return; if(fornecedores.findByCompanyIdOrderByFantasyName(companyId).stream().anyMatch(e->!e.id.equals(id)&&!texto(e.cnpj).isEmpty()&&texto(e.cnpj).equalsIgnoreCase(texto(cnpj)))) falhar("Já existe fornecedor com este CNPJ."); }
    private void normalizar(FornecedorRequest r) { if(texto(r.companyName).isEmpty())r.companyName=texto(r.fantasyName);else if(texto(r.fantasyName).isEmpty())r.fantasyName=texto(r.companyName);if(texto(r.companyName).isEmpty()&&texto(r.fantasyName).isEmpty())falhar("Informe a Razão Social ou Nome Fantasia do fornecedor."); }
    private void copiar(FornecedorRequest r,Fornecedor e) {
        e.companyName=texto(r.companyName);
        e.fantasyName=texto(r.fantasyName);
        e.cnpj=texto(r.cnpj);
        e.cep=texto(r.cep);
        e.city=texto(r.city);
        e.state=texto(r.state);
        e.address=texto(r.address);
        e.neighborhood=texto(r.neighborhood);
        e.streetComplement=texto(r.streetComplement);
        e.number=texto(r.number);
        e.referencePoint=texto(r.referencePoint);
        e.telephone=texto(r.telephone);
        e.cellphone=texto(r.cellphone);
        e.email=texto(r.email);
    }
    private FornecedorDto dto(Fornecedor e) { var r=new FornecedorDto();r.id=e.id;
        r.companyName=e.companyName;
        r.fantasyName=e.fantasyName;
        r.cnpj=e.cnpj;
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
        return r;
    }
    private static String texto(String v) { return v==null?"":v.trim(); }
    private static void falhar(String m) { throw new NegocioException(HttpStatus.BAD_REQUEST,m); }
}
