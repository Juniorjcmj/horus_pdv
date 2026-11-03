package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.security.JwtService;
import br.com.quack.pdv.sessao.app.ValidadorSessao;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** 6.1: cada saida do filtro de autenticacao (mensagens e ordem como o HorusAuthMiddleware). */
class AutenticacaoFilterWebTest extends WebTestBase {

    static final String MSG_401 = "Sessao expirada ou token invalido.";

    @Test
    void semTokenRecebe401() throws Exception {
        mvc.perform(get("/api/Teste/eco")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.message").value(MSG_401));
    }

    @Test
    void tokenLixoOuAdulteradoRecebe401() throws Exception {
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer lixo"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(MSG_401));
        String t = tokenDe("administrador");
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cabecalhoMalFormadoRecebe401() throws Exception {
        String t = tokenDe("administrador");
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "bearer " + t))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, t))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t + " extra"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenValidoPeloBearerEPeloCookie() throws Exception {
        String t = tokenDe("administrador");
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)).andExpect(status().isOk());
        mvc.perform(get("/api/Teste/eco").cookie(new Cookie(JwtService.AUTH_COOKIE_NAME, t))).andExpect(status().isOk());
    }

    @Test
    void cookieTemPrioridadeSobreOBearer() throws Exception {
        String bom = tokenDe("administrador");
        mvc.perform(get("/api/Teste/eco").cookie(new Cookie(JwtService.AUTH_COOKIE_NAME, "lixo"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bom)).andExpect(status().isUnauthorized());
    }

    @Test
    void sessaoEncerradaRecebe401() throws Exception {
        String t = tokenDe("administrador");
        when(validador.validar(anyString(), anyString(), anyString()))
                .thenReturn(new ValidadorSessao.Validacao(false, true, "aprovada", ""));
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(MSG_401));
    }

    @Test
    void usuarioInativoRecebe401() throws Exception {
        String t = tokenDe("administrador");
        when(validador.validar(anyString(), anyString(), anyString()))
                .thenReturn(new ValidadorSessao.Validacao(true, false, "aprovada", ""));
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void empresaForaDeAprovadaRecebe403ComAMensagemDaSituacao() throws Exception {
        String[][] casos = {
                {"pendente", "Sua empresa ainda aguarda aprovação da administração."},
                {"rejeitada", "O cadastro da sua empresa foi recusado."},
                {"bloqueada", "O acesso da sua empresa está bloqueado/suspenso."},
                {"qualquer", "Acesso restrito para esta empresa."}};
        for (String[] caso : casos) {
            String t = tokenDaEmpresa("usr-2", "emp-x", "administrador", caso[0]);
            mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(caso[1]));
        }
    }

    @Test
    void empresaPrincipalNuncaEBloqueadaPelaSituacao() throws Exception {
        String t = tokenDaEmpresa("usr-3", "empresa-principal", "administrador", "bloqueada");
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + t)).andExpect(status().isOk());
    }

    @Test
    void tokensDoGatewayEDeIntegracaoRecebem401NestaFase() throws Exception {
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer qgw_abc123"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(MSG_401));
        mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer qmcp_abc123"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void caminhoMcpRecebe401MesmoComJwtValidoECookie() throws Exception {
        String t = tokenDe("administrador");
        mvc.perform(post("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + t).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(MSG_401));
        mvc.perform(post("/MCP/x").cookie(new Cookie(JwtService.AUTH_COOKIE_NAME, t))).andExpect(status().isUnauthorized());
    }

    @Test
    void rotasPublicasENaoExigemToken() throws Exception {
        when(recaptcha.verificar(any(), any(), any()))
                .thenReturn(new br.com.quack.pdv.auth.app.RecaptchaService.Resultado(true, 200, "", "", true));
        for (String rota : new String[] {"/api/Auth/login", "/api/auth/LOGIN", "/api/Auth/forgot-password",
                "/api/Auth/reset-password", "/api/Auth/register"}) {
            int status = mvc.perform(post(rota).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn().getResponse().getStatus();
            org.assertj.core.api.Assertions.assertThat(status).as(rota).isNotEqualTo(401).isNotEqualTo(403);
        }
    }

    @Test
    void preflightNaoExigeToken() throws Exception {
        int status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/Teste/eco"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isNotEqualTo(401);
    }
}
