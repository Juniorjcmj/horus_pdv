package br.com.quack.pdv.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
class CategoriaWebTest extends WebTestBase {
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente","caixa"}) void autenticadoLe(String p) throws Exception {mvc.perform(get("/api/Categoria?plana=true&apenasAtivas=true").header("Authorization","Bearer "+tokenDe(p))).andExpect(status().isOk());verify(categorias).listar("empresa-principal",true,true);}
    @ParameterizedTest @ValueSource(strings={"administrador","gerente"}) void gestorCria(String p) throws Exception {mvc.perform(post("/api/Categoria").header("Authorization","Bearer "+tokenDe(p)).contentType("application/json").content("{}")).andExpect(status().isCreated());}
    @ParameterizedTest @ValueSource(strings={"atendente","caixa"}) void operadorNaoCria(String p) throws Exception {mvc.perform(post("/api/Categoria").header("Authorization","Bearer "+tokenDe(p)).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
    @Test void semSessao() throws Exception {mvc.perform(get("/api/Categoria")).andExpect(status().isUnauthorized());}
}
