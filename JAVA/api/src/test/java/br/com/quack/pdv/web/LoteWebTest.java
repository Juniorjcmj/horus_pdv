package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoteWebTest extends WebTestBase {

    @Test
    void semSessaoDa401() throws Exception {
        mvc.perform(get("/api/Produto/lotes/alertas")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente", "caixa", "estoquista"})
    void consultasPermitemQualquerAutenticado(String perfil) throws Exception {
        when(loteService.listarCategorias("empresa-principal")).thenReturn(List.of());
        mvc.perform(get("/api/Produto/lotes/categorias").header("Authorization", "Bearer " + tokenDe(perfil)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Prazos de validade por categoria obtidos com sucesso."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"caixa"})
    void caixaNaoRegistraLote(String perfil) throws Exception {
        mvc.perform(post("/api/Produto/lotes/registrar").header("Authorization", "Bearer " + tokenDe(perfil))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void estoquistaRegistraLoteMasNaoMudaModoNemCategoria() throws Exception {
        String auth = "Bearer " + tokenDe("estoquista");
        mvc.perform(post("/api/Produto/lotes/registrar").header("Authorization", auth)
                        .contentType("application/json")
                        .content("{\"produtoId\":\"p\",\"dataValidade\":\"2027-01-01\",\"quantidade\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Lote registrado com sucesso."));
        mvc.perform(put("/api/Produto/lotes/fefo/modo").header("Authorization", auth)
                        .contentType("application/json").content("{\"modo\":\"ativo\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/Produto/lotes/categorias/c1").header("Authorization", auth)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void gerenteMudaModoECategoriaInexistenteDa404() throws Exception {
        String auth = "Bearer " + tokenDe("gerente");
        mvc.perform(put("/api/Produto/lotes/fefo/modo").header("Authorization", auth)
                        .contentType("application/json").content("{\"modo\":\"ativo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Modo da baixa por lote atualizado."));
        when(loteService.salvarCategoria(eq("empresa-principal"), eq("x"), any())).thenReturn(false);
        mvc.perform(put("/api/Produto/lotes/categorias/x").header("Authorization", auth)
                        .contentType("application/json").content("{\"prazoPadraoDias\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Categoria não encontrada."));
    }
}
