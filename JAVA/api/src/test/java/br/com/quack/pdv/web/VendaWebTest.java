package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class VendaWebTest extends WebTestBase {

    private static final String BASE = "/api/HistoricoVendas";
    private static final String JSON = "application/json";
    private static final String CORPO = "{\"customerName\":\"X\",\"totalAmount\":\"3,00\",\"items\":["
            + "{\"productCode\":\"A\",\"quantity\":1,\"unitPrice\":3}],\"payments\":[{\"paymentType\":\"dinheiro\","
            + "\"amount\":3}]}";

    private String auth(String perfil) {
        return "Bearer " + tokenDe(perfil);
    }

    private static VendaRegistroResultado resultado(boolean replay) {
        return new VendaRegistroResultado("15043", "sale-e-15043", null, replay, List.of(), List.of(), List.of(), null,
                null);
    }

    @Test
    void semSessaoDa401() throws Exception {
        mvc.perform(post(BASE).contentType(JSON).content(CORPO)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/1")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/autorizar-preco").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verify(vendaRegistro, never()).registrar(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente", "caixa", "estoquista"})
    void registrarAceitaQualquerPerfilAutenticado(String perfil) throws Exception {
        when(vendaRegistro.registrar(any(), any(), any())).thenReturn(resultado(false));
        when(fiscalDaVenda.aposVenda(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FiscalDaVenda.Resultado(false, false));

        mvc.perform(post(BASE).header("Authorization", auth(perfil)).contentType(JSON).content(CORPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Venda registrada com sucesso."))
                .andExpect(jsonPath("$.data.saleNumber").value("15043"))
                .andExpect(jsonPath("$.data.isReplay").value(false))
                .andExpect(jsonPath("$.data.fiscalQueued").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"estoquista"})
    void perfisSemPermissaoNasRotasComAtributo(String perfil) throws Exception {
        mvc.perform(get(BASE).header("Authorization", auth(perfil))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/1").header("Authorization", auth(perfil))).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/autorizar-preco").header("Authorization", auth(perfil)).contentType(JSON)
                .content("{}")).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente", "caixa"})
    void rotasComAtributoAceitamOsQuatroPerfis(String perfil) throws Exception {
        when(vendaConsulta.listar(any(), any(), any())).thenReturn(List.of());
        mvc.perform(get(BASE).header("Authorization", auth(perfil))).andExpect(status().isOk());
    }

    @Test
    void replayDevolve200() throws Exception {
        when(vendaRegistro.registrar(any(), any(), any())).thenReturn(resultado(true));
        when(fiscalDaVenda.aposVenda(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FiscalDaVenda.Resultado(true, true));

        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON).content(CORPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isReplay").value(true))
                .andExpect(jsonPath("$.data.emitirFiscal").value(true));
    }

    @Test
    void semItensDa400() throws Exception {
        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON)
                .content("{\"items\":[]}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Venda sem itens."));
        verify(vendaRegistro, never()).registrar(any(), any(), any());
    }

    @Test
    void xmlDeContingenciaDoGatewayEhRecusadoAntesDeGravar() throws Exception {
        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON)
                .content(CORPO.replace("{\"customerName\"", "{\"xmlNfceContingencia\":\"<NFe/>\",\"customerName\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A importação da NFC-e de contingência do Gateway ainda "
                        + "não está disponível nesta versão."));
        verify(vendaRegistro, never()).registrar(any(), any(), any());
    }

    @Test
    void caixaBloqueadoOnlineDa400SemRegistrar() throws Exception {
        org.mockito.Mockito.doThrow(new NegocioException(HttpStatus.BAD_REQUEST, "Abra o caixa do dia antes de iniciar vendas."))
                .when(caixaService).garantirVendaPermitida(any(), any());

        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON).content(CORPO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Abra o caixa do dia antes de iniciar vendas."));
        verify(vendaRegistro, never()).registrar(any(), any(), any());
    }

    @Test
    void vendaOfflineComEventIdPassaMesmoComCaixaBloqueado() throws Exception {
        org.mockito.Mockito.doThrow(new NegocioException(HttpStatus.BAD_REQUEST, "bloqueado"))
                .when(caixaService).garantirVendaPermitida(any(), any());
        when(vendaRegistro.registrar(any(), any(), any())).thenReturn(resultado(false));
        when(fiscalDaVenda.aposVenda(any(), any(), any(), any(), any(), any()))
                .thenReturn(new FiscalDaVenda.Resultado(false, false));

        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON)
                .content(CORPO.replace("{\"customerName\"", "{\"eventId\":\"ev1\",\"customerName\"")))
                .andExpect(status().isCreated());
    }

    @Test
    void falhaFiscalDa503ComSaleNumberEVendaIdNoData() throws Exception {
        when(vendaRegistro.registrar(any(), any(), any())).thenReturn(resultado(false));
        when(fiscalDaVenda.aposVenda(any(), any(), any(), any(), any(), any())).thenThrow(new NegocioException(
                HttpStatus.SERVICE_UNAVAILABLE, "Venda guardada; a preparação da nota fiscal será retomada "
                        + "automaticamente.", null, Map.of("saleNumber", "15043", "vendaId", "sale-e-15043")));

        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON).content(CORPO))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Venda guardada; a preparação da nota fiscal será retomada "
                        + "automaticamente."))
                .andExpect(jsonPath("$.data.saleNumber").value("15043"))
                .andExpect(jsonPath("$.data.vendaId").value("sale-e-15043"));
    }

    @Test
    void conflitoDeIdempotenciaDa409() throws Exception {
        when(vendaRegistro.registrar(any(), any(), any())).thenThrow(new NegocioException(HttpStatus.CONFLICT,
                "Conflito de idempotência: o EventId 'e1' já foi processado anteriormente com um payload diferente."));

        mvc.perform(post(BASE).header("Authorization", auth("caixa")).contentType(JSON).content(CORPO))
                .andExpect(status().isConflict());
    }

    @Test
    void detalheEImprimirInexistentesDao404() throws Exception {
        when(vendaConsulta.detalhe(any(), eq("9"))).thenReturn(Optional.empty());
        when(vendaConsulta.imprimir(any(), eq("9"))).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/9").header("Authorization", auth("caixa"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Venda não encontrada."));
        mvc.perform(post(BASE + "/9/imprimir").header("Authorization", auth("estoquista")))
                .andExpect(status().isNotFound());
    }
}
