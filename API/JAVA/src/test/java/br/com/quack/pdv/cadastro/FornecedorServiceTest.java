package br.com.quack.pdv.cadastro;
import br.com.quack.pdv.cadastro.fornecedor.app.FornecedorService;
import br.com.quack.pdv.cadastro.fornecedor.domain.Fornecedor;
import br.com.quack.pdv.cadastro.fornecedor.repo.FornecedorRepository;
import br.com.quack.pdv.cadastro.fornecedor.web.FornecedorRequest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FornecedorServiceTest {
    private final FornecedorRepository repo=mock(FornecedorRepository.class);
    private final FornecedorService service=new FornecedorService(repo,Clock.fixed(Instant.ofEpochMilli(123),ZoneOffset.UTC));
    @BeforeEach void setup() { when(repo.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); }
    private FornecedorRequest request() {var r=new FornecedorRequest();r.fantasyName="  Distribuidora Sol  ";return r;}
    @Test void fantasiaCompletaRazao() { var d=service.criar("loja",request()); assertThat(d.companyName).isEqualTo("Distribuidora Sol");assertThat(d.fantasyName).isEqualTo("Distribuidora Sol");assertThat(d.id).isEqualTo("fr-123"); }
    @Test void razaoCompletaFantasia() {var r=new FornecedorRequest();r.companyName="Razao";assertThat(service.criar("loja",r).fantasyName).isEqualTo("Razao");}
    @Test void exigeAlgumNome() { assertThatThrownBy(()->service.criar("loja",new FornecedorRequest())).hasMessage("Informe a Raz\u00e3o Social ou Nome Fantasia do fornecedor.");verify(repo,never()).saveAndFlush(any()); }
    @Test void cnpjUnicoNaCriacao() {var e=new Fornecedor();e.id="fr-outro";e.cnpj=" abc ";when(repo.findByCompanyIdOrderByFantasyName("loja")).thenReturn(List.of(e));var r=request();r.cnpj="ABC";assertThatThrownBy(()->service.criar("loja",r)).hasMessage("J\u00e1 existe fornecedor com este CNPJ.");}
    @Test void cnpjUnicoNaEdicao() {var e=new Fornecedor();e.id="fr-outro";e.cnpj="123";var alvo=new Fornecedor();alvo.id="fr-alvo";when(repo.findByCompanyIdAndId("loja","fr-alvo")).thenReturn(Optional.of(alvo));when(repo.findByCompanyIdOrderByFantasyName("loja")).thenReturn(List.of(e));var r=request();r.cnpj="123";assertThatThrownBy(()->service.atualizar("loja","fr-alvo",r)).hasMessage("J\u00e1 existe fornecedor com este CNPJ.");}
    @Test void permiteProprioCnpj() {var e=new Fornecedor();e.id="fr-alvo";e.cnpj="123";when(repo.findByCompanyIdAndId("loja","fr-alvo")).thenReturn(Optional.of(e));when(repo.findByCompanyIdOrderByFantasyName("loja")).thenReturn(List.of(e));var r=request();r.cnpj="123";assertThat(service.atualizar("loja","fr-alvo",r).cnpj).isEqualTo("123");}
    @Test void outraEmpresaNaoEncontrada() {assertThatThrownBy(()->service.atualizar("outra","fr",request())).hasMessage("Fornecedor n\u00e3o encontrado.");verify(repo,never()).saveAndFlush(any());}
    @Test void bloqueiaFornecedorEmUso() {var e=new Fornecedor();e.fantasyName="Sol";e.companyName="Sol SA";when(repo.findByCompanyIdAndId("loja","fr")).thenReturn(Optional.of(e));when(repo.produtosVinculados("loja","Sol","Sol SA")).thenReturn(1);assertThatThrownBy(()->service.excluir("loja","fr")).hasMessage("Fornecedor possui produtos vinculados e n\u00e3o pode ser exclu\u00eddo.");verify(repo,never()).delete(any());}
    @Test void excluiLivre() {var e=new Fornecedor();when(repo.findByCompanyIdAndId("loja","fr")).thenReturn(Optional.of(e));service.excluir("loja","fr");verify(repo).delete(e);}
}
