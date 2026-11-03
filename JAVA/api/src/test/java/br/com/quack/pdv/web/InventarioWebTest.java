package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.estoque.inventario.app.InventarioResultado;
import br.com.quack.pdv.usuario.app.ValidadorSupervisor;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InventarioWebTest extends WebTestBase {

    private static final String BASE = "/api/Inventario";
    private static final String JSON = "application/json";

    private String auth(String perfil) {
        return "Bearer " + tokenDe(perfil);
    }

    @Test
    void semSessaoDa401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void atendenteECaixaNaoAcessam() throws Exception {
        for (String perfil : List.of("atendente", "caixa")) {
            mvc.perform(get(BASE).header("Authorization", auth(perfil))).andExpect(status().isForbidden());
            mvc.perform(post(BASE).header("Authorization", auth(perfil)).contentType(JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void estoquistaNaoRecontaAplicaCancelaNemVeTrilha() throws Exception {
        String a = auth("estoquista");
        mvc.perform(post(BASE + "/i1/recontar").header("Authorization", a).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/i1/aplicar").header("Authorization", a).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/i1/cancelar").header("Authorization", a).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/i1/trilha").header("Authorization", a)).andExpect(status().isForbidden());
    }

    @Test
    void estoquistaListaEConsultaSemValores() throws Exception {
        when(inventarioConsultas.listar("empresa-principal", null)).thenReturn(List.of());
        when(inventarioConsultas.obter("empresa-principal", "i1", false)).thenReturn(Optional.of(Map.of("resumo", 1)));
        String a = auth("estoquista");
        mvc.perform(get(BASE).header("Authorization", a)).andExpect(status().isOk());
        mvc.perform(get(BASE + "/i1").header("Authorization", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Inventário."));
        verify(inventarioConsultas).obter("empresa-principal", "i1", false);
    }

    @Test
    void gerenteConsultaComValores() throws Exception {
        when(inventarioConsultas.obter("empresa-principal", "i1", true)).thenReturn(Optional.of(Map.of("resumo", 1)));
        mvc.perform(get(BASE + "/i1").header("Authorization", auth("gerente"))).andExpect(status().isOk());
        verify(inventarioConsultas).obter("empresa-principal", "i1", true);
    }

    @Test
    void consultaInexistenteDa404ComCodigo() throws Exception {
        when(inventarioConsultas.obter(any(), any(), anyBoolean())).thenReturn(Optional.empty());
        mvc.perform(get(BASE + "/x").header("Authorization", auth("gerente")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Inventário não encontrado."))
                .andExpect(jsonPath("$.data.codigo").value("NAO_ENCONTRADO"));
    }

    @ParameterizedTest
    @CsvSource({
        "NAO_ENCONTRADO,404", "CODIGO_DESCONHECIDO,404",
        "ESCOPO_SOBREPOSTO,409", "INVENTARIO_ENCERRADO,409", "JA_APLICADO,409", "JA_ANULADA,409", "OCUPADO,409",
        "SEM_PERMISSAO,403", "FORA_DO_ESCOPO,422",
        "QUANTIDADE_INVALIDA,400", "MOTIVO_OBRIGATORIO,400", "ESCOPO_VAZIO,400"
    })
    void mapaDeCodigoParaHttp(String codigo, int http) throws Exception {
        when(inventarios.contar(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(InventarioResultado.falha(codigo, "msg " + codigo));
        mvc.perform(post(BASE + "/i1/contagens").header("Authorization", auth("estoquista"))
                        .contentType(JSON).content("{\"produtoId\":\"p\",\"quantidade\":1}"))
                .andExpect(status().is(http))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.codigo").value(codigo));
    }

    @Test
    void recusaComDetalheInclui() throws Exception {
        when(inventarios.abrir(any(), any(), any(), any(), any(), any()))
                .thenReturn(InventarioResultado.falha("ESCOPO_SOBREPOSTO", "Sobreposto", Map.of("numeroAberto", 3)));
        mvc.perform(post(BASE).header("Authorization", auth("gerente")).contentType(JSON).content("{\"escopo\":\"loja\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.detalhe.numeroAberto").value(3));
    }

    @Test
    void justificativaCurtaRegistraNegadaESemChamarSupervisor() throws Exception {
        mvc.perform(post(BASE + "/i1/aplicar").header("Authorization", auth("gerente")).contentType(JSON)
                        .content("{\"supervisorId\":\"s\",\"supervisorPassword\":\"x\",\"justificativa\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe uma justificativa de no mínimo 5 caracteres."))
                .andExpect(jsonPath("$.data.codigo").value("JUSTIFICATIVA_CURTA"));
        verify(inventarios).registrarNegada(eq("empresa-principal"), eq("i1"), any(), eq("s"), any());
        verify(validadorSupervisor, never()).validar(any(), any(), any());
    }

    @Test
    void senhaDoSupervisorErradaDa403SemAplicar() throws Exception {
        when(validadorSupervisor.validar("s", "errada", "empresa-principal"))
                .thenReturn(new ValidadorSupervisor.Resultado(false, "Senha do gerente incorreta.", null));
        mvc.perform(post(BASE + "/i1/aplicar").header("Authorization", auth("gerente")).contentType(JSON)
                        .content("{\"supervisorId\":\"s\",\"supervisorPassword\":\"errada\",\"justificativa\":\"Contagem ok\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.codigo").value("SENHA_INVALIDA"));
        verify(inventarios, never()).aplicar(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(inventarios).registrarNegada(eq("empresa-principal"), eq("i1"), any(), eq("s"), eq("Senha do gerente incorreta."));
    }

    @Test
    void aplicacaoOkUsaOSupervisorValidado() throws Exception {
        UsuarioDto sup = new UsuarioDto("usr-s", "empresa-principal", "", "Sup", "s@x", "", "gerente", "ativo", "", "", false, List.of());
        when(validadorSupervisor.validar("usr-s", "ok", "empresa-principal"))
                .thenReturn(new ValidadorSupervisor.Resultado(true, "ok", sup));
        when(inventarios.aplicar(any(), eq("i1"), any(), eq("usr-s"), eq("Sup"), eq("Contagem ok"), eq(true)))
                .thenReturn(InventarioResultado.ok(Map.of("ajustados", 1), "Inventário aplicado."));
        mvc.perform(post(BASE + "/i1/aplicar").header("Authorization", auth("gerente")).contentType(JSON)
                        .content("{\"supervisorId\":\"usr-s\",\"supervisorPassword\":\"ok\",\"justificativa\":\" Contagem ok \",\"zerarNaoContados\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Inventário aplicado."))
                .andExpect(jsonPath("$.data.ajustados").value(1));
        verify(inventarios, never()).registrarNegada(any(), any(), any(), any(), any());
    }

    @Test
    void aplicacaoRecusadaPeloServicoVaiParaATrilha() throws Exception {
        UsuarioDto sup = new UsuarioDto("usr-s", "empresa-principal", "", "Sup", "s@x", "", "gerente", "ativo", "", "", false, List.of());
        when(validadorSupervisor.validar("usr-s", "ok", "empresa-principal"))
                .thenReturn(new ValidadorSupervisor.Resultado(true, "ok", sup));
        when(inventarios.aplicar(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(InventarioResultado.falha("JA_APLICADO", "Este inventário já foi aplicado."));
        mvc.perform(post(BASE + "/i1/aplicar").header("Authorization", auth("gerente")).contentType(JSON)
                        .content("{\"supervisorId\":\"usr-s\",\"supervisorPassword\":\"ok\",\"justificativa\":\"Contagem ok\"}"))
                .andExpect(status().isConflict());
        verify(inventarios).registrarNegada(eq("empresa-principal"), eq("i1"), any(), eq("usr-s"), eq("Este inventário já foi aplicado."));
    }
}
