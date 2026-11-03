package br.com.quack.pdv.empresa;
import br.com.quack.pdv.empresa.app.*;
import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.empresa.web.EmpresaRequest;
import br.com.quack.pdv.security.SecretProtector;
import java.time.*;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class EmpresaServiceTest {
    private final EmpresaRepository repo=mock(EmpresaRepository.class);
    private final SecretProtector secrets=new SecretProtector("chave-de-teste", "jwt-de-teste");
    private final EmpresaService service=new EmpresaService(repo,secrets,new LeitorCertificado(),Clock.systemUTC());
    private EmpresaRequest valido() { var r=new EmpresaRequest(); r.fantasyName="Mercado Teste"; r.cnpj="11222333000181"; return r; }
    @BeforeEach void preparar() { when(repo.findById(anyString())).thenReturn(Optional.empty()); when(repo.saveAndFlush(any())).thenAnswer(i->i.getArgument(0)); }
    static Stream<Arguments> erros() {
        return Stream.of(
            caso(r->r.fantasyName="a", "Nome fantasia e obrigatorio."),
            caso(r->r.cnpj="123", "CNPJ invalido."),
            caso(r->r.emailSmtpEnabled=true, "Informe o host SMTP."),
            caso(r->{smtp(r);r.emailSmtpPort=0;}, "Informe uma porta SMTP valida."),
            caso(r->{smtp(r);r.emailSmtpUser="x";}, "Informe o usuario SMTP."),
            caso(r->{smtp(r);r.emailSmtpFromEmail="x";}, "Informe o e-mail remetente."),
            caso(r->{smtp(r);r.emailSmtpPassword="";}, "Informe a senha de app SMTP."),
            caso(r->{smtp(r);r.emailSmtpReplyTo="x";}, "Informe um e-mail de resposta valido."),
            caso(r->r.crt=5, "Regime tributario (CRT) invalido."),
            caso(r->r.cscId="A", "CSC id deve ter s\u00f3 numeros, ate 6 digitos (e o \"Id do token\" do portal da SEFAZ-RJ)."),
            caso(r->r.certificadoPfxBase64="AA==", "Informe a senha do certificado digital."),
            caso(r->{r.certificadoPfxBase64="AA==";r.certificadoSenha="x";}, "Certificado digital ou senha inv\u00e1lidos.")
        );
    }
    static Arguments caso(Consumer<EmpresaRequest> alterar,String mensagem) { return Arguments.of(alterar,mensagem); }
    static void smtp(EmpresaRequest r) { r.emailSmtpEnabled=true; r.emailSmtpHost="smtp.x";r.emailSmtpUser="u@x";r.emailSmtpFromEmail="f@x";r.emailSmtpPassword="senha"; }
    @ParameterizedTest @MethodSource("erros") void validacoesNaoGravam(Consumer<EmpresaRequest> alterar,String mensagem) { var r=valido();alterar.accept(r);assertThatThrownBy(()->service.salvar("loja",r)).hasMessage(mensagem);verify(repo,never()).saveAndFlush(any()); }
    @Test void mascaraSegredosEConservaValoresAusentes() {
        var e=new Empresa();e.setId("loja");e.setEmailSmtpPassword(secrets.protect("smtp")); e.setCscCifrado(secrets.protect("csc"));e.setCscId("000001");e.setCertificadoPfxCifrado(secrets.protect("pfx"));e.setCertificadoSenhaCifrada(secrets.protect("senha"));e.setCertificadoThumbprint("ABC");e.setCertificadoValidoAte(OffsetDateTime.parse("2030-01-01T00:00:00Z"));
        when(repo.findById("loja")).thenReturn(Optional.of(e));
        var r=valido();r.serieNfce=0;var salvo=service.salvar("loja",r);
        assertThat(secrets.unprotect(e.getEmailSmtpPassword())).isEqualTo("smtp");assertThat(secrets.unprotect(e.getCscCifrado())).isEqualTo("csc");assertThat(secrets.unprotect(e.getCertificadoSenhaCifrada())).isEqualTo("senha");assertThat(salvo.emailSmtpPassword).isEmpty();assertThat(salvo.csc).isEmpty();assertThat(salvo.certificadoPfxBase64).isEmpty();assertThat(salvo.certificadoSenha).isEmpty(); assertThat(salvo.emailSmtpHasPassword).isTrue();assertThat(salvo.cscHasValue).isTrue();assertThat(salvo.certificadoHasValue).isTrue();assertThat(salvo.serieNfce).isEqualTo(2);assertThat(salvo.cscId).isEqualTo("000001");assertThat(salvo.certificadoThumbprint).isEqualTo("ABC");
    }
    @Test void fallbackNaoCopiaSegredosParaEmpresaNova() { var principal=new Empresa(); principal.setFantasyName("Principal");principal.setCscCifrado(secrets.protect("principal")); when(repo.findById("empresa-principal")).thenReturn(Optional.of(principal));assertThat(service.obter("loja").fantasyName).isEqualTo("Principal"); var r=service.salvar("loja",valido());assertThat(r.cscHasValue).isFalse();assertThat(principal.getFantasyName()).isEqualTo("Principal"); }
    @Test void cifraSegredosNovos() { var r=valido();r.csc="novo CSC";r.emailSmtpPassword="nova SMTP";service.salvar("loja",r);var captor=org.mockito.ArgumentCaptor.forClass(Empresa.class);verify(repo).saveAndFlush(captor.capture());assertThat(captor.getValue().getCscCifrado()).startsWith("enc:v1:");assertThat(secrets.unprotect(captor.getValue().getCscCifrado())).isEqualTo("novo CSC"); }
    @Test void base64ComLixoERecusado() { assertThatThrownBy(()->new LeitorCertificado().ler("!!AA==", "x")).hasMessage("Certificado digital ou senha inv\u00e1lidos."); }
}
