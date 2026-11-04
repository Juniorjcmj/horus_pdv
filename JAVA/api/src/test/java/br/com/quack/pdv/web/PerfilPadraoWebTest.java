package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import br.com.quack.pdv.config.QuackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Sem perfil de modo a API segue como antes: modo padrao e nenhuma rota barrada pelo filtro da nuvem. */
class PerfilPadraoWebTest extends WebTestBase {

    @Autowired QuackProperties props;

    @Test
    void modoPadraoENaoHaFiltroDaNuvem() throws Exception {
        assertThat(props.getModo()).isEqualTo("padrao");
        // sem token: 401 da autenticacao (a rota existe), nao o 404 do filtro da nuvem
        assertThat(mvc.perform(get("/api/HistoricoVendas")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
