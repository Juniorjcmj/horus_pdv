package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.venda.caixa.app.CaixaComandos;
import br.com.quack.pdv.venda.caixa.domain.CaixaStatusDto;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class CaixaWebTest extends WebTestBase {

    private static final String BASE = "/api/Caixa";
    private static final String JSON = "application/json";

    private static CaixaStatusDto statusDto(boolean replay) {
        return new CaixaStatusDto("aberto", true, "", "2026-11-03T10:00:00.0000000-03:00", null, null, List.of(),
                List.of(), replay);
    }

    private String auth(String perfil) {
        return "Bearer " + tokenDe(perfil);
    }

    @Test
    void semSessaoDa401EnaoChamaOServico() throws Exception {
        mvc.perform(get(BASE + "/status")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/abrir").contentType(JSON).content("{}")).andExpect(status().isUnauthorized());
        verify(caixaService, never()).status(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"administrador", "gerente", "atendente", "caixa", "estoquista"})
    void qualquerPerfilAutenticadoAcessaAsRotas(String perfil) throws Exception {
        when(caixaService.status(any())).thenReturn(statusDto(false));
        mvc.perform(get(BASE + "/status").header("Authorization", auth(perfil)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Status do caixa obtido com sucesso."))
                .andExpect(jsonPath("$.data.canSell").value(true))
                .andExpect(jsonPath("$.data.isReplay").value(false));
    }

    @Test
    void sessaoInexistenteOuDeOutroOperadorDa404() throws Exception {
        when(caixaService.sessao(any(), eq("cx-1"))).thenReturn(Optional.empty());
        mvc.perform(get(BASE + "/sessao/cx-1").header("Authorization", auth("caixa")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Turno de caixa não encontrado."));
    }

    @Test
    void movimentosUsamAJanelaPadraoEValidamOPeriodo() throws Exception {
        when(caixaService.hoje()).thenReturn(LocalDate.of(2026, 11, 3));
        when(caixaService.movimentosPorPeriodo(any(), eq(LocalDate.of(2026, 10, 4)), eq(LocalDate.of(2026, 11, 3))))
                .thenReturn(List.of());
        mvc.perform(get(BASE + "/movimentos").header("Authorization", auth("gerente")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Movimentos de caixa obtidos com sucesso."));
        mvc.perform(get(BASE + "/movimentos?de=2026-02-01&ate=2026-01-01").header("Authorization", auth("gerente")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Período inválido: a data final é anterior à inicial."));
    }

    @Test
    void abrirDevolveAsMensagensDeSucessoEDeRepeticao() throws Exception {
        ArgumentCaptor<CaixaComandos.Abrir> cmd = ArgumentCaptor.forClass(CaixaComandos.Abrir.class);
        when(caixaService.abrir(cmd.capture(), any(), any())).thenReturn(statusDto(false), statusDto(true));
        String corpo = "{\"eventId\":\"e1\",\"openingAmount\":\"10,00\"}";
        mvc.perform(post(BASE + "/abrir").header("Authorization", auth("caixa")).contentType(JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Caixa aberto com sucesso."));
        mvc.perform(post(BASE + "/abrir").header("Authorization", auth("caixa")).contentType(JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Abertura de caixa já processada anteriormente (idempotente)."))
                .andExpect(jsonPath("$.data.isReplay").value(true));
        assertThat(cmd.getValue().openingAmount()).isEqualTo("10,00");
        assertThat(cmd.getValue().eventId()).isEqualTo("e1");
    }

    @Test
    void abrirSemValorUsaOPadraoDoDotNet() throws Exception {
        ArgumentCaptor<CaixaComandos.Abrir> cmd = ArgumentCaptor.forClass(CaixaComandos.Abrir.class);
        when(caixaService.abrir(cmd.capture(), any(), any())).thenReturn(statusDto(false));
        mvc.perform(post(BASE + "/abrir").header("Authorization", auth("caixa")).contentType(JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(cmd.getValue().openingAmount()).isEqualTo("0,00");
    }

    @Test
    void valorNuloNoCampoObrigatorioDa400DeValidacao() throws Exception {
        mvc.perform(post(BASE + "/fechar").header("Authorization", auth("caixa")).contentType(JSON)
                        .content("{\"closingAmount\":null,\"note\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors.ClosingAmount[0]").value("The ClosingAmount field is required."))
                .andExpect(jsonPath("$.errors.Note[0]").value("The Note field is required."));
        verify(caixaService, never()).fechar(any(), any(), any());
    }

    @Test
    void erroDeNegocioDa400ComAMensagem() throws Exception {
        when(caixaService.fechar(any(), any(), any())).thenThrow(
                new NegocioException(HttpStatus.BAD_REQUEST, "Você não possui caixa aberto para fechamento."));
        mvc.perform(post(BASE + "/fechar").header("Authorization", auth("caixa")).contentType(JSON)
                        .content("{\"closingAmount\":\"1,00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Você não possui caixa aberto para fechamento."));
    }

    @Test
    void conflitoDeIdempotenciaDa409() throws Exception {
        String mensagem = "Conflito de idempotência: o EventId 'e1' já foi processado anteriormente com um payload diferente.";
        when(caixaService.registrarMovimento(any(), any(), any()))
                .thenThrow(new NegocioException(HttpStatus.CONFLICT, mensagem));
        mvc.perform(post(BASE + "/movimento").header("Authorization", auth("caixa")).contentType(JSON)
                        .content("{\"eventId\":\"e1\",\"tipo\":\"Sangria\",\"valor\":\"5,00\",\"motivo\":\"abc\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(mensagem));
    }

    @Test
    void movimentoNovoDa201ERepeticaoDa200() throws Exception {
        when(caixaService.registrarMovimento(any(), any(), any())).thenReturn(statusDto(false), statusDto(true));
        String corpo = "{\"tipo\":\"Reforco\",\"valor\":\"5,00\",\"motivo\":\"abc\"}";
        mvc.perform(post(BASE + "/movimento").header("Authorization", auth("caixa")).contentType(JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Movimento de caixa registrado com sucesso."));
        mvc.perform(post(BASE + "/movimento").header("Authorization", auth("caixa")).contentType(JSON).content(corpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Movimento de caixa já processado anteriormente (idempotente)."));
    }

    @Test
    void fecharDevolveAsMensagensDeSucessoEDeRepeticao() throws Exception {
        when(caixaService.fechar(any(), any(), any())).thenReturn(statusDto(false), statusDto(true));
        String corpo = "{\"closingAmount\":\"1,00\"}";
        mvc.perform(post(BASE + "/fechar").header("Authorization", auth("caixa")).contentType(JSON).content(corpo))
                .andExpect(jsonPath("$.message").value("Caixa fechado com sucesso."));
        mvc.perform(post(BASE + "/fechar").header("Authorization", auth("caixa")).contentType(JSON).content(corpo))
                .andExpect(jsonPath("$.message").value("Fechamento de caixa já processado anteriormente (idempotente)."));
    }
}
