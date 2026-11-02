package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.auth.app.AutenticacaoService;
import br.com.quack.pdv.auth.app.RecaptchaService;
import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.sessao.domain.Sessao;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Rotas /api/Auth (login, logout, me, troca de senha, recuperacao e cadastro) com servicos mockados. */
class AuthControllerWebTest extends WebTestBase {

    static UsuarioDto usuario() {
        return new UsuarioDto("usr-1", "empresa-principal", "11111111111", "Ana", "ana@x.com", "1199", "gerente", "ativo",
                "2026-01-01", "-", false, List.of("financeiro"));
    }

    static Sessao sessao() {
        Sessao s = new Sessao();
        s.setId("sess-abc");
        s.setUserId("usr-1");
        return s;
    }

    ResultActions login(String corpo) throws Exception {
        return mvc.perform(post("/api/Auth/login").contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    // ---- login ----

    @Test
    void loginValidoDevolveCorpoECookieDeSessao() throws Exception {
        when(autenticacao.login(eq("ana@x.com"), eq("Senha#1234"), anyString(), anyString()))
                .thenReturn(new AutenticacaoService.LoginSucesso(usuario(), sessao()));

        MvcResult r = login("{\"email\":\"ana@x.com\",\"password\":\"Senha#1234\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login realizado com sucesso."))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(28800))
                .andExpect(jsonPath("$.data.sessionId").value("sess-abc"))
                .andExpect(jsonPath("$.data.user.email").value("ana@x.com"))
                .andExpect(jsonPath("$.data.user.perfisAdicionais[0]").value("financeiro"))
                .andReturn();

        jakarta.servlet.http.Cookie cookie = r.getResponse().getCookie("horuspdv.auth");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualToIgnoringCase("strict");
        assertThat(cookie.getAttribute("Expires")).isNull(); // sem rememberMe: cookie de sessao do navegador
        assertThat(jwt.validarToken(cookie.getValue()).sessionId()).isEqualTo("sess-abc");
    }

