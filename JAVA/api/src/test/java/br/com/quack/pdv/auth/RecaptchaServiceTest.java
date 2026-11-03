package br.com.quack.pdv.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import br.com.quack.pdv.auth.app.RecaptchaService;
import br.com.quack.pdv.config.QuackProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RecaptchaServiceTest {

    QuackProperties props;
    MockRestServiceServer servidor;
    RecaptchaService service;

    @BeforeEach
    void preparar() {
        props = new QuackProperties();
        props.getRecaptcha().setEnabled(true);
        props.getRecaptcha().setSecretKey("chave-secreta");
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        service = new RecaptchaService(props, builder, new ObjectMapper());
    }

    void google(String json) {
        servidor.expect(requestTo(RecaptchaService.URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void desligadoPassaSemChamarOGoogle() {
        props.getRecaptcha().setEnabled(false);
        RecaptchaService.Resultado r = service.verificar("", "login", "1.1.1.1");
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isTrue();
        servidor.verify();
    }

    @Test
    void ligadoSemChaveRecebe500() {
        props.getRecaptcha().setSecretKey("");
        RecaptchaService.Resultado r = service.verificar("t", "login", "1.1.1.1");
        assertThat(r.success()).isFalse();
        assertThat(r.statusCode()).isEqualTo(500);
        assertThat(r.message()).isEqualTo("Validação de segurança indisponível. Configure Recaptcha:SecretKey na API.");
    }

    @Test
    void semTokenRecebe400() {
        RecaptchaService.Resultado r = service.verificar("  ", "login", "1.1.1.1");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.message()).isEqualTo("Validação de segurança obrigatória. Atualize e tente novamente.");
    }

    @Test
    void falhaDeRedeOuHttpDoGoogleRecebe503() {
        servidor.expect(requestTo(RecaptchaService.URL)).andRespond(withServerError());
        RecaptchaService.Resultado r = service.verificar("tok", "login", "1.1.1.1");
        assertThat(r.statusCode()).isEqualTo(503);
        assertThat(r.message()).isEqualTo("Não foi possível validar o reCAPTCHA no momento.");
    }

    @Test
    void timeoutOuDuplicadoRecebe409TokenExpirado() {
        google("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
        RecaptchaService.Resultado r = service.verificar("tok", "login", "1.1.1.1");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.message()).isEqualTo("Validação de segurança expirou. Tente novamente.");
        assertThat(r.details()).isEqualTo("RECAPTCHA_TOKEN_EXPIRED");
    }

    @Test
    void invalidInputResponseTambemE409() {
        google("{\"success\":false,\"error-codes\":[\"Invalid-Input-Response\"]}");
        assertThat(service.verificar("tok", "login", "1.1.1.1").statusCode()).isEqualTo(409);
    }

    @Test
    void outrasFalhasRecebem400ValidationFailed() {
        google("{\"success\":false,\"error-codes\":[\"missing-input-secret\"]}");
        RecaptchaService.Resultado r = service.verificar("tok", "login", "1.1.1.1");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.message()).isEqualTo("Falha na validação de segurança. Tente novamente.");
        assertThat(r.details()).isEqualTo("RECAPTCHA_VALIDATION_FAILED");
    }

    @Test
    void acaoDiferenteRecebe400() {
        google("{\"success\":true,\"action\":\"outra\",\"score\":0.9}");
        RecaptchaService.Resultado r = service.verificar("tok", "login", "1.1.1.1");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.details()).isEqualTo("RECAPTCHA_ACTION_MISMATCH");
        assertThat(r.message()).isEqualTo("Validação de segurança inválida para esta operação.");
    }

    @Test
    void notaAbaixoDoMinimoRecebe400() {
        google("{\"success\":true,\"action\":\"login\",\"score\":0.3}");
        RecaptchaService.Resultado r = service.verificar("tok", "login", "1.1.1.1");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.details()).isEqualTo("RECAPTCHA_SCORE_REJECTED");
        assertThat(r.message()).isEqualTo("Validação de segurança reprovada. Tente novamente.");
    }

    @Test
    void sucessoComAcaoENotaBoas() {
        servidor.expect(requestTo(RecaptchaService.URL))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("secret=chave-secreta")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("response=tok")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("remoteip=200.1.1.1")))
                .andRespond(withSuccess("{\"success\":true,\"action\":\"login\",\"score\":0.9}", MediaType.APPLICATION_JSON));
        RecaptchaService.Resultado r = service.verificar(" tok ", "login", "200.1.1.1, 10.0.0.1");
        assertThat(r.success()).isTrue();
        assertThat(r.skipped()).isFalse();
        servidor.verify();
    }
}
