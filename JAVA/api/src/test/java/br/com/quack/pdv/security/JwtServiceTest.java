package br.com.quack.pdv.security;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.config.QuackProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    QuackProperties props;
    JwtService jwt;

    @BeforeEach
    void preparar() {
        props = new QuackProperties();
        props.getAuth().setJwtSecret("segredo-de-teste-com-mais-de-32-caracteres-aqui");
        jwt = new JwtService(props, Clock.fixed(T0, ZoneOffset.UTC));
    }

    String token() {
        return jwt.criarToken("usr-1", "emp-1", "Ana", "ana@x.com", "caixa", "sess-1", "10.0.0.1");
    }

    @Test
    void emiteEValidaIdaEVolta() {
        UsuarioAutenticado u = jwt.validarToken(token());
        assertThat(u).isNotNull();
        assertThat(u.id()).isEqualTo("usr-1");
        assertThat(u.companyId()).isEqualTo("emp-1");
        assertThat(u.sessionId()).isEqualTo("sess-1");
        assertThat(u.role()).isEqualTo("caixa");
    }

    @Test
    void claimsEHeaderIguaisAoDotNet() throws Exception {
        String[] p = token().split("\\.");
        JsonNode header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(p[0]));
        assertThat(header.get("alg").asText()).isEqualTo("HS256");
        assertThat(header.get("typ").asText()).isEqualTo("JWT");
        JsonNode c = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(p[1]));
        assertThat(c.fieldNames()).toIterable().containsExactly("iss", "aud", "sub", "id", "companyId", "name", "email",
                "role", "tokenType", "sessionId", "sessionTtl", "ip", "iat", "exp");
        assertThat(c.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(c.get("sessionTtl").asText()).isEqualTo("8h");
        assertThat(c.get("exp").asLong() - c.get("iat").asLong()).isEqualTo(8 * 3600);
        assertThat(token()).doesNotContain("=");
    }

    @Test
    void sessionTtlEExpiracaoSeguemAsHorasConfiguradas() throws Exception {
        props.getAuth().setSessionHours(40); // vira 24
        JsonNode c = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token().split("\\.")[1]));
        assertThat(c.get("sessionTtl").asText()).isEqualTo("24h");
        assertThat(c.get("exp").asLong() - c.get("iat").asLong()).isEqualTo(24 * 3600);
        assertThat(jwt.sessionHours()).isEqualTo(24);
    }

    @Test
    void assinaturaAlteradaERecusada() {
        String[] p = token().split("\\.");
        char ultimo = p[2].charAt(p[2].length() - 1);
        String adulterada = p[0] + "." + p[1] + "." + p[2].substring(0, p[2].length() - 1) + (ultimo == 'A' ? 'B' : 'A');
        assertThat(jwt.validarToken(adulterada)).isNull();
    }

    @Test
    void payloadAlteradoERecusado() {
        String[] p = token().split("\\.");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"horus-pdv-api\",\"aud\":\"horus-pdv-web\",\"sub\":\"usr-2\",\"sessionId\":\"s\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(jwt.validarToken(p[0] + "." + payload + "." + p[2])).isNull();
    }

    @Test
    void emissorOuPublicoErradosSaoRecusados() {
        String token = token();
        props.getAuth().setIssuer("outro");
        assertThat(jwt.validarToken(token)).isNull();
        props.getAuth().setIssuer("horus-pdv-api");
        props.getAuth().setAudience("outro");
        assertThat(jwt.validarToken(token)).isNull();
    }

    @Test
    void tokenExpiradoERecusado() {
        String token = token();
        JwtService depois = new JwtService(props, Clock.fixed(T0.plusSeconds(8 * 3600 + 1), ZoneOffset.UTC));
        assertThat(depois.validarToken(token)).isNull();
        JwtService antes = new JwtService(props, Clock.fixed(T0.plusSeconds(8 * 3600 - 1), ZoneOffset.UTC));
        assertThat(antes.validarToken(token)).isNotNull();
    }

    @Test
    void lixoNaoQuebra() {
        assertThat(jwt.validarToken(null)).isNull();
        assertThat(jwt.validarToken("")).isNull();
        assertThat(jwt.validarToken("a.b")).isNull();
        assertThat(jwt.validarToken("a.b.c")).isNull();
    }
}
