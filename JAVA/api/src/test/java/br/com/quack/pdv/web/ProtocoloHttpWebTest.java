package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Envelope, erros, Jackson, roteamento, cabecalhos e CORS (tarefas dos grupos 4 e 5). */
class ProtocoloHttpWebTest extends WebTestBase {

    String bearer() {
        return "Bearer " + tokenDe("administrador");
    }

    // ---- 4.1 envelope e erros ----

    @Test
    void negocioExceptionViraEnvelopeComOStatus() throws Exception {
        mvc.perform(get("/api/Teste/negocio").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Regra violada."))
                .andExpect(jsonPath("$.details").value("DETALHE_X"))
                .andExpect(jsonPath("$.data.k").value("v"));
    }

    @Test
    void corpoInvalidoViraProblemDetails400() throws Exception {
        mvc.perform(post("/api/Teste/corpo").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{ nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.type").value("https://tools.ietf.org/html/rfc9110#section-15.5.1"))
                .andExpect(jsonPath("$.title").value("One or more validation errors occurred."))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors").exists())
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void erroInesperadoNaoVazaDetalhesEViraSoSuccessEMessage() throws Exception {
        MvcResult r = mvc.perform(get("/api/Teste/quebra").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Erro interno no servidor."))
                .andReturn();
        String corpo = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(corpo).isEqualTo("{\"success\":false,\"message\":\"Erro interno no servidor.\"}");
    }

    // ---- 4.2 Jackson ----

    @Test
    void jsonComNulosPresentesEDatasNoFormatoDotNet() throws Exception {
        String corpo = mvc.perform(get("/api/Teste/json").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(corpo).contains("\"nulo\":null");
        assertThat(corpo).contains("\"inteira\":\"2026-11-01T22:30:00+00:00\"");
        assertThat(corpo).contains("\"fracao\":\"2026-11-01T22:30:00.1234567+00:00\"");
        assertThat(corpo).contains("\"fracaoCurta\":\"2026-11-01T22:30:00.12+00:00\"");
        assertThat(corpo).contains("\"comOffset\":\"2026-11-01T19:30:00-03:00\"");
        assertThat(corpo).contains("\"acentos\":\"Ação café\"");
    }

    @Test
    void entradaAceitaNomesPascalCaseECamposExtras() throws Exception {
        mvc.perform(post("/api/Teste/corpo").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"Nome\":\"Ana\",\"IDADE\":30,\"campoQueNaoExiste\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Ana"))
                .andExpect(jsonPath("$.idade").value(30));
    }

    // ---- 4.3 roteamento ----

    @Test
    void rotaSemDiferenciarMaiusculasENemBarraFinal() throws Exception {
        String auth = bearer();
        for (String caminho : new String[] {"/api/Teste/eco", "/API/TESTE/ECO", "/api/teste/eco", "/api/Teste/eco/",
                "/Api/Teste/Eco/"}) {
            mvc.perform(get(caminho).header(HttpHeaders.AUTHORIZATION, auth))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("ok"));
        }
    }

    @Test
    void rotaInexistenteEMetodoErradoSaemSemCorpo() throws Exception {
        String auth = bearer();
        mvc.perform(get("/api/Teste/nao-existe").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(delete("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isMethodNotAllowed()).andExpect(content().string(""));
    }

    // ---- 5.1 cabecalhos ----

    @Test
    void todaRespostaTraSeteCabecalhosDeSegurancaESemServer() throws Exception {
        MvcResult r = mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();
        var h = r.getResponse();
        assertThat(h.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(h.getHeader("Permissions-Policy")).isEqualTo("camera=(), microphone=(), geolocation=()");
        assertThat(h.getHeader("X-Permitted-Cross-Domain-Policies")).isEqualTo("none");
        assertThat(h.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("cross-origin");
        assertThat(h.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(h.getHeader("Server")).isNull();
        assertThat(h.getHeader("X-Powered-By")).isNull();
        assertThat(h.getHeader("X-Request-Id")).matches("[0-9a-f]{32}");
    }

    @Test
    void respostaDeErroDoFiltroTambemTemOsCabecalhos() throws Exception {
        MvcResult r = mvc.perform(get("/api/Teste/eco")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("X-Request-Id")).isNotBlank();
    }

    // ---- 5.3 limite de corpo ----

    @Test
    void corpoAcimaDoLimiteRecebe413() throws Exception {
        byte[] grande = new byte[3_000_000];
        mvc.perform(post("/api/Teste/corpo").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(grande))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Payload maior que o permitido."));
    }

    // ---- 5.4 CORS ----

    @Test
    void preflightDeOrigemPermitidaTemOsCabecalhosEOrigemDesconhecidaNao() throws Exception {
        for (String origem : new String[] {"https://pdv.quacksistemas.com.br", "http://localhost:5173",
                "https://loja.quacksistemas.com.br", "http://127.0.0.1:3000", "https://x.wootchat.com.br"}) {
            MvcResult r = mvc.perform(options("/api/Auth/login").header("Origin", origem)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type,x-qualquer")).andReturn();
            assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).as(origem).isEqualTo(origem);
            assertThat(r.getResponse().getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        }
        MvcResult negada = mvc.perform(options("/api/Auth/login").header("Origin", "https://exemplo.com")
                .header("Access-Control-Request-Method", "POST")).andReturn();
        assertThat(negada.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();

        MvcResult negadaSimples = mvc.perform(get("/api/Teste/eco").header("Origin", "https://exemplo.com")
                .header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();
        assertThat(negadaSimples.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void respostaDe401TambemTemCors() throws Exception {
        MvcResult r = mvc.perform(get("/api/Teste/eco").header("Origin", "http://localhost:5173")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    }
}
