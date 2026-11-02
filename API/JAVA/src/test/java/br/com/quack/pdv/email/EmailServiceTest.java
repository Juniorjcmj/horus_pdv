package br.com.quack.pdv.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.email.app.EmailService;
import br.com.quack.pdv.email.app.EmailTemplate;
import br.com.quack.pdv.empresa.domain.Empresa;
import br.com.quack.pdv.empresa.repo.EmpresaRepository;
import br.com.quack.pdv.security.SecretProtector;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 9.1: servidor SMTP falso (GreenMail) recebe o e-mail com o link; senha SMTP da empresa vem decifrada. */
class EmailServiceTest {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("robo", "segredo123"));

    static final OffsetDateTime EXPIRA = OffsetDateTime.of(2026, 11, 1, 15, 0, 0, 0, ZoneOffset.UTC); // 12:00 em Brasilia

    QuackProperties props;
    EmpresaRepository empresas = mock(EmpresaRepository.class);
    SecretProtector protetor;
    EmailService service;

    @BeforeEach
    void preparar() {
        props = new QuackProperties();
        QuackProperties.Email e = props.getEmail();
        e.setEnabled(true);
        e.setHost("localhost");
        e.setPort(ServerSetupTest.SMTP.getPort());
        e.setEnableSsl(false);
        e.setUser("robo");
        e.setPassword("segredo123");
        e.setFromEmail("naoresponder@quack.com");
        e.setFromName("Quack PDV");
        e.setFrontendBaseUrl("https://pdv.quacksistemas.com.br/");
        protetor = new SecretProtector("chave-de-cifra-de-teste-com-mais-de-32-caracteres", "jwt-secret-de-teste-com-mais-de-32-caracteres");
        service = new EmailService(props, empresas, protetor);
    }

    @Test
    void enviaORedefinicaoComLinkHtmlETexto() throws Exception {
        String url = service.montarUrlRedefinicao("abc-DEF_123");
        assertThat(url).isEqualTo("https://pdv.quacksistemas.com.br/?resetToken=abc-DEF_123");

        service.enviarRedefinicaoSenha("Ana@Exemplo.com", "Mercado <Bom> & Cia", url, EXPIRA);

        MimeMessage[] recebidas = smtp.getReceivedMessages();
        assertThat(recebidas).hasSize(1);
        MimeMessage m = recebidas[0];
        assertThat(m.getSubject()).isEqualTo("Recuperação de senha - Quack PDV");
        assertThat(m.getAllRecipients()[0].toString()).isEqualTo("ana@exemplo.com");
        assertThat(m.getFrom()[0].toString()).contains("naoresponder@quack.com").contains("Quack PDV");
        String corpo = GreenMailUtil.getWholeMessage(m);
        assertThat(corpo).contains("text/html").contains("text/plain");
        assertThat(corpo).contains("https://pdv.quacksistemas.com.br/?resetToken=3Dabc-DEF_123");
    }

    @Test
    void conteudoDosModelosTemLinkEscapeESuaHoraDeBrasilia() {
        String html = EmailTemplate.redefinicaoHtml("Mercado <Bom> & Cia", "https://x/?a=1&b=2", EXPIRA);
        assertThat(html).contains("Mercado &lt;Bom&gt; &amp; Cia").contains("href=\"https://x/?a=1&amp;b=2\"")
                .contains("01/11/2026 12:00").contains("Recuperação de senha");
        String texto = EmailTemplate.redefinicaoTexto("Mercado", "https://x/?a=1&b=2", EXPIRA);
        assertThat(texto).contains("Link: https://x/?a=1&b=2").contains("Expira em: 01/11/2026 12:00 (horário de Brasília)");
        assertThat(EmailTemplate.boasVindasTexto("", "https://x")).contains("Empresa: sua empresa");
        assertThat(EmailTemplate.redefinicaoTexto(" ", "u", EXPIRA)).contains("Empresa: Quack PDV");
    }

    @Test
    void boasVindas() throws Exception {
        service.enviarBoasVindas("nova@loja.com", "Loja Nova");
        assertThat(smtp.getReceivedMessages()).hasSize(1);
        assertThat(smtp.getReceivedMessages()[0].getSubject()).isEqualTo("Cadastro criado - Quack PDV");
        assertThat(GreenMailUtil.getWholeMessage(smtp.getReceivedMessages()[0])).contains("https://pdv.quacksistemas.com.br");
    }

    @Test
    void desligadoNaoEnviaNada() {
        props.getEmail().setEnabled(false);
        assertThat(service.habilitado()).isFalse();
        service.enviarRedefinicaoSenha("ana@x.com", "X", "http://u", EXPIRA);
        service.enviarBoasVindas("ana@x.com", "X");
        assertThat(smtp.getReceivedMessages()).isEmpty();
    }

    @Test
    void ligadoMasSemSenhaOuHostLancaErroDeConfiguracao() {
        props.getEmail().setPassword("");
        assertThatThrownBy(() -> service.enviarRedefinicaoSenha("ana@x.com", "X", "http://u", EXPIRA))
                .hasMessageContaining("Serviço de e-mail não configurado");
    }

    @Test
    void destinoInvalidoLancaErro() {
        assertThatThrownBy(() -> service.enviarRedefinicaoSenha("semarroba", "X", "http://u", EXPIRA))
                .hasMessageContaining("E-mail de destino inválido.");
    }

    @Test
    void servidorQueRecusaLancaErro() {
        props.getEmail().setPassword("senha-errada");
        assertThatThrownBy(() -> service.enviarRedefinicaoSenha("ana@x.com", "X", "http://u", EXPIRA))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void senhaSmtpDaEmpresaCifradaPeloDotNetEDecifradaParaEnviar() throws Exception {
        Empresa e = new Empresa();
        e.setId("emp-1");
        e.setFantasyName("Mercado do Zé");
        e.setEmailSmtpEnabled(true);
        e.setEmailSmtpHost("localhost");
        e.setEmailSmtpPort(ServerSetupTest.SMTP.getPort());
        e.setEmailSmtpEnableSsl(false);
        e.setEmailSmtpUser("robo");
        e.setEmailSmtpPassword(protetor.protect("segredo123"));
        e.setEmailSmtpFromEmail("loja@meu.com");
        e.setEmailSmtpFromName("");
        e.setEmailSmtpReplyTo("suporte@meu.com");
        when(empresas.findById("emp-1")).thenReturn(Optional.of(e));
        props.getEmail().setEnabled(false); // so a SMTP da empresa vale

        assertThat(service.habilitado("emp-1")).isTrue();
        service.enviarRedefinicaoSenha("ana@x.com", "Mercado do Zé", "http://u", EXPIRA, "emp-1");

        MimeMessage m = smtp.getReceivedMessages()[0];
        assertThat(((jakarta.mail.internet.InternetAddress) m.getFrom()[0]).getAddress()).isEqualTo("loja@meu.com");
        assertThat(((jakarta.mail.internet.InternetAddress) m.getFrom()[0]).getPersonal()).isEqualTo("Mercado do Zé");
        assertThat(m.getReplyTo()[0].toString()).isEqualTo("suporte@meu.com");
    }

    @Test
    void empresaSemSmtpHabilitadoCaiNaConfiguracaoGlobal() {
        Empresa e = new Empresa();
        e.setId("emp-2");
        when(empresas.findById("emp-2")).thenReturn(Optional.of(e));
        assertThat(service.habilitado("emp-2")).isTrue(); // a global esta ligada
        props.getEmail().setEnabled(false);
        assertThat(service.habilitado("emp-2")).isFalse();
    }

    @Test
    void urlBasePadraoEEscapeDoToken() {
        props.getEmail().setFrontendBaseUrl("");
        assertThat(service.montarUrlRedefinicao(" a b+c/d ")).isEqualTo("http://localhost:5173/?resetToken=a%20b%2Bc%2Fd");
    }
}
