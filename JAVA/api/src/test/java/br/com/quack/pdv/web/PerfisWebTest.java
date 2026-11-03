package br.com.quack.pdv.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 6.2: @PerfisPermitidos (perfil principal ou adicional, sem diferenciar maiusculas, 403 com a mensagem padrao). */
class PerfisWebTest extends WebTestBase {

    String auth(String role, String... adicionais) {
        return "Bearer " + tokenDe(role, adicionais);
    }

    @Test
    void perfilPermitidoPassa() throws Exception {
        mvc.perform(get("/api/Teste/admin").header(HttpHeaders.AUTHORIZATION, auth("administrador")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/Teste/admin").header(HttpHeaders.AUTHORIZATION, auth("Administrador")))
                .andExpect(status().isOk());
    }

    @Test
    void perfilNegadoRecebe403ComMensagem() throws Exception {
        mvc.perform(get("/api/Teste/admin").header(HttpHeaders.AUTHORIZATION, auth("caixa")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Usuário sem permissão para esta ação."));
    }

    @Test
    void gerenteComPerfilAdicionalFinanceiroPassaNaRotaFinanceira() throws Exception {
        mvc.perform(get("/api/Teste/financeiro").header(HttpHeaders.AUTHORIZATION, auth("gerente", "financeiro")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/Teste/financeiro").header(HttpHeaders.AUTHORIZATION, auth("gerente")))
                .andExpect(status().isForbidden());
    }
}
