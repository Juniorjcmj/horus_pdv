package br.com.quack.pdv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.quack.pdv.ApiIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Recuperacao de senha ponta a ponta: pedido, e-mail no SMTP falso, redefinicao, sessoes e token de uso unico. */
class RecuperacaoSenhaIT extends ApiIT {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withUser("robo", "segredo123"));

    @DynamicPropertySource
    static void emailLigado(DynamicPropertyRegistry r) {
        r.add("quack.email.enabled", () -> "true");
        r.add("quack.email.host", () -> "localhost");
        r.add("quack.email.port", () -> ServerSetupTest.SMTP.getPort());
        r.add("quack.email.enable-ssl", () -> "false");
        r.add("quack.email.user", () -> "robo");
        r.add("quack.email.password", () -> "segredo123");
        r.add("quack.email.from-email", () -> "naoresponder@quack.com");
        r.add("quack.email.frontend-base-url", () -> "https://front.exemplo.com");
    }

    static final Pattern LINK = Pattern.compile("resetToken=([A-Za-z0-9_-]+)");

    @BeforeEach
    void limpar() throws Exception {
        smtp.purgeEmailFromAllMailboxes();
        jdbc.update("UPDATE Usuarios SET Status = 'ativo', FailedLoginAttempts = 0, LockoutEnd = NULL, FirstFailedLoginAt = NULL, PasswordHash = "
                + "N'100000.Q0wmjZSGSpZjzetxaWmnOg==./e7RA0660AYv/UNXRsWH7jS/LW9C9uhWJccn0quZxXs=' WHERE Id = 'usr-ct-apr-adm'");
    }

    MvcResult postar(String rota, String json) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(rota)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    String tokenDoEmail() throws Exception {
        MimeMessage[] msgs = smtp.getReceivedMessages();
        assertThat(msgs).hasSize(1);
        String texto = textoSimples(msgs[0]);
        Matcher m = LINK.matcher(texto);
        assertThat(m.find()).as("link no e-mail: " + texto).isTrue();
        return m.group(1);
    }

    /** Parte text/plain ja decodificada (o quoted-printable quebra o token em varias linhas no corpo bruto). */
    static String textoSimples(jakarta.mail.Part parte) throws Exception {
        if (parte.isMimeType("text/plain")) return (String) parte.getContent();
        if (parte.isMimeType("multipart/*")) {
            jakarta.mail.Multipart mp = (jakarta.mail.Multipart) parte.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                String t = textoSimples(mp.getBodyPart(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    @Test
    void fluxoCompletoPedidoEmailRedefinicaoELogin() throws Exception {
        String antes = bearer("adm.aprovada@contrato.quack");

        MvcResult pedido = postar("/api/Auth/forgot-password", "{\"cnpj\":\"11.222.333/0001-81\",\"email\":\"Adm.Aprovada@contrato.quack\"}");
        assertThat(pedido.getResponse().getStatus()).isEqualTo(200);
        JsonNode dados = corpo(pedido).get("data");
        assertThat(dados.get("accepted").asBoolean()).isTrue();
        assertThat(dados.get("maskedEmail").asText()).isEqualTo("a***a@contrato.quack");
        assertThat(dados.has("resetToken")).isFalse();
        assertThat(dados.get("expiresAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T.*\\+00:00");

        String token = tokenDoEmail();
        // so o hash fica no banco
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PasswordResetTokens WHERE TokenHash LIKE ?", Integer.class, "%" + token + "%")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM PasswordResetTokens WHERE UserId = 'usr-ct-apr-adm' AND ConsumedAt IS NULL", Integer.class)).isEqualTo(1);

        MvcResult redefinicao = postar("/api/Auth/reset-password",
                "{\"token\":\"" + token + "\",\"nextPassword\":\"NovaSenha#99\",\"confirmPassword\":\"NovaSenha#99\"}");
        assertThat(redefinicao.getResponse().getStatus()).isEqualTo(200);
        assertThat(corpo(redefinicao).get("message").asText()).isEqualTo("Senha redefinida com sucesso.");

        assertThat(login("adm.aprovada@contrato.quack", SENHA).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("adm.aprovada@contrato.quack", "NovaSenha#99").getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, antes)).andReturn().getResponse().getStatus())
                .as("sessao anterior encerrada").isEqualTo(401);

        MvcResult reuso = postar("/api/Auth/reset-password",
                "{\"token\":\"" + token + "\",\"nextPassword\":\"OutraSenha#1\",\"confirmPassword\":\"OutraSenha#1\"}");
        assertThat(reuso.getResponse().getStatus()).isEqualTo(400);
        assertThat(corpo(reuso).get("message").asText()).isEqualTo("Token de redefinição inválido ou expirado.");
    }

    @Test
    void segundoPedidoInvalidaOTokenAnterior() throws Exception {
        String corpoPedido = "{\"cnpj\":\"11222333000181\",\"email\":\"adm.aprovada@contrato.quack\"}";
        postar("/api/Auth/forgot-password", corpoPedido);
        String primeiro = tokenDoEmail();
        smtp.purgeEmailFromAllMailboxes();
        postar("/api/Auth/forgot-password", corpoPedido);
        String segundo = tokenDoEmail();
        assertThat(segundo).isNotEqualTo(primeiro);

        MvcResult velho = postar("/api/Auth/reset-password",
                "{\"token\":\"" + primeiro + "\",\"nextPassword\":\"NovaSenha#99\",\"confirmPassword\":\"NovaSenha#99\"}");
        assertThat(velho.getResponse().getStatus()).isEqualTo(400);
        MvcResult novo = postar("/api/Auth/reset-password",
                "{\"token\":\"" + segundo + "\",\"nextPassword\":\"NovaSenha#99\",\"confirmPassword\":\"NovaSenha#99\"}");
        assertThat(novo.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void emailQueNaoExisteResponde200IgualENaoEnviaNada() throws Exception {
        MvcResult r = postar("/api/Auth/forgot-password", "{\"cnpj\":\"11.222.333/0001-81\",\"email\":\"fantasma@contrato.quack\"}");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(corpo(r).get("data").get("accepted").asBoolean()).isTrue();
        assertThat(corpo(r).get("data").get("maskedEmail").isNull()).isTrue();
        assertThat(smtp.getReceivedMessages()).isEmpty();
    }

    @Test
    void validacoesEConfirmacaoDiferente() throws Exception {
        assertThat(corpo(postar("/api/Auth/forgot-password", "{\"cnpj\":\"11.222.333/0001-82\",\"email\":\"a@b.com\"}")).get("message").asText())
                .isEqualTo("Informe um CNPJ válido.");
        assertThat(corpo(postar("/api/Auth/reset-password", "{\"token\":\"x\",\"nextPassword\":\"NovaSenha#99\",\"confirmPassword\":\"Outra\"}"))
                .get("message").asText()).isEqualTo("A confirmação de senha não confere.");
        assertThat(corpo(postar("/api/Auth/reset-password", "{\"token\":\"x\",\"nextPassword\":\"curta\",\"confirmPassword\":\"curta\"}"))
                .get("message").asText()).isEqualTo("A nova senha deve ter no minimo 8 caracteres.");
        assertThat(corpo(postar("/api/Auth/reset-password", "{\"token\":\"nao-existe\",\"nextPassword\":\"NovaSenha#99\",\"confirmPassword\":\"NovaSenha#99\"}"))
                .get("message").asText()).isEqualTo("Token de redefinição inválido ou expirado.");
    }

    @Test
    void redefinicaoAdministrativaExigeTrocaEncerraSessoesEMandaOEmail() throws Exception {
        String admin = bearer("admin@contrato.quack");
        String alvo = bearer("adm.aprovada@contrato.quack");
        // o admin da empresa-principal nao enxerga usuarios de outra empresa
        MvcResult outraEmpresa = mvc.perform(post("/api/Usuario/usr-ct-apr-adm/resetar-senha").header(HttpHeaders.AUTHORIZATION, admin)).andReturn();
        assertThat(outraEmpresa.getResponse().getStatus()).isEqualTo(404);

        String adminDaEmpresa = alvo;
        MvcResult r = mvc.perform(post("/api/Usuario/usr-ct-apr-adm/resetar-senha").header(HttpHeaders.AUTHORIZATION, adminDaEmpresa)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode dados = corpo(r).get("data");
        assertThat(dados.get("user").get("mustChangePassword").asBoolean()).isTrue();
        assertThat(dados.has("resetToken")).isFalse();
        assertThat(jdbc.queryForObject("SELECT MustChangePassword FROM Usuarios WHERE Id = 'usr-ct-apr-adm'", Boolean.class)).isTrue();
        assertThat(mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, alvo)).andReturn().getResponse().getStatus()).isEqualTo(401);

        String token = tokenDoEmail();
        MvcResult redefine = postar("/api/Auth/reset-password",
                "{\"token\":\"" + token + "\",\"nextPassword\":\"NovaSenha#99\",\"confirmPassword\":\"NovaSenha#99\"}");
        assertThat(redefine.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT MustChangePassword FROM Usuarios WHERE Id = 'usr-ct-apr-adm'", Boolean.class)).isFalse();
    }
}
