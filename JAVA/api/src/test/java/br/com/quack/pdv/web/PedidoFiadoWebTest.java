package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.shared.NegocioException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

/** Perfis, 401 e mensagens das rotas /api/Pedido e /api/Fiado (6.1 e 6.2). */
class PedidoFiadoWebTest extends WebTestBase {

    private static final String JSON = "application/json";

    private String auth(String perfil) {
        return "Bearer " + tokenDe(perfil);
    }

    @Test
    void pedidoSemSessaoDa401() throws Exception {
        mvc.perform(post("/api/Pedido").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Pedido")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Pedido/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/Pedido/1/finalizar").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/Pedido/1/cancelar")).andExpect(status().isUnauthorized());
        verify(pedidoService, never()).criar(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"caixa", "estoquista"})
    void criarListarECancelarPedidoSaoNegadosParaCaixaEEstoquista(String perfil) throws Exception {
        mvc.perform(post("/api/Pedido").header("Authorization", auth(perfil)).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/Pedido").header("Authorization", auth(perfil))).andExpect(status().isForbidden());
        mvc.perform(post("/api/Pedido/1/cancelar").header("Authorization", auth(perfil)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente"})
    void listarEcancelarPedidoAceitamOsTresPerfis(String perfil) throws Exception {
        when(pedidoService.listarAbertos(any())).thenReturn(List.of());
        when(pedidoService.cancelar(any(), any())).thenReturn(true);

        mvc.perform(get("/api/Pedido").header("Authorization", auth(perfil))).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Pedidos obtidos com sucesso."));
        mvc.perform(post("/api/Pedido/1/cancelar").header("Authorization", auth(perfil)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Pedido cancelado com sucesso."));
    }

    @Test
    void cancelarPedidoInexistenteOuFechadoDa404() throws Exception {
        when(pedidoService.cancelar(any(), any())).thenReturn(false);

        mvc.perform(post("/api/Pedido/9/cancelar").header("Authorization", auth("atendente")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Pedido não encontrado ou não está mais aberto."));
    }

    @Test
    void consultarPedidoInexistenteDa404EQualquerPerfilConsulta() throws Exception {
        when(pedidoService.obter(any(), any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/Pedido/9").header("Authorization", auth("estoquista")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Pedido não encontrado."));
    }

    @Test
    void finalizarComCaixaFechadoRecusaAntesDeProcurarOPedido() throws Exception {
        org.mockito.Mockito.doThrow(new NegocioException(HttpStatus.BAD_REQUEST,
                "Abra o caixa do dia antes de iniciar vendas.")).when(caixaService).garantirVendaPermitida(any(), any());

        mvc.perform(post("/api/Pedido/1/finalizar").header("Authorization", auth("caixa")).contentType(JSON)
                .content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Abra o caixa do dia antes de iniciar vendas."));
        verify(pedidoService, never()).obter(any(), any());
    }

    @Test
    void criarPedidoComNomeNuloRecusaComoModeloInvalido() throws Exception {
        mvc.perform(post("/api/Pedido").header("Authorization", auth("atendente")).contentType(JSON)
                .content("{\"customerName\":null}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.CustomerName[0]").value("The CustomerName field is required."));
    }

    @Test
    void fiadoSemSessaoDa401() throws Exception {
        mvc.perform(post("/api/Fiado/receber").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Fiado/devedores")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Fiado/resumo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/Fiado/extrato/c1")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gerente", "atendente", "estoquista"})
    void receberFiadoSoParaAdministradorECaixa(String perfil) throws Exception {
        mvc.perform(post("/api/Fiado/receber").header("Authorization", auth(perfil)).contentType(JSON)
                .content("{}")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"atendente", "estoquista"})
    void consultasDeFiadoSaoNegadasForaDeAdministradorGerenteECaixa(String perfil) throws Exception {
        mvc.perform(get("/api/Fiado/devedores").header("Authorization", auth(perfil)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/Fiado/resumo").header("Authorization", auth(perfil)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/Fiado/extrato/c1").header("Authorization", auth(perfil)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "caixa"})
    void gerenteConsultaEAdministradorECaixaTambem(String perfil) throws Exception {
        when(fiadoService.devedores(any(), any())).thenReturn(List.of());

        mvc.perform(get("/api/Fiado/devedores").header("Authorization", auth(perfil))).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Lista de devedores obtida com sucesso."));
    }

    @Test
    void extratoComDataInvalidaDaProblemaDeModelo() throws Exception {
        mvc.perform(get("/api/Fiado/extrato/c1?dataInicio=abc").header("Authorization", auth("caixa")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dataInicio[0]").value("The value 'abc' is not valid."));
    }
}
