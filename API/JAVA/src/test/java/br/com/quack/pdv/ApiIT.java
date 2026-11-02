package br.com.quack.pdv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Base dos ITs da API inteira (MockMvc + SQL Server real + seed do contrato). */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiIT extends BancoIT {

    public static final String SENHA = "Senha#Quack2026";
    private static final AtomicBoolean SEMEADO = new AtomicBoolean(false);
    protected static final ObjectMapper JSON = new ObjectMapper();

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void semearUmaVez() {
        if (SEMEADO.compareAndSet(false, true)) {
            // conexao propria: o seed faz SET NOCOUNT ON, que nao pode vazar para uma conexao do pool
            ResourceDatabasePopulator p = new ResourceDatabasePopulator(new ClassPathResource("contrato/seed/seed-contrato.sql"));
            p.setSeparator(ScriptUtils.EOF_STATEMENT_SEPARATOR);
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(urlDoBanco("quack"), SQL.getUsername(), SQL.getPassword())) {
                p.populate(c);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    protected MvcResult login(String email, String senha) throws Exception {
        return mvc.perform(post("/api/Auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("email", email, "password", senha)))).andReturn();
    }

    protected JsonNode corpo(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Faz login e devolve o cabecalho Authorization pronto. */
    protected String bearer(String email) throws Exception {
        MvcResult r = login(email, SENHA);
        if (r.getResponse().getStatus() != 200) {
            throw new IllegalStateException("login de " + email + " falhou: " + r.getResponse().getContentAsString());
        }
        String cookie = r.getResponse().getCookie("horuspdv.auth").getValue();
        return "Bearer " + cookie;
    }

    protected static HttpHeaders auth(String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.AUTHORIZATION, bearer);
        return h;
    }
}
