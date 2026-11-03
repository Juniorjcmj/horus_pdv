package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProdutoWebTest extends WebTestBase {

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente", "caixa", "estoquista"})
    void listarEVencimentosPermitemQualquerAutenticado(String perfil) throws Exception {
        when(produtos.listar("empresa-principal")).thenReturn(List.of());
        mvc.perform(get("/api/Produto").header("Authorization", "Bearer " + tokenDe(perfil)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/Produto/vencimentos").header("Authorization", "Bearer " + tokenDe(perfil)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/Produto/vencimentos/resumo").header("Authorization", "Bearer " + tokenDe(perfil)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caixa", "estoquista"})
    void caixaEEstoquistaNaoCriamEditamPrecificamNemExcluem(String perfil) throws Exception {
        String auth = "Bearer " + tokenDe(perfil);
        mvc.perform(post("/api/Produto").header("Authorization", auth).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/Produto/pr-1").header("Authorization", auth).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/Produto/pr-1/preco-venda").header("Authorization", auth).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/Produto/pr-1").header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void estoquistaPodeAjustarEstoqueAtualizarValidadeELerMovimentacoesMasCaixaNao() throws Exception {
        when(produtos.atualizarValidade(eq("empresa-principal"), eq("pr-1"), any())).thenReturn(true);
        when(produtos.ajustarEstoque(
                        eq("empresa-principal"), eq("pr-1"), eq("uso_interno"), eq(new BigDecimal("1.25")),
                        eq("Degustacao"), any(), any(), any(), any()))
                .thenReturn(true);

        String authEstoquista = "Bearer " + tokenDe("estoquista");
        mvc.perform(put("/api/Produto/pr-1/validade")
                        .header("Authorization", authEstoquista)
                        .contentType("application/json")
                        .content("{\"dataValidade\":\"2027-01-10\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/Produto/pr-1/ajuste-estoque")
                        .header("Authorization", authEstoquista)
                        .contentType("application/json")
                        .content("{\"tipo\":\"uso_interno\",\"quantidade\":1.25,\"motivo\":\"Degustacao\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Uso interno de 1.25 registrada com sucesso."));
        mvc.perform(get("/api/Produto/movimentacoes").header("Authorization", authEstoquista))
                .andExpect(status().isOk());

        String authCaixa = "Bearer " + tokenDe("caixa");
        mvc.perform(put("/api/Produto/pr-1/validade").header("Authorization", authCaixa).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/Produto/pr-1/ajuste-estoque").header("Authorization", authCaixa).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/Produto/movimentacoes").header("Authorization", authCaixa))
                .andExpect(status().isForbidden());
    }

    @Test
    void importarLegadoExigeGerenteOuAdministradorEVerificacaoFiscalNaoEstaMapeada() throws Exception {
        when(produtos.importarCargaLegado("empresa-principal")).thenReturn(-1);
        mvc.perform(post("/api/Produto/importar-legado").header("Authorization", "Bearer " + tokenDe("gerente")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowsAffected").value(-1));

        mvc.perform(post("/api/Produto/importar-legado").header("Authorization", "Bearer " + tokenDe("atendente")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/Produto/importar-legado").header("Authorization", "Bearer " + tokenDe("estoquista")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/Produto/pr-1/verificacao-fiscal").header("Authorization", "Bearer " + tokenDe("administrador")))
                .andExpect(status().isNotFound());
    }
}
