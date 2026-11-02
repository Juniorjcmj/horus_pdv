package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/** 4.4: no perfil dev o Swagger e a OpenAPI respondem sem token; o health tambem. */
@ActiveProfiles({"test", "dev"})
class SwaggerDevWebTest extends WebTestBase {

    @Test
    void apiDocsESwaggerRespondemSemTokenEmDev() throws Exception {
        MvcResult docs = mvc.perform(get("/v3/api-docs")).andReturn();
        assertThat(docs.getResponse().getStatus()).isEqualTo(200);
        assertThat(docs.getResponse().getContentAsString()).contains("\"openapi\"");
        MvcResult ui = mvc.perform(get("/swagger-ui.html")).andReturn();
        assertThat(ui.getResponse().getStatus()).isIn(200, 302);
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }
}
