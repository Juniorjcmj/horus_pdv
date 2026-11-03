package br.com.quack.pdv.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ClienteWebTest extends WebTestBase {
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente","caixa"}) void todosLeemECriam(String p) throws Exception {String token=tokenDe(p);mvc.perform(get("/api/Cliente").header("Authorization","Bearer "+token)).andExpect(status().isOk());mvc.perform(post("/api/Cliente").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isCreated());}
    @Test void caixaNaoEdita() throws Exception {mvc.perform(put("/api/Cliente/cl").header("Authorization","Bearer "+tokenDe("caixa")).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
    @Test void atendenteNaoExclui() throws Exception {mvc.perform(delete("/api/Cliente/cl").header("Authorization","Bearer "+tokenDe("atendente"))).andExpect(status().isForbidden());}
    @Test void snapshotAutenticado() throws Exception {mvc.perform(post("/api/Cliente/credito-snapshot").header("Authorization","Bearer "+tokenDe("caixa")).contentType("application/json").content("[]")).andExpect(status().isOk());}
    @Test void snapshotSemSessao() throws Exception {mvc.perform(post("/api/Cliente/credito-snapshot").contentType("application/json").content("[]")).andExpect(status().isUnauthorized()).andExpect(jsonPath("success").value(false)).andExpect(jsonPath("message").value("Sessao expirada ou token invalido."));}
}
