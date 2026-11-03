package br.com.quack.pdv.web;
import br.com.quack.pdv.empresa.web.EmpresaRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class EmpresaCadastrosWebTest extends WebTestBase {
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente","caixa"})
    void qualquerPerfilLeEmpresa(String perfil) throws Exception { when(empresas.obter("empresa-principal")).thenReturn(new EmpresaRequest()); mvc.perform(get("/api/Empresa").header("Authorization","Bearer "+tokenDe(perfil))).andExpect(status().isOk()).andExpect(jsonPath("data.emailSmtpPassword").value("")); }
    @Test void empresaSemSessao() throws Exception { mvc.perform(get("/api/Empresa")).andExpect(status().isUnauthorized()); }
    @Test void somenteAdministradorSalvaEmpresa() throws Exception { when(empresas.salvar(eq("empresa-principal"),any())).thenReturn(new EmpresaRequest()); mvc.perform(put("/api/Empresa").header("Authorization","Bearer "+tokenDe("administrador")).contentType("application/json").content("{}")).andExpect(status().isOk()); }
    @ParameterizedTest @ValueSource(strings={"gerente","atendente","caixa"})
    void perfilNaoAdminRecusado(String perfil) throws Exception { mvc.perform(put("/api/Empresa").header("Authorization","Bearer "+tokenDe(perfil)).contentType("application/json").content("{}")).andExpect(status().isForbidden()); }
    @ParameterizedTest @ValueSource(strings={"administrador","gerente","atendente","caixa"})
    void qualquerPerfilLeTema(String perfil) throws Exception { mvc.perform(get("/api/company-theme").header("Authorization","Bearer "+tokenDe(perfil))).andExpect(status().isOk()).andExpect(jsonPath("message").value("Sem tema personalizado.")); }
    @ParameterizedTest @ValueSource(strings={"administrador","gerente"})
    void gestorSalvaTema(String perfil) throws Exception { mvc.perform(put("/api/company-theme").header("Authorization","Bearer "+tokenDe(perfil)).contentType("application/json").content("{}")).andExpect(status().isOk()); verify(temas).salvar("empresa-principal","usr-1",null,null); }
    @ParameterizedTest @ValueSource(strings={"atendente","caixa"})
    void operadorNaoSalvaTema(String perfil) throws Exception { mvc.perform(put("/api/company-theme").header("Authorization","Bearer "+tokenDe(perfil)).contentType("application/json").content("{}")).andExpect(status().isForbidden()); }
    @ParameterizedTest @ValueSource(strings={"crt", "codigoUfIbge", "ambienteFiscal"})
    void bytesDotnetRejeitamOverflow(String campo) throws Exception {
        for (String valor : new String[]{"-1","256","1.5","null"}) {
            mvc.perform(put("/api/Empresa").header("Authorization","Bearer "+tokenDe("administrador")).contentType("application/json").content("{\""+campo+"\":"+valor+"}"))
                .andExpect(status().isBadRequest());
        }
        verify(empresas,never()).salvar(any(),any());
    }
    @Test void empresaUsaIdentidadeAutenticada() throws Exception { mvc.perform(get("/api/Empresa?companyId=empresa-principal").header("Authorization","Bearer "+tokenDaEmpresa("u-loja","loja","caixa","aprovada"))).andExpect(status().isOk()); verify(empresas).obter("loja"); }
}
