package br.com.quack.pdv.cadastro.cliente.app;
import br.com.quack.pdv.auditoria.app.AuditoriaService;
import br.com.quack.pdv.cadastro.cliente.web.*;
import br.com.quack.pdv.security.UsuarioAutenticado;
import br.com.quack.pdv.shared.Dinheiro;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
@Service
public class ClienteService {
    private static final Logger LOG=LoggerFactory.getLogger(ClienteService.class);
    private final ClienteTransacao transacao;private final AuditoriaService auditoria;
    public ClienteService(ClienteTransacao transacao,AuditoriaService auditoria) {this.transacao=transacao;this.auditoria=auditoria;}
    public List<ClienteDto> listar(String companyId) {return transacao.listar(companyId);}
    public ClienteDto criar(UsuarioAutenticado u,ClienteRequest r) {var a=transacao.criar(u.companyId(),r);var c=a.cliente();auditar(u,"ClienteCriado","Cadastrou cliente "+c.customerName+(c.limiteCredito.signum()>0?" com limite de crédito "+numero(c.limiteCredito)+".":"."),c.id);return c;}
    public ClienteDto atualizar(UsuarioAutenticado u,String id,ClienteRequest r) {var a=transacao.atualizar(u.companyId(),id,r,podeFiado(u));var c=a.cliente();if(a.limiteAnterior().compareTo(r.limiteCredito)!=0)auditar(u,"FiadoLimiteAlterado","Alterou limite de crédito de "+a.nomeAnterior()+": "+numero(a.limiteAnterior())+" → "+numero(r.limiteCredito)+".",id);auditar(u,"ClienteAtualizado","Atualizou cadastro do cliente "+c.customerName+".",id);return c;}
    public void excluir(UsuarioAutenticado u,String id) {var a=transacao.excluir(u.companyId(),id,podeFiado(u));auditar(u,"ClienteExcluido","Excluiu cliente "+a.nomeAnterior()+(a.saldoAnterior().signum()>0?" (saldo devedor: "+numero(a.saldoAnterior())+").":"."),id);}
    public ClienteTransacao.Snapshot snapshot(String companyId,List<String> ids) {return transacao.snapshot(companyId,ids);}
    private static boolean podeFiado(UsuarioAutenticado u) {return !"gerente".equalsIgnoreCase(u.role());}
    private static String numero(java.math.BigDecimal valor) {return Dinheiro.formatar(valor).substring(3);}
    private void auditar(UsuarioAutenticado u,String tipo,String descricao,String id) {try{auditoria.registrar(u.companyId(),u.id(),u.name(),tipo,descricao,"Cliente",id,null);}catch(RuntimeException e){LOG.warn("Falha ao registrar auditoria do cliente {}.",id,e);}}
}
