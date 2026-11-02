package br.com.quack.pdv.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
class FornecedorWebTest extends WebTestBase {
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente"}) void perfisPermitidos(String p) throws Exception { mvc.perform(get("/api/Fornecedor").header("Authorization","Bearer "+tokenDe(p))).andExpect(status().isOk()); }
    @Test void caixaNaoAcessa() throws Exception {mvc.perform(get("/api/Fornecedor").header("Authorization","Bearer "+tokenDe("caixa"))).andExpect(status().isForbidden());}
    @Test void semSessao() throws Exception {mvc.perform(get("/api/Fornecedor")).andExpect(status().isUnauthorized());}
    @Test void criarRetorna201ECompanyDoToken() throws Exception {mvc.perform(post("/api/Fornecedor").header("Authorization","Bearer "+tokenDaEmpresa("u","loja","atendente","aprovada")).contentType("application/json").content("{\"companyId\":\"outra\",\"fantasyName\":\"Sol\"}")).andExpect(status().isCreated());verify(fornecedores).criar(eq("loja"),any());}
}
