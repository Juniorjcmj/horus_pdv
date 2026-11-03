package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/** Perfil dev: cookie sem Secure e SameSite=Lax; recuperacao devolve o token com o e-mail desligado. */
@ActiveProfiles({"test", "dev"})
class AuthDevWebTest extends WebTestBase {

    static UsuarioDto usuario() {
        return new UsuarioDto("usr-1", "empresa-principal", "11111111111", "Ana", "ana@x.com", "11", "gerente", "ativo",
                "2026-01-01", "-", false, List.of());
    }

    @Test
    void cookieEmDevNaoTemSecureETemSameSiteLax() throws Exception {
        Sessao s = new Sessao();
        s.setId("sess-1");
        when(autenticacao.login(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AutenticacaoService.LoginSucesso(usuario(), s));
        MvcResult r = mvc.perform(post("/api/Auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@x.com\",\"password\":\"x\"}")).andExpect(status().isOk()).andReturn();
        jakarta.servlet.http.Cookie cookie = r.getResponse().getCookie("horuspdv.auth");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("lax");
    }

    @Test
    void forgotPasswordEmDevComEmailDesligadoDevolveOTokenCompleto() throws Exception {
        OffsetDateTime expira = OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.UTC);
        when(email.habilitado()).thenReturn(false);
        when(recuperacao.solicitar(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RecuperacaoSenhaService.PedidoRedefinicao(true, "a***a@x.com", "tok-dev", expira));
        when(email.montarUrlRedefinicao("tok-dev")).thenReturn("http://front/?resetToken=tok-dev");

        mvc.perform(post("/api/Auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"11.222.333/0001-81\",\"email\":\"ana@x.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resetToken").value("tok-dev"))
                .andExpect(jsonPath("$.data.maskedEmail").value("a***a@x.com"));
        // com o e-mail desligado o servico de e-mail e quem decide nao enviar (EmailService.enviarRedefinicaoSenha)
        verify(email).enviarRedefinicaoSenha("ana@x.com", "Quack PDV", "http://front/?resetToken=tok-dev", expira);
    }

    @Test
    void resetarSenhaEmDevComEmailDesligadoDevolveOResultadoCompleto() throws Exception {
        OffsetDateTime expira = OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.UTC);
        when(email.habilitado()).thenReturn(false);
        when(recuperacao.solicitarAdministrativo(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.of(
                new RecuperacaoSenhaService.RedefinicaoAdministrativa(usuario(), true, "a***a@x.com", "tok-adm", expira)));
        mvc.perform(post("/api/Usuario/usr-1/resetar-senha")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe("administrador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resetToken").value("tok-adm"))
                .andExpect(jsonPath("$.data.user.id").value("usr-1"));
        org.mockito.Mockito.verify(email, org.mockito.Mockito.never())
                .enviarRedefinicaoSenha(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }
}
