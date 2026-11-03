package br.com.quack.pdv.web;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ApiDocumentationWebTest extends WebTestBase {
    @Test void geraDocumentoDaAplicacaoERespeitaServidor() throws Exception {
        var r=mvc.perform(get("/api/Admin/Empresas/documentacao-api").with(req->{req.setScheme("https");req.setServerName("pdv.exemplo.com");req.setServerPort(8443);return req;}).header("Authorization","Bearer "+tokenDe("administrador")))
            .andExpect(status().isOk()).andExpect(header().string("Content-Type","application/json;charset=utf-8"))
            .andExpect(header().string("Content-Disposition","attachment; filename=quack-pdv-api-openapi.json; filename*=UTF-8''quack-pdv-api-openapi.json")).andReturn().getResponse();
        var doc=new ObjectMapper().readTree(r.getContentAsByteArray());assertThat(doc.get("openapi").asText()).startsWith("3.");assertThat(doc.path("paths").has("/api/Empresa")).isTrue();assertThat(doc.path("servers").get(0).path("url").asText()).isEqualTo("https://pdv.exemplo.com:8443");assertThat(doc.path("info").path("title").asText()).isEqualTo("Quack Sistemas — API do PDV");
    }
    @Test void adminLojaNegado() throws Exception {mvc.perform(get("/api/Admin/Empresas/documentacao-api").header("Authorization","Bearer "+tokenDaEmpresa("u","loja","administrador","aprovada"))).andExpect(status().isForbidden()).andExpect(jsonPath("message").value("Documentação da API restrita ao Administrador Geral da Plataforma."));}
    @ParameterizedTest @ValueSource(strings={"gerente","atendente","caixa"}) void perfisNegados(String p) throws Exception {mvc.perform(get("/api/Admin/Empresas/documentacao-api").header("Authorization","Bearer "+tokenDe(p))).andExpect(status().isForbidden());}
    @Test void semSessao() throws Exception {mvc.perform(get("/api/Admin/Empresas/documentacao-api")).andExpect(status().isUnauthorized());}
}
