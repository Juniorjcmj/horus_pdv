package br.com.quack.pdv.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AdminEmpresasWebTest extends WebTestBase {
    @Test void adminPrincipalLista() throws Exception {mvc.perform(get("/api/Admin/Empresas").header("Authorization","Bearer "+tokenDe("administrador"))).andExpect(status().isOk());}
    @Test void adminLojaNegado() throws Exception {mvc.perform(get("/api/Admin/Empresas").header("Authorization","Bearer "+tokenDaEmpresa("u","loja","administrador","aprovada"))).andExpect(status().isForbidden()).andExpect(jsonPath("message").value("Acesso negado: módulo restrito ao Administrador Geral da Plataforma."));}
    @ParameterizedTest @ValueSource(strings={"gerente","atendente","caixa"}) void perfisNegados(String p) throws Exception {mvc.perform(get("/api/Admin/Empresas").header("Authorization","Bearer "+tokenDe(p))).andExpect(status().isForbidden());}
    @Test void semSessao() throws Exception {mvc.perform(get("/api/Admin/Empresas")).andExpect(status().isUnauthorized());}
}
