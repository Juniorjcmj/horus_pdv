package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import br.com.quack.pdv.config.QuackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

/** Perfil nuvem (D1): so /api/loja-sync/** e /actuator/health existem; o resto e 404 ate com token valido. */
@ActiveProfiles({"test", "nuvem"})
class NuvemWebTest extends WebTestBase {

    @Autowired QuackProperties props;
    @Autowired Environment env;

    @Test
    void historicoVendasResponde404ComESemToken() throws Exception {
        assertThat(mvc.perform(get("/api/HistoricoVendas")).andReturn().getResponse().getStatus()).isEqualTo(404);
        String token = tokenDe("administrador");
        assertThat(mvc.perform(get("/api/HistoricoVendas").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/Auth/login").contentType("application/json").content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void healthResponde200() throws Exception {
        assertThat(mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void rotasDeSincronizacaoPassamPeloFiltro() throws Exception {
        // sem rota mapeada ainda (grupo 2), mas o filtro nao as barra: sem token a cadeia de seguranca responde 401
        assertThat(mvc.perform(get("/api/loja-sync/situacao")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void perfilDesligaFilaFiscalEMinimoDeAgendamento() {
        assertThat(props.getModo()).isEqualTo("nuvem");
        assertThat(env.getProperty("quack.fiscal.fila-minima.ativa", Boolean.class)).isFalse();
        assertThat(env.getProperty("quack.lote.retentativa.ativa", Boolean.class)).isFalse();
        assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/nuvem");
        assertThat(env.getProperty("spring.flyway.table")).isEqualTo("flyway_quack_sync");
    }
}
