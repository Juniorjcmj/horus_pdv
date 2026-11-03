package br.com.quack.pdv.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.auth.app.RecuperacaoSenhaService;
import br.com.quack.pdv.shared.NegocioException;
import br.com.quack.pdv.usuario.app.UsuarioService;
import br.com.quack.pdv.usuario.web.UsuarioDto;
import br.com.quack.pdv.usuario.web.UsuarioRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** 8.2: rotas /api/Usuario (perfis por rota, 201 ao criar, 404 de outra empresa) e 9.3: resetar-senha. */
class UsuarioControllerWebTest extends WebTestBase {

    static UsuarioDto dto() {
        return new UsuarioDto("usr-9", "empresa-principal", "11144477735", "Joao", "joao@x.com", "11", "caixa", "ativo",
                "2026-11-01", "-", true, List.of());
    }

    String auth(String role, String... adicionais) {
        return "Bearer " + tokenDe(role, adicionais);
    }

    static final String CORPO = "{\"cpf\":\"11144477735\",\"name\":\"Joao\",\"email\":\"joao@x.com\",\"phone\":\"11\","
            + "\"role\":\"caixa\",\"status\":\"ativo\",\"password\":\"Senha#1234\",\"perfisAdicionais\":[\"financeiro\"]}";

    @Test
    void caixaNaoListaUsuariosMasVeOsSupervisores() throws Exception {
        mvc.perform(get("/api/Usuario").header(HttpHeaders.AUTHORIZATION, auth("caixa")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Usuário sem permissão para esta ação."));

        when(usuarios.supervisores("empresa-principal"))
                .thenReturn(List.of(new UsuarioService.Supervisor("usr-1", "Ana", "gerente")));
        for (String perfil : new String[] {"caixa", "atendente", "gerente", "administrador"}) {
            mvc.perform(get("/api/Usuario/supervisores").header(HttpHeaders.AUTHORIZATION, auth(perfil)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Supervisores obtidos com sucesso."))
                    .andExpect(jsonPath("$.data[0].id").value("usr-1"))
                    .andExpect(jsonPath("$.data[0].name").value("Ana"))
                    .andExpect(jsonPath("$.data[0].role").value("gerente"));
        }
        mvc.perform(get("/api/Usuario/supervisores").header(HttpHeaders.AUTHORIZATION, auth("estoquista")))
                .andExpect(status().isForbidden());
    }

    @Test
    void gerenteEAdministradorListam() throws Exception {
        when(usuarios.listar("empresa-principal")).thenReturn(List.of(dto()));
        for (String perfil : new String[] {"gerente", "administrador"}) {
            mvc.perform(get("/api/Usuario").header(HttpHeaders.AUTHORIZATION, auth(perfil))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Usuarios obtidos com sucesso."))
                    .andExpect(jsonPath("$.data[0].id").value("usr-9"));
        }
        mvc.perform(get("/api/Usuario").header(HttpHeaders.AUTHORIZATION, auth("atendente"))).andExpect(status().isForbidden());
    }

    @Test
    void criarDevolve201ERespeitaQuemPodeDarPerfisAdicionais() throws Exception {
        when(usuarios.criar(any(), anyString(), eq(false))).thenReturn(dto());
        mvc.perform(post("/api/Usuario").header(HttpHeaders.AUTHORIZATION, auth("gerente"))
                        .contentType(MediaType.APPLICATION_JSON).content(CORPO))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Usuario criado com sucesso."));

        when(usuarios.criar(any(), anyString(), eq(true))).thenReturn(dto());
        mvc.perform(post("/api/Usuario").header(HttpHeaders.AUTHORIZATION, auth("administrador"))
                        .contentType(MediaType.APPLICATION_JSON).content(CORPO)).andExpect(status().isCreated());
        ArgumentCaptor<UsuarioRequest> corpo = ArgumentCaptor.forClass(UsuarioRequest.class);
        verify(usuarios, org.mockito.Mockito.atLeastOnce()).criar(corpo.capture(), anyString(), eq(true));
        org.assertj.core.api.Assertions.assertThat(corpo.getValue().perfisAdicionais()).containsExactly("financeiro");
    }

    @Test
    void criarComDadosInvalidosOuDuplicadosRecebe400() throws Exception {
        when(usuarios.criar(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new NegocioException(HttpStatus.BAD_REQUEST, "Já existe um usuário com este CPF nesta empresa."));
        mvc.perform(post("/api/Usuario").header(HttpHeaders.AUTHORIZATION, auth("gerente"))
                        .contentType(MediaType.APPLICATION_JSON).content(CORPO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Já existe um usuário com este CPF nesta empresa."));
    }

    @Test
    void atualizarDeOutraEmpresaRecebe404() throws Exception {
        when(usuarios.atualizar(eq("usr-9"), any(), eq("empresa-principal"), eq(false))).thenReturn(Optional.empty());
        mvc.perform(put("/api/Usuario/usr-9").header(HttpHeaders.AUTHORIZATION, auth("gerente"))
                        .contentType(MediaType.APPLICATION_JSON).content(CORPO))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Usuário não encontrado."));

        when(usuarios.atualizar(eq("usr-9"), any(), eq("empresa-principal"), eq(false))).thenReturn(Optional.of(dto()));
        mvc.perform(put("/api/Usuario/usr-9").header(HttpHeaders.AUTHORIZATION, auth("gerente"))
                        .contentType(MediaType.APPLICATION_JSON).content(CORPO))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Usuario atualizado com sucesso."));
    }

    @Test
    void alterarStatus() throws Exception {
        when(usuarios.alterarStatus("usr-9", "inativo", "empresa-principal")).thenReturn(Optional.of(dto()));
        mvc.perform(patch("/api/Usuario/usr-9/status").header(HttpHeaders.AUTHORIZATION, auth("gerente"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"inativo\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Status atualizado com sucesso."));

        when(usuarios.alterarStatus("usr-8", "ativo", "empresa-principal")).thenReturn(Optional.empty());
        mvc.perform(patch("/api/Usuario/usr-8/status").header(HttpHeaders.AUTHORIZATION, auth("gerente"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // ---- resetar-senha (9.3) ----

    RecuperacaoSenhaService.RedefinicaoAdministrativa resultado(OffsetDateTime expira) {
        return new RecuperacaoSenhaService.RedefinicaoAdministrativa(dto(), true, "j***o@x.com", "tok-admin", expira);
    }

    @Test
    void resetarSenhaSemEmailEmProducaoRecebe503() throws Exception {
        when(email.habilitado()).thenReturn(false);
        mvc.perform(post("/api/Usuario/usr-9/resetar-senha").header(HttpHeaders.AUTHORIZATION, auth("gerente")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Reset de senha indisponível. Configure o envio de e-mail."));
        verifyNoInteractions(recuperacao);
    }

    @Test
    void resetarSenhaEnviaOEmailENaoDevolveOToken() throws Exception {
        OffsetDateTime expira = OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.UTC);
        when(email.habilitado()).thenReturn(true);
        when(email.montarUrlRedefinicao("tok-admin")).thenReturn("http://front/?resetToken=tok-admin");
        when(recuperacao.solicitarAdministrativo(eq("usr-9"), eq("empresa-principal"), anyString(), anyString()))
                .thenReturn(Optional.of(resultado(expira)));

        mvc.perform(post("/api/Usuario/usr-9/resetar-senha").header(HttpHeaders.AUTHORIZATION, auth("gerente")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Redefinição de senha gerada com sucesso."))
                .andExpect(jsonPath("$.data.user.id").value("usr-9"))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.maskedEmail").value("j***o@x.com"))
                .andExpect(jsonPath("$.data.resetToken").doesNotExist());
        verify(email).enviarRedefinicaoSenha("joao@x.com", "Quack PDV", "http://front/?resetToken=tok-admin", expira);
    }

    @Test
    void resetarSenhaComFalhaNoEnvioInvalidaOTokenERecebe502() throws Exception {
        OffsetDateTime expira = OffsetDateTime.of(2026, 11, 1, 12, 30, 0, 0, ZoneOffset.UTC);
        when(email.habilitado()).thenReturn(true);
        when(email.montarUrlRedefinicao(anyString())).thenReturn("http://front");
        when(recuperacao.solicitarAdministrativo(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(resultado(expira)));
        doThrow(new IllegalStateException("recusou")).when(email)
                .enviarRedefinicaoSenha(anyString(), anyString(), anyString(), any());

        mvc.perform(post("/api/Usuario/usr-9/resetar-senha").header(HttpHeaders.AUTHORIZATION, auth("gerente")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Não foi possível enviar o e-mail de redefinição de senha."));
        verify(recuperacao).consumir(eq("tok-admin"), anyString(), anyString());
    }

    @Test
    void resetarSenhaDeUsuarioInexistenteRecebe404() throws Exception {
        when(email.habilitado()).thenReturn(true);
        when(recuperacao.solicitarAdministrativo(anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        mvc.perform(post("/api/Usuario/usr-x/resetar-senha").header(HttpHeaders.AUTHORIZATION, auth("gerente")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Usuário não encontrado."));
    }
}
