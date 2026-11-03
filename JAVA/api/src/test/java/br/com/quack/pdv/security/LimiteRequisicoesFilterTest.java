package br.com.quack.pdv.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LimiteRequisicoesFilterTest {

    /** Relogio que anda quando o teste manda. */
    static class RelogioMutavel extends Clock {
        Instant agora = Instant.parse("2026-11-01T12:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return agora; }
    }

    RelogioMutavel relogio;
    QuackProperties props;
    LimiteRequisicoesFilter filtro;

    @BeforeEach
    void preparar() {
        relogio = new RelogioMutavel();
        props = new QuackProperties(); // 240 requisicoes por 60 s
        filtro = new LimiteRequisicoesFilter(props, new ClienteIpResolver(props), new RespostaJson(new ObjectMapper()), relogio);
    }

    MockHttpServletResponse chamar(String metodo, String caminho, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, caminho);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filtro.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void a241aRequisicaoNaJanelaRecebe429ComRetryAfter() throws Exception {
        for (int i = 1; i <= 240; i++) {
            assertThat(chamar("GET", "/api/x", "1.1.1.1").getStatus()).as("req " + i).isEqualTo(200);
        }
        relogio.agora = relogio.agora.plusSeconds(10);
        MockHttpServletResponse res = chamar("GET", "/api/x", "1.1.1.1");
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("50");
        assertThat(res.getContentAsString()).contains("Limite de requisições excedido. Tente novamente em instantes.");
        assertThat(res.getContentType()).startsWith("application/json");
    }

    @Test
    void retryAfterNuncaEMenorQueUm() throws Exception {
        for (int i = 0; i < 240; i++) chamar("GET", "/api/x", "1.1.1.1");
        relogio.agora = relogio.agora.plusMillis(59_900);
        assertThat(chamar("GET", "/api/x", "1.1.1.1").getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void janelaNovaDepoisDoVencimentoZeraAContagem() throws Exception {
        for (int i = 0; i < 241; i++) chamar("GET", "/api/x", "1.1.1.1");
        relogio.agora = relogio.agora.plusSeconds(61);
        assertThat(chamar("GET", "/api/x", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void cadaIpTemSuaPropriaContagem() throws Exception {
        for (int i = 0; i < 241; i++) chamar("GET", "/api/x", "1.1.1.1");
        assertThat(chamar("GET", "/api/x", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void preflightESwaggerNaoContam() throws Exception {
        for (int i = 0; i < 400; i++) {
            assertThat(chamar("OPTIONS", "/api/x", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(chamar("GET", "/swagger-ui/index.html", "1.1.1.1").getStatus()).isEqualTo(200);
            assertThat(chamar("GET", "/v3/api-docs", "1.1.1.1").getStatus()).isEqualTo(200);
        }
        assertThat(chamar("GET", "/api/x", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void limpezaRemoveBaldesVencidosAcimaDe500Entradas() throws Exception {
        for (int i = 0; i < 520; i++) chamar("GET", "/api/x", "10.0." + (i / 250) + "." + (i % 250));
        assertThat(filtro.tamanho()).isGreaterThanOrEqualTo(500);
        relogio.agora = relogio.agora.plusSeconds(120);
        chamar("GET", "/api/x", "9.9.9.9");
        assertThat(filtro.tamanho()).isEqualTo(1);
    }
}
