package br.com.quack.pdv.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
class TreinamentoWebTest extends WebTestBase {
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente","caixa"}) void todosLeem(String p) throws Exception {mvc.perform(get("/api/treinamento").header("Authorization","Bearer "+tokenDe(p))).andExpect(status().isOk()).andExpect(jsonPath("data.podeGerenciar").value(p.equals("administrador")));}
    @Test void adminPrincipalCriaSecao() throws Exception {when(treinamento.salvarSecao(isNull(),any())).thenReturn("trs-teste");mvc.perform(post("/api/treinamento/secoes").header("Authorization","Bearer "+tokenDe("administrador")).contentType("application/json").content("{\"nome\":\"Secao\"}")).andExpect(status().isOk()).andExpect(jsonPath("data.id").value("trs-teste"));}
    @Test void adminLojaNaoEdita() throws Exception {mvc.perform(post("/api/treinamento/secoes").header("Authorization","Bearer "+tokenDaEmpresa("u","loja","administrador","aprovada")).contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("message").value("Somente o administrador geral da plataforma pode editar os treinamentos."));verify(treinamento,never()).salvarSecao(any(),any());}
    @Test void semSessao() throws Exception {mvc.perform(get("/api/treinamento")).andExpect(status().isUnauthorized());}
}
