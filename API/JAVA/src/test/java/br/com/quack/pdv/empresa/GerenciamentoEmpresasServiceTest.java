package br.com.quack.pdv.empresa;
import br.com.quack.pdv.empresa.app.GerenciamentoEmpresasService;
import br.com.quack.pdv.empresa.repo.GerenciamentoEmpresasRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GerenciamentoEmpresasServiceTest {
    private final GerenciamentoEmpresasRepository repo=mock(GerenciamentoEmpresasRepository.class);
    private final GerenciamentoEmpresasService service=new GerenciamentoEmpresasService(repo);
    @Test void normalizaPaginaTamanhoBuscaStatus() {when(repo.listar("%Mercado%",null,1,100)).thenReturn(new GerenciamentoEmpresasRepository.Lista(List.of(),0,1,100));var l=service.listar(" Mercado ","todas",0,500);assertThat(l.page()).isEqualTo(1);assertThat(l.pageSize()).isEqualTo(100);verify(repo).listar("%Mercado%",null,1,100);}
    @Test void tamanhoMinimoEStatusNormalizado() {service.listar(" "," PENDENTE ",2,0);verify(repo).listar(null,"pendente",2,1);}
    @Test void protegeEmpresaPrincipal() {assertThatThrownBy(()->service.situacao("aprovar","empresa-principal",null,"Admin")).hasMessage("Empresa inválida para aprovação.");assertThatThrownBy(()->service.excluir("EMPRESA-PRINCIPAL")).hasMessage("Empresa inválida para exclusão.");verifyNoInteractions(repo);}
    @Test void rejeicaoExigeMotivo() {assertThatThrownBy(()->service.situacao("rejeitar","loja"," ","Admin")).hasMessage("Informe o motivo da recusa/rejeição.");verifyNoInteractions(repo);}
    @Test void aprovarAusente() {assertThatThrownBy(()->service.situacao("aprovar","ausente",null,"Admin")).hasMessage("Empresa não encontrada ou já aprovada.");}
    @Test void bloquearComMensagemExata() {when(repo.situacao("bloquear","loja","Admin","Motivo")).thenReturn(true);assertThat(service.situacao("bloquear","loja","Motivo","Admin")).isEqualTo("Empresa suspensa/bloqueada com sucesso. Todas as sessões ativas foram revogadas.");}
    @Test void exclusaoTraduzFalhaInterna() {when(repo.excluir("loja")).thenThrow(new RuntimeException("FK"));assertThatThrownBy(()->service.excluir("loja")).hasMessage("Erro interno ao excluir empresa.");}
    @Test void credenciaisMantemErroNegocio() {when(repo.credenciais("loja",null,null)).thenReturn(new GerenciamentoEmpresasRepository.Credenciais(false,"Informe o novo e-mail ou a nova senha."));assertThatThrownBy(()->service.credenciais("loja",null,null)).hasMessage("Informe o novo e-mail ou a nova senha.");}
    @Test void configurarAutomatico() {assertThat(service.configurar(false)).isEqualTo("Aprovação automática ativada: novos cadastros terão acesso imediato.");verify(repo).configurar(false);}
}
