package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.usuario.app.ValidadorSupervisor;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import br.com.quack.pdv.venda.cancelamento.domain.CancelamentoResultado;
import br.com.quack.pdv.venda.cancelamento.domain.CorrecaoResultado;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Cancelamento, trilha e correcao (5.2/5.3): perfis, 401, mapa de codigo para HTTP e mensagens do .NET. */
class CancelamentoWebTest extends WebTestBase {

    private static final String BASE = "/api/HistoricoVendas";
    private static final String JSON = "application/json";
    private static final String CANCELAR = "{\"supervisorId\":\"usr-g\",\"supervisorPassword\":\"ok\","
            + "\"justificativa\":\"Cliente desistiu.\",\"terminal\":\" PDV-1 \"}";
    private static final String CORRIGIR_COM_GERENTE = "{\"produtosParaRemover\":[\"A\"],\"justificativa\":"
            + "\"Item por engano\",\"supervisorId\":\"usr-g\",\"supervisorPassword\":\"ok\"}";
    private static final String CORRIGIR_SEM_GERENTE = "{\"produtosParaRemover\":[\"A\"],\"justificativa\":"
            + "\"Item por engano\"}";

    private static final UsuarioDto GERENTE = new UsuarioDto("usr-g", "empresa-principal", "", "Gil Gerente", "", "",
            "gerente", "ativo", "", "", false, List.of());

    private String auth(String perfil, String... adicionais) {
        return "Bearer " + tokenDe(perfil, adicionais);
    }

    @BeforeEach
    void gerenteValido() {
        when(validadorSupervisor.validar("usr-g", "ok", "empresa-principal"))
                .thenReturn(new ValidadorSupervisor.Resultado(true, "Gerente validado com sucesso.", GERENTE));
        when(validadorSupervisor.validar("usr-g", "errada", "empresa-principal"))
                .thenReturn(new ValidadorSupervisor.Resultado(false, "Senha do gerente incorreta.", null));
    }

    private static CancelamentoResultado ok() {
        return new CancelamentoResultado(true, "Venda cancelada e mercadorias estornadas ao estoque com sucesso.", 2,
                "03/11/2026 10:00:00", null, null, null, new BigDecimal("6.00"));
    }

    // ---------------------------------------------------------------- 401 e perfis

