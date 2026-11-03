package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 4.4: em producao nao ha Swagger nem OpenAPI; o health continua respondendo sem token. */
class SwaggerProducaoWebTest extends WebTestBase {

    @Test
    void apiDocsESwaggerNaoExistemEmProducao() throws Exception {
        // sem token a autenticacao responde 401 antes de qualquer rota
        assertThat(mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus()).isEqualTo(401);
        // com token a rota simplesmente nao existe
        String token = tokenDe("administrador");
        assertThat(mvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/swagger-ui.html").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void healthSemTokenResponde() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
