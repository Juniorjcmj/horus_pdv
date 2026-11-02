package br.com.quack.pdv.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ModuloMercadoWebTest extends WebTestBase {
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente"}) void permitido(String p) throws Exception {mvc.perform(get("/api/ModuloMercado/estoque").header("Authorization","Bearer "+tokenDe(p))).andExpect(status().isOk());}
    @Test void caixaNegado() throws Exception {mvc.perform(get("/api/ModuloMercado/estoque").header("Authorization","Bearer "+tokenDe("caixa"))).andExpect(status().isForbidden());}
    @Test void criar201() throws Exception {mvc.perform(post("/api/ModuloMercado/estoque/registros").header("Authorization","Bearer "+tokenDe("gerente")).contentType("application/json").content("{}")).andExpect(status().isCreated());}
    @Test void semSessao() throws Exception {mvc.perform(get("/api/ModuloMercado/estoque")).andExpect(status().isUnauthorized());}
}