    @Test
    void semSessaoDa401EmTodasAsRotas() throws Exception {
        mvc.perform(post(BASE + "/1/cancelar").contentType(JSON).content(CANCELAR)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/1/corrigir").contentType(JSON).content(CORRIGIR_COM_GERENTE))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/cancelamentos")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/cancelamentos/auditoria")).andExpect(status().isUnauthorized());
        verify(cancelamentoVenda, never()).cancelar(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"estoquista", "financeiro"})
    void cancelarNaoAceitaPerfisForaDaLista(String perfil) throws Exception {
        mvc.perform(post(BASE + "/1/cancelar").header("Authorization", auth(perfil)).contentType(JSON)
                .content(CANCELAR)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @CsvSource({"administrador,200", "gerente,200", "atendente,403", "caixa,403", "estoquista,403"})
    void trilhaCompletaSoParaAdministradorEGerente(String perfil, int esperado) throws Exception {
        when(cancelamentoConsultas.trilha(any(), any(), any(), any())).thenReturn(List.of());

        mvc.perform(get(BASE + "/cancelamentos/auditoria").header("Authorization", auth(perfil)))
                .andExpect(status().is(esperado));
    }

    @ParameterizedTest
    @CsvSource({"administrador,200", "gerente,200", "atendente,200", "caixa,200", "estoquista,403"})
    void listaDeCancelamentosAceitaOsQuatroPerfis(String perfil, int esperado) throws Exception {
        when(cancelamentoConsultas.cancelamentos(any(), any(), any())).thenReturn(List.of());

        mvc.perform(get(BASE + "/cancelamentos").header("Authorization", auth(perfil)))
                .andExpect(status().is(esperado));
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente", "caixa", "financeiro"})
    void corrigirAceitaOsCincoPerfis(String perfil) throws Exception {
        when(correcaoVenda.corrigir(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CorrecaoResultado("1", "2", "x", "Gil Gerente", 1, "6,00", false));

        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth(perfil)).contentType(JSON)
                .content(CORRIGIR_COM_GERENTE)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- cancelar

    @Test
    void cancelarOkDevolveOsCamposDaDotNet() throws Exception {
        when(cancelamentoVenda.cancelar(any())).thenReturn(ok());

        mvc.perform(post(BASE + "/15040/cancelar").header("Authorization", auth("caixa")).contentType(JSON)
                .content(CANCELAR)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Venda cancelada e mercadorias estornadas ao estoque com sucesso."))
                .andExpect(jsonPath("$.data.saleNumber").value("15040"))
                .andExpect(jsonPath("$.data.canceladoEm").value("03/11/2026 10:00:00"))
                .andExpect(jsonPath("$.data.supervisorNome").value("Gil Gerente"))
                .andExpect(jsonPath("$.data.itensEstornados").value(2))
                .andExpect(jsonPath("$.data.fiadoEstornado").value(6.0));
        verify(cancelamentoVenda, never()).registrarNegada(any(), any(), any());
    }

    @Test
    void justificativaCurtaDa400GravaNegadaENaoValidaOGerente() throws Exception {
        mvc.perform(post(BASE + "/15040/cancelar").header("Authorization", auth("caixa")).contentType(JSON)
                .content("{\"supervisorId\":\"usr-g\",\"supervisorPassword\":\"ok\",\"justificativa\":\" abc \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe uma justificativa de no mínimo 5 caracteres."));

        verify(cancelamentoVenda).registrarNegada(any(), eq("Informe uma justificativa de no mínimo 5 caracteres."),
                eq(null));
        verify(validadorSupervisor, never()).validar(anyString(), anyString(), anyString());
        verify(cancelamentoVenda, never()).cancelar(any());
    }

    @Test
    void senhaErradaDa403GravaNegadaESemCancelar() throws Exception {
        mvc.perform(post(BASE + "/15040/cancelar").header("Authorization", auth("caixa")).contentType(JSON)
                .content("{\"supervisorId\":\"usr-g\",\"supervisorPassword\":\"errada\","
                        + "\"justificativa\":\"Cliente desistiu.\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Senha do gerente incorreta."));

        verify(cancelamentoVenda).registrarNegada(any(), eq("Senha do gerente incorreta."), eq(null));
        verify(cancelamentoVenda, never()).cancelar(any());
    }

    @ParameterizedTest
    @CsvSource({"NAO_ENCONTRADA,404", "JA_CANCELADA,409", "STATUS_INVALIDO,409", "NOTA_AUTORIZADA,409",
            "NOTA_PENDENTE,409", "ERRO,400", "OUTRO,400"})
    void mapaDeCodigoParaHttp(String codigo, int http) throws Exception {
        when(cancelamentoVenda.cancelar(any())).thenReturn(CancelamentoResultado.recusa("Recusada.", codigo));

        mvc.perform(post(BASE + "/15040/cancelar").header("Authorization", auth("gerente")).contentType(JSON)
                .content(CANCELAR)).andExpect(status().is(http))
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.message").value("Recusada."))
                .andExpect(jsonPath("$.data.codigo").value(codigo))
                .andExpect(jsonPath("$.data.documentoFiscalId").doesNotExist())
                .andExpect(jsonPath("$.data.documentoModelo").doesNotExist());
        verify(cancelamentoVenda).registrarNegada(any(), eq("Recusada."), eq("Gil Gerente"));
    }

    @Test
    void notaAutorizadaTrazOIdEOModeloDoDocumento() throws Exception {
        when(cancelamentoVenda.cancelar(any())).thenReturn(CancelamentoResultado.recusaDeNota("Tem NFC-e.",
                "NOTA_AUTORIZADA", "df-1", 65));

        mvc.perform(post(BASE + "/15042/cancelar").header("Authorization", auth("caixa")).contentType(JSON)
                .content(CANCELAR)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.documentoFiscalId").value("df-1"))
                .andExpect(jsonPath("$.data.documentoModelo").value(65));
    }

    // ---------------------------------------------------------------- corrigir

    @Test
    void corrigirSemGerenteExigePerfilFinanceiro() throws Exception {
        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth("caixa")).contentType(JSON)
                .content(CORRIGIR_SEM_GERENTE)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Apenas usuários com perfil financeiro podem corrigir vendas."));
        verify(correcaoVenda, never()).corrigir(any(), any(), any(), any(), any(), any());
    }

    @Test
    void corrigirSemGerenteComPerfilFinanceiroAdicionalUsaOProprioUsuarioComoResponsavel() throws Exception {
        when(correcaoVenda.corrigir(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CorrecaoResultado("1", "2", "x", "Fulano", 1, "6,00", false));

        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth("gerente", "financeiro"))
                .contentType(JSON).content(CORRIGIR_SEM_GERENTE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Venda #1 corrigida com sucesso. Nova venda: #2."));
        verify(correcaoVenda).corrigir("empresa-principal", "1", List.of("A"), "usr-1", "Fulano", "Item por engano");
    }

    @Test
    void corrigirComGerenteUsaOGerenteComoResponsavelEAFilaFiscalDefineFiscalQueued() throws Exception {
        when(correcaoVenda.corrigir(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CorrecaoResultado("1", "2", "03/11/2026 10:00:00", "Gil Gerente", 1, "6,00", false));
        when(fiscalDaVenda.aposCorrecao("empresa-principal", "sale-empresa-principal-2")).thenReturn(true);

        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth("caixa")).contentType(JSON)
                .content(CORRIGIR_COM_GERENTE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vendaOriginalNumber").value("1"))
                .andExpect(jsonPath("$.data.novaVendaNumber").value("2"))
                .andExpect(jsonPath("$.data.corrigidaEm").value("03/11/2026 10:00:00"))
                .andExpect(jsonPath("$.data.financeiroNome").value("Gil Gerente"))
                .andExpect(jsonPath("$.data.itensRemovidos").value(1))
                .andExpect(jsonPath("$.data.novoTotal").value("6,00"))
                .andExpect(jsonPath("$.data.fiscalQueued").value(true));
        verify(correcaoVenda).corrigir("empresa-principal", "1", List.of("A"), "usr-g", "Gil Gerente",
                "Item por engano");
    }

    @Test
    void corrigirComSenhaDeGerenteErradaDa403() throws Exception {
        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth("caixa")).contentType(JSON)
                .content("{\"produtosParaRemover\":[\"A\"],\"justificativa\":\"Item por engano\","
                        + "\"supervisorId\":\"usr-g\",\"supervisorPassword\":\"errada\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Senha do gerente incorreta."));
    }

    @Test
    void corrigirValidaJustificativaEProdutosNaOrdemDaDotNet() throws Exception {
        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth("caixa")).contentType(JSON)
                .content("{\"produtosParaRemover\":[],\"justificativa\":\"abc\",\"supervisorId\":\"usr-g\","
                        + "\"supervisorPassword\":\"ok\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe uma justificativa de no mínimo 5 caracteres."));
        mvc.perform(post(BASE + "/1/corrigir").header("Authorization", auth("caixa")).contentType(JSON)
                .content("{\"produtosParaRemover\":[],\"justificativa\":\"Item por engano\",\"supervisorId\":"
                        + "\"usr-g\",\"supervisorPassword\":\"ok\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Selecione ao menos um produto para remover."));
        verify(correcaoVenda, never()).corrigir(any(), any(), any(), any(), any(), any());
    }
}
