package br.com.quack.pdv.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClienteIpResolverTest {

    MockHttpServletRequest requisicao(String remoto, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remoto);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void semConfiarUsaOIpDaConexaoEIgnoraXForwardedFor() {
        QuackProperties p = new QuackProperties();
        ClienteIpResolver r = new ClienteIpResolver(p);
        assertThat(r.resolver(requisicao("10.9.9.9", "200.1.1.1, 10.0.0.2"))).isEqualTo("10.9.9.9");
    }

    @Test
    void confiandoUsaOPrimeiroDoXForwardedFor() {
        QuackProperties p = new QuackProperties();
        p.getSecurity().setTrustForwardedHeaders(true);
        ClienteIpResolver r = new ClienteIpResolver(p);
        assertThat(r.resolver(requisicao("10.9.9.9", "200.1.1.1, 10.0.0.2"))).isEqualTo("200.1.1.1");
        assertThat(r.resolver(requisicao("10.9.9.9", "  201.2.2.2 "))).isEqualTo("201.2.2.2");
    }

    @Test
    void confiandoMasSemCabecalhoVoltaParaOIpDaConexao() {
        QuackProperties p = new QuackProperties();
        p.getSecurity().setTrustForwardedHeaders(true);
        ClienteIpResolver r = new ClienteIpResolver(p);
        assertThat(r.resolver(requisicao("10.9.9.9", null))).isEqualTo("10.9.9.9");
        assertThat(r.resolver(requisicao("10.9.9.9", "   "))).isEqualTo("10.9.9.9");
    }

    @Test
    void semIpNenhumUsaOPadrao() {
        ClienteIpResolver r = new ClienteIpResolver(new QuackProperties());
        MockHttpServletRequest sem = requisicao("", null);
        assertThat(r.resolver(sem)).isEqualTo("unknown");
        assertThat(r.resolver(sem, "0.0.0.0")).isEqualTo("0.0.0.0");
    }
}
