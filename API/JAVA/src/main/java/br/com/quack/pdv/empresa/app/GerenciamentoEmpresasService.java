package br.com.quack.pdv.empresa.app;
import br.com.quack.pdv.empresa.repo.GerenciamentoEmpresasRepository;
import br.com.quack.pdv.shared.NegocioException;
import java.util.*;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
@Service
public class GerenciamentoEmpresasService {
    private static final Logger LOG=LoggerFactory.getLogger(GerenciamentoEmpresasService.class);
    private record Acao(String invalida,String motivo,String ausente,String sucesso,String erro) {}
    private static final Map<String,Acao> ACOES=Map.ofEntries(Map.entry("aprovar",new Acao("Empresa inválida para aprovação.",null,"Empresa não encontrada ou já aprovada.","Empresa aprovada com sucesso! O acesso aos usuários foi liberado.","Erro interno ao aprovar empresa.")),Map.entry("rejeitar",new Acao("Empresa inválida para rejeição.","Informe o motivo da recusa/rejeição.","Empresa não encontrada.","Inscrição da empresa rejeitada. O acesso de login permanece bloqueado.","Erro interno ao rejeitar empresa.")),Map.entry("bloquear",new Acao("Empresa inválida para bloqueio.","Informe o motivo do bloqueio.","Empresa não encontrada.","Empresa suspensa/bloqueada com sucesso. Todas as sessões ativas foram revogadas.","Erro interno ao bloquear empresa.")),Map.entry("reativar",new Acao("Empresa inválida para reativação.",null,"Empresa não encontrada.","Empresa reativada com sucesso! O acesso foi restabelecido.","Erro interno ao reativar empresa.")));
    private final GerenciamentoEmpresasRepository empresas;
    public GerenciamentoEmpresasService(GerenciamentoEmpresasRepository empresas) {this.empresas=empresas;}
    public GerenciamentoEmpresasRepository.Lista listar(String search,String status,int page,int pageSize) {
        int pagina=Math.max(1,page),tamanho=Math.clamp(pageSize,1,100);
        String pesquisa=search==null||search.isBlank()?null:"%"+search.trim()+"%";
        String situacao=status==null||status.isBlank()||status.equalsIgnoreCase("todas")?null:status.trim().toLowerCase(Locale.ROOT);
        return executar("Erro interno ao buscar empresas.",()->empresas.listar(pesquisa,situacao,pagina,tamanho));
    }
    public GerenciamentoEmpresasRepository.Metricas metricas() {return executar("Erro interno ao obter métricas.",empresas::metricas);}
    public boolean configuracao() {return empresas.configuracao();}
    public String configurar(boolean required) {return executar("Erro interno ao atualizar configuração.",()->{empresas.configurar(required);return required?"Aprovação prévia ativada: novos cadastros ficarão pendentes até sua análise.":"Aprovação automática ativada: novos cadastros terão acesso imediato.";});}
    public String situacao(String acao,String id,String reason,String reviewer) {
        var a=ACOES.get(acao);validarId(id,a.invalida());
        if(a.motivo()!=null&&(reason==null||reason.isBlank()))falhar(HttpStatus.BAD_REQUEST,a.motivo());
        return executar(a.erro(),()->{if(!empresas.situacao(acao,id,reviewer,reason))falhar(HttpStatus.NOT_FOUND,a.ausente());return a.sucesso();});
    }
    public String credenciais(String id,String email,String senha) {validarId(id,"Empresa inválida para esta operação.");return executar("Erro interno ao alterar credenciais.",()->{var r=empresas.credenciais(id,email,senha);if(!r.success())falhar(HttpStatus.BAD_REQUEST,r.message());return r.message();});}
    public String excluir(String id) {validarId(id,"Empresa inválida para exclusão.");return executar("Erro interno ao excluir empresa.",()->{if(!empresas.excluir(id))falhar(HttpStatus.NOT_FOUND,"Empresa não encontrada.");return "Empresa e todos os seus dados foram excluídos permanentemente.";});}
    private static void validarId(String id,String m) {if(id==null||id.isBlank()||id.equalsIgnoreCase("empresa-principal"))falhar(HttpStatus.BAD_REQUEST,m);}
    private static void falhar(HttpStatus status,String m) {throw new NegocioException(status,m);}
    private <T>T executar(String erro,Supplier<T> trabalho) {try{return trabalho.get();}catch(NegocioException e){throw e;}catch(RuntimeException e){LOG.error(erro,e);throw new NegocioException(HttpStatus.INTERNAL_SERVER_ERROR,erro);}}
}
