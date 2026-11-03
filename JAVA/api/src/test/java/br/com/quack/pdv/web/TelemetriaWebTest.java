package br.com.quack.pdv.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import br.com.quack.pdv.security.TelemetriaFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/** 5.2: X-Request-Id e o log http.request.completed com o mesmo id. */
class TelemetriaWebTest extends WebTestBase {

    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void capturar() {
        logger = (Logger) LoggerFactory.getLogger(TelemetriaFilter.class);
        logger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void soltar() {
        logger.detachAppender(logs);
    }

    @Test
    void logTemOMesmoIdDoCabecalhoECamposDoDotNet() throws Exception {
        MvcResult r = mvc.perform(get("/api/Teste/eco").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe("administrador")))
                .andReturn();
        String id = r.getResponse().getHeader("X-Request-Id");
        assertThat(id).isNotBlank();

        assertThat(logs.list).hasSize(1);
        String msg = logs.list.get(0).getFormattedMessage();
        assertThat(msg).startsWith("http.request.completed requestId=" + id + " method=GET path=/api/Teste/eco statusCode=200 durationMs=");
        assertThat(msg).contains(" userId=usr-1 sessionId=sess-usr-1 ip=");
    }

    @Test
    void requisicaoSemTokenLogaComUsuarioNuloEStatus401() throws Exception {
        MvcResult r = mvc.perform(get("/api/Teste/eco")).andReturn();
        String id = r.getResponse().getHeader("X-Request-Id");
        String msg = logs.list.get(logs.list.size() - 1).getFormattedMessage();
        assertThat(msg).contains("requestId=" + id).contains("statusCode=401").contains("userId=null sessionId=null");
    }
}
