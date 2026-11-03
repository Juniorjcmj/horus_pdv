package br.com.quack.pdv.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.quack.pdv.sessao.web.SessaoDto;
import br.com.quack.pdv.shared.NegocioException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 7.1: rotas /api/Sessao com o servico mockado. */
class SessaoControllerWebTest extends WebTestBase {

    List<SessaoDto> lista = List.of(
            new SessaoDto("sess-usr-1", "Navegador - Chrome", "Localização indisponível", "1.1.1.1", "Agora mesmo", true, "desktop"),
            new SessaoDto("sess-2", "Navegador - Firefox", "Localização indisponível", "2.2.2.2", "Agora mesmo", false, "mobile"));

    String auth() {
        return "Bearer " + tokenDe("caixa");
    }

    @Test
    void listarMarcaAAtual() throws Exception {
        when(sessoes.listar("usr-1", "sess-usr-1")).thenReturn(lista);
        mvc.perform(get("/api/Sessao").header(HttpHeaders.AUTHORIZATION, auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sessoes obtidas com sucesso."))
                .andExpect(jsonPath("$.data[0].current").value(true))
                .andExpect(jsonPath("$.data[0].lastActive").value("Agora mesmo"))
                .andExpect(jsonPath("$.data[1].current").value(false));
    }

    @Test
    void encerrarOutraDevolveAListaAtualizada() throws Exception {
        when(sessoes.encerrar("usr-1", "sess-2", "sess-usr-1")).thenReturn(lista.subList(0, 1));
        mvc.perform(delete("/api/Sessao/sess-2").header(HttpHeaders.AUTHORIZATION, auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Sessao encerrada com sucesso."))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void encerrarAAtualRecebe400EInexistenteRecebe404() throws Exception {
        when(sessoes.encerrar("usr-1", "sess-usr-1", "sess-usr-1")).thenThrow(
                new NegocioException(HttpStatus.BAD_REQUEST, "A sessão atual não pode ser encerrada por esta ação."));
        mvc.perform(delete("/api/Sessao/sess-usr-1").header(HttpHeaders.AUTHORIZATION, auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A sessão atual não pode ser encerrada por esta ação."));

        when(sessoes.encerrar("usr-1", "nao-existe", "sess-usr-1"))
                .thenThrow(new NegocioException(HttpStatus.NOT_FOUND, "Sessão não encontrada."));
        mvc.perform(delete("/api/Sessao/nao-existe").header(HttpHeaders.AUTHORIZATION, auth()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Sessão não encontrada."));
    }

    @Test
    void encerrarOutras() throws Exception {
        when(sessoes.encerrarOutras("usr-1", "sess-usr-1")).thenReturn(lista.subList(0, 1));
        mvc.perform(delete("/api/Sessao/outras").header(HttpHeaders.AUTHORIZATION, auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Outras sessoes encerradas com sucesso."));
    }
}
