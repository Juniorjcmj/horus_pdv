package br.com.quack.pdv.cadastro;
import br.com.quack.pdv.cadastro.cliente.app.ClienteTransacao;
import br.com.quack.pdv.cadastro.cliente.repo.ClienteRepository;
import br.com.quack.pdv.cadastro.cliente.domain.Cliente;
import br.com.quack.pdv.cadastro.cliente.web.ClienteRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ClienteTransacaoTest {
    private final ClienteRepository repo=mock(ClienteRepository.class);
    private final ClienteTransacao service=new ClienteTransacao(repo,Clock.systemUTC(),new ObjectMapper());
    private ClienteRequest valido() {var r=new ClienteRequest();r.customerName="Nome Cliente";r.telephone="11999998888";r.limiteCredito=new BigDecimal("100");return r;}
    private Cliente existente() {var e=new Cliente();e.id="cl";e.customerName="Anterior";e.limiteCredito=new BigDecimal("100");e.saldoDevedor=new BigDecimal("50");return e;}
    @BeforeEach void setup() {when(repo.saveAndFlush(any())).thenAnswer(i->i.getArgument(0));when(repo.delegadoCentavos(any(),any())).thenReturn(BigDecimal.ZERO);}
    static Arguments caso(Consumer<ClienteRequest> mudar,String msg) {return Arguments.of(mudar,msg);}
    static Stream<Arguments> erros() {return Stream.of(caso(r->r.customerName="ab","Nome do cliente deve ter no minimo 3 caracteres."),caso(r->r.document="123","Documento do cliente invalido."),caso(r->r.telephone="","Informe um telefone ou celular com DDD."),caso(r->r.telephone="123","Informe um telefone ou celular válido com DDD (10 ou 11 dígitos)."),caso(r->r.email="x","E-mail do cliente invalido."));}
    @ParameterizedTest @MethodSource("erros") void validaAntesDeGravar(Consumer<ClienteRequest> mudar,String msg) {var r=valido();mudar.accept(r);assertThatThrownBy(()->service.criar("loja",r)).hasMessage(msg);verify(repo,never()).saveAndFlush(any());}
    @Test void documentoComparadoSoDigitos() {var e=existente();e.document="123.456.789-01";when(repo.findByCompanyIdOrderByCustomerName("loja")).thenReturn(List.of(e));var r=valido();r.document="12345678901";assertThatThrownBy(()->service.criar("loja",r)).hasMessage("Já existe cliente com este documento.");}
    @Test void gerenteNaoMudaLimite() {when(repo.findByCompanyIdAndId("loja","cl")).thenReturn(Optional.of(existente()));var r=valido();r.limiteCredito=new BigDecimal("200");assertThatThrownBy(()->service.atualizar("loja","cl",r,false)).hasMessage("Seu perfil só consulta fiado/crediário: o limite de crédito do cliente não pode ser alterado.");}
    @Test void compromissosIncluemDelegadoMesmoSemAlterarLimite() {var e=existente();when(repo.findByCompanyIdAndId("loja","cl")).thenReturn(Optional.of(e));when(repo.saldoBloqueado("loja","cl")).thenReturn(Optional.of(new BigDecimal("50")));when(repo.delegadoCentavos("loja","cl")).thenReturn(new BigDecimal("6000"));assertThatThrownBy(()->service.atualizar("loja","cl",valido(),false)).hasMessage("Recolha e reconcilie os orçamentos antes de reduzir o limite abaixo dos compromissos.");verify(repo,never()).saveAndFlush(any());}
    @Test void gerenteEditaSemMudarLimiteEPreservaSaldo() {var e=existente();when(repo.findByCompanyIdAndId("loja","cl")).thenReturn(Optional.of(e));when(repo.saldoBloqueado("loja","cl")).thenReturn(Optional.of(new BigDecimal("50")));var a=service.atualizar("loja","cl",valido(),false);assertThat(a.cliente().saldoDevedor).isZero();assertThat(e.saldoDevedor).isEqualByComparingTo("50");}
    @Test void gerenteNaoExcluiDevedor() {when(repo.findByCompanyIdAndId("loja","cl")).thenReturn(Optional.of(existente()));assertThatThrownBy(()->service.excluir("loja","cl",false)).hasMessage("Seu perfil só consulta fiado/crediário: cliente com saldo devedor não pode ser excluído.");}
    @Test void adminExcluiDevedor() {var e=existente();when(repo.findByCompanyIdAndId("loja","cl")).thenReturn(Optional.of(e));service.excluir("loja","cl",true);verify(repo).delete(e);}
    @Test void outraEmpresaNaoEncontrada() {assertThatThrownBy(()->service.atualizar("outra","cl",valido(),true)).hasMessage("Cliente não encontrado.");}
    @Test void snapshotLimite5000() {assertThatThrownBy(()->service.snapshot("loja",Collections.nCopies(5001,"id"))).hasMessage("Muitas identidades para conferir crédito.");verify(repo,never()).snapshotBloqueado(any());}
    @Test void snapshotSerializaIdentidadesEUsaCompany() {when(repo.snapshotBloqueado("loja")).thenReturn(List.of(existente()));when(repo.vendasAceitas("loja","[\"id1\",\"id2\"]")).thenReturn(List.of("id1"));var s=service.snapshot("loja",List.of("id1","id2"));assertThat(s.customers()).hasSize(1);assertThat(s.acceptedClientSaleIds()).containsExactly("id1");}
}