    @Test
    void rememberMeAcrescentaExpiresDoCookie() throws Exception {
        when(autenticacao.login(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AutenticacaoService.LoginSucesso(usuario(), sessao()));
        MvcResult r = login("{\"email\":\"ana@x.com\",\"password\":\"x\",\"rememberMe\":true}").andReturn();
        String cabecalho = r.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(cabecalho).containsIgnoringCase("expires=").containsIgnoringCase("GMT").containsIgnoringCase("httponly");
        // o MockMvc reescreve o Set-Cookie; o formato bruto do ASP.NET e conferido no teste de contrato (HTTP real)
    }

    @Test
    void camposVaziosRecebem400() throws Exception {
        login("{\"email\":\"\",\"password\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe e-mail e senha."));
        login("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Informe e-mail e senha."));
    }

    @Test
    void senhaErradaEBloqueioPassamOStatusEOLockedUntil() throws Exception {
        when(autenticacao.login(eq("ana@x.com"), eq("errada"), anyString(), anyString()))
                .thenThrow(new NegocioException(HttpStatus.UNAUTHORIZED, "E-mail ou senha inválidos."));
        login("{\"email\":\"ana@x.com\",\"password\":\"errada\"}").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("E-mail ou senha inválidos."))
                .andExpect(jsonPath("$.data").doesNotExist());

        when(autenticacao.login(eq("ana@x.com"), eq("de-novo"), anyString(), anyString()))
                .thenThrow(new NegocioException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas inválidas. Aguarde alguns minutos para tentar novamente.",
                        null, Map.of("lockedUntil", "2026-11-01T12:10:00.0000000+00:00")));
        login("{\"email\":\"ana@x.com\",\"password\":\"de-novo\"}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.data.lockedUntil").value("2026-11-01T12:10:00.0000000+00:00"));
    }

    @Test
    void recaptchaReprovadoNoLoginDevolveStatusMensagemEDetails() throws Exception {
        when(recaptcha.verificar(any(), eq("login"), any())).thenReturn(new RecaptchaService.Resultado(false, 409,
                "Validação de segurança expirou. Tente novamente.", "RECAPTCHA_TOKEN_EXPIRED", false));
        login("{\"email\":\"ana@x.com\",\"password\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").value("RECAPTCHA_TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Validação de segurança expirou. Tente novamente."));
    }

    // ---- logout ----

    @Test
    void logoutApagaASessaoEOCookie() throws Exception {
        MvcResult r = mvc.perform(post("/api/Auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe("caixa")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logout realizado com sucesso."))
                .andReturn();
        verify(autenticacao).logout("sess-usr-1");
        jakarta.servlet.http.Cookie apagado = r.getResponse().getCookie("horuspdv.auth");
        assertThat(apagado.getValue()).isEmpty();
        assertThat(apagado.getAttribute("Expires")).containsIgnoringCase("1970");
        assertThat(apagado.isHttpOnly()).isTrue();
    }

    // ---- me / perfil / senha ----

    @Test
    void meDevolveOUsuarioEUsuarioInativoRecebe401() throws Exception {
        String auth = "Bearer " + tokenDe("gerente");
        when(autenticacao.usuarioAtivo("usr-1", "empresa-principal")).thenReturn(Optional.of(usuario()));
        mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Usuario autenticado obtido com sucesso."))
                .andExpect(jsonPath("$.data.id").value("usr-1"));

        when(autenticacao.usuarioAtivo("usr-1", "empresa-principal")).thenReturn(Optional.empty());
        mvc.perform(get("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, auth)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Usuario inativo ou inexistente."));
    }

    @Test
    void atualizarPerfil() throws Exception {
        String auth = "Bearer " + tokenDe("gerente");
        when(autenticacao.atualizarPerfil("usr-1", "empresa-principal", "Ana B", "ab@x.com", "11"))
                .thenReturn(Optional.of(usuario()));
        mvc.perform(put("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana B\",\"email\":\"ab@x.com\",\"phone\":\"11\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Perfil atualizado com sucesso."));

        when(autenticacao.atualizarPerfil(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new NegocioException(HttpStatus.BAD_REQUEST, "Este e-mail já está cadastrado no sistema."));
        mvc.perform(put("/api/Auth/me").header(HttpHeaders.AUTHORIZATION, auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ana\",\"email\":\"outro@x.com\",\"phone\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Este e-mail já está cadastrado no sistema."));
    }

    @Test
    void trocarSenha() throws Exception {
        String auth = "Bearer " + tokenDe("gerente");
        when(autenticacao.trocarSenha("usr-1", "empresa-principal", "Atual#123", "NovaSenha1")).thenReturn(true);
        mvc.perform(post("/api/Auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Atual#123\",\"nextPassword\":\"NovaSenha1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Senha atualizada com sucesso. Faça login novamente."));

        when(autenticacao.trocarSenha(anyString(), anyString(), eq("errada"), anyString())).thenReturn(false);
        mvc.perform(post("/api/Auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"errada\",\"nextPassword\":\"NovaSenha1\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Senha atual inválida."));

        when(autenticacao.trocarSenha(anyString(), anyString(), anyString(), eq("curta")))
                .thenThrow(new NegocioException(HttpStatus.BAD_REQUEST, "A nova senha deve ter no minimo 8 caracteres."));
        mvc.perform(post("/api/Auth/change-password").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"nextPassword\":\"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A nova senha deve ter no minimo 8 caracteres."));
    }

    // ---- recuperacao de senha (9.3) ----

    static final String FORGOT = "{\"cnpj\":\"11.222.333/0001-81\",\"email\":\"ana@x.com\"}";

    ResultActions forgot(String corpo) throws Exception {
        return mvc.perform(post("/api/Auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @Test
    void forgotPasswordValidaCnpjEEmail() throws Exception {
        forgot("{\"cnpj\":\"123\",\"email\":\"ana@x.com\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe um CNPJ válido."));
        forgot("{\"cnpj\":\"11.222.333/0001-81\",\"email\":\"semarroba\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe um e-mail válido."));
    }

    @Test
    void forgotPasswordSemEmailEmProducaoRecebe503() throws Exception {
        when(email.habilitado()).thenReturn(false);
        forgot(FORGOT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Recuperação de senha indisponível. Configure o envio de e-mail."));
    }

    @Test
    void forgotPasswordAceitoNaoRevelaOTokenNemSeOEmailExiste() throws Exception {
        OffsetDateTime expira = OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.UTC);
        when(email.habilitado()).thenReturn(true);
        when(email.montarUrlRedefinicao("tok123")).thenReturn("http://front/?resetToken=tok123");
        when(recuperacao.solicitar(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RecuperacaoSenhaService.PedidoRedefinicao(true, "a***a@x.com", "tok123", expira));

        MvcResult r = forgot(FORGOT).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Se o e-mail estiver cadastrado, enviaremos as instruções de recuperação."))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.maskedEmail").value("a***a@x.com"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-01T12:30:00+00:00"))
                .andExpect(jsonPath("$.data.resetToken").doesNotExist())
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("tok123");
        verify(email).enviarRedefinicaoSenha("ana@x.com", "Quack PDV", "http://front/?resetToken=tok123", expira);
    }

    @Test
    void forgotPasswordComEmailQueNaoExisteResponde200SemDados() throws Exception {
        when(email.habilitado()).thenReturn(true);
        when(recuperacao.solicitar(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RecuperacaoSenhaService.PedidoRedefinicao(true, null, null, null));
        forgot(FORGOT).andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.maskedEmail").value((Object) null));
    }

    @Test
    void forgotPasswordComFalhaNoEnvioConsomeOTokenERecebe502() throws Exception {
        OffsetDateTime expira = OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.UTC);
        when(email.habilitado()).thenReturn(true);
        when(email.montarUrlRedefinicao(anyString())).thenReturn("http://front/?resetToken=tok123");
        when(recuperacao.solicitar(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RecuperacaoSenhaService.PedidoRedefinicao(true, "a***a@x.com", "tok123", expira));
        doThrow(new IllegalStateException("smtp fora")).when(email)
                .enviarRedefinicaoSenha(anyString(), anyString(), anyString(), any());

        forgot(FORGOT).andExpect(status().isBadGateway()).andExpect(jsonPath("$.message")
                .value("Não foi possível enviar o e-mail de recuperação. Verifique a configuração SMTP."));
        verify(recuperacao).consumir(eq("tok123"), anyString(), anyString());
    }

    @Test
    void resetPasswordTrocaAsenhaEApagaOCookie() throws Exception {
        when(recuperacao.redefinir(eq("tok"), eq("NovaSenha1"), eq("NovaSenha1"), anyString(), anyString()))
                .thenReturn(usuario());
        MvcResult r = mvc.perform(post("/api/Auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"tok\",\"nextPassword\":\"NovaSenha1\",\"confirmPassword\":\"NovaSenha1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Senha redefinida com sucesso."))
                .andExpect(jsonPath("$.data.id").value("usr-1")).andReturn();
        assertThat(r.getResponse().getCookie("horuspdv.auth").getValue()).isEmpty();

        when(recuperacao.redefinir(eq("velho"), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new NegocioException(HttpStatus.BAD_REQUEST, "Token de redefinição inválido ou expirado."));
        mvc.perform(post("/api/Auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"velho\",\"nextPassword\":\"NovaSenha1\",\"confirmPassword\":\"NovaSenha1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Token de redefinição inválido ou expirado."));
    }

    // ---- cadastro (9.4) ----

    static final String REGISTRO = "{\"cnpj\":\"11.222.333/0001-81\",\"name\":\"Loja\",\"email\":\"loja@x.com\","
            + "\"phone\":\"11\",\"password\":\"Senha#1234\",\"confirmPassword\":\"Senha#1234\"}";

    @Test
    void cadastroCriaERecebe201EEnviaBoasVindas() throws Exception {
        when(cadastro.cadastrar(any())).thenReturn(usuario());
        mvc.perform(post("/api/Auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Cadastro criado com sucesso."));
        verify(email).enviarBoasVindas("loja@x.com", "Loja");
    }

    @Test
    void cadastroNaoCaiSeOEmailDeBoasVindasFalhar() throws Exception {
        when(cadastro.cadastrar(any())).thenReturn(usuario());
        doThrow(new IllegalStateException("smtp")).when(email).enviarBoasVindas(anyString(), anyString());
        mvc.perform(post("/api/Auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isCreated());
    }

    @Test
    void cadastroRecusadoPelasRegrasRecebe400EErroInesperadoRecebe500() throws Exception {
        doThrow(new NegocioException(HttpStatus.BAD_REQUEST,
                "Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta.")).when(cadastro).cadastrar(any());
        mvc.perform(post("/api/Auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                        .value("Este CNPJ já possui uma empresa cadastrada no sistema. Faça login com sua conta."));

        doThrow(new IllegalStateException("banco caiu")).when(cadastro).cadastrar(any());
        mvc.perform(post("/api/Auth/register").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message")
                        .value("Não foi possível concluir o cadastro. Se já possui conta, faça login."));
    }
}
