package br.com.quack.pdv.plataforma.monitoramento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.security.RespostaJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MetricasTokenFilterTest {

    private static MetricasTokenFilter filtro(String token) {
        QuackProperties props = new QuackProperties();
        props.getMetrics().setToken(token);
        return new MetricasTokenFilter(props, new RespostaJson(new ObjectMapper()));
    }

    private static MockHttpServletResponse chamar(MetricasTokenFilter f, String caminho, String autorizacao, MockFilterChain cadeia)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", caminho);
        if (autorizacao != null) req.addHeader("Authorization", autorizacao);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, cadeia);
        return res;
    }

    @Test
    @DisplayName("token correto segue a cadeia")
    void tokenCorretoSegueACadeia() throws Exception {
        MockFilterChain cadeia = new MockFilterChain();

        MockHttpServletResponse res = chamar(filtro("segredo"), "/actuator/prometheus", "Bearer segredo", cadeia);

        assertThat(cadeia.getRequest()).isNotNull();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("sem cabecalho ou token errado responde 401 e nao segue a cadeia")
    void tokenAusenteOuErradoDa401() throws Exception {
        for (String auth : new String[] {null, "Bearer errado", "segredo", "Bearer segredox", "Bearer "}) {
            MockFilterChain cadeia = new MockFilterChain();
            MockHttpServletResponse res = chamar(filtro("segredo"), "/actuator/prometheus", auth, cadeia);

            assertThat(res.getStatus()).as("auth=" + auth).isEqualTo(401);
            assertThat(cadeia.getRequest()).isNull();
        }
    }

    @Test
    @DisplayName("token nao configurado responde 404, com ou sem cabecalho")
    void semTokenConfiguradoDa404() throws Exception {
        for (String auth : new String[] {null, "Bearer ", "Bearer qualquer"}) {
            MockFilterChain cadeia = new MockFilterChain();
            MockHttpServletResponse res = chamar(filtro(""), "/actuator/prometheus", auth, cadeia);

            assertThat(res.getStatus()).as("auth=" + auth).isEqualTo(404);
            assertThat(cadeia.getRequest()).isNull();
        }
    }

    @Test
    @DisplayName("outras rotas nao sao tocadas")
    void outrasRotasPassam() throws Exception {
        for (String caminho : new String[] {"/actuator/health", "/api/loja-sync/lotes", "/api/auth/login"}) {
            MockFilterChain cadeia = new MockFilterChain();
            MockHttpServletResponse res = chamar(filtro(""), caminho, null, cadeia);

            assertThat(res.getStatus()).as(caminho).isEqualTo(200);
            assertThat(cadeia.getRequest()).as(caminho).isNotNull();
        }
    }

    @Test
    @DisplayName("maiusculas e barra final tambem sao protegidas")
    void variacoesDoCaminhoSaoProtegidas() throws Exception {
        for (String caminho : new String[] {"/Actuator/Prometheus", "/actuator/prometheus/"}) {
            MockFilterChain cadeia = new MockFilterChain();
            MockHttpServletResponse res = chamar(filtro("segredo"), caminho, null, cadeia);

            assertThat(res.getStatus()).as(caminho).isEqualTo(401);
        }
    }
}
