package br.com.quack.pdv.plataforma.sincronizacao.envio;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.envio.ClassificadorErro.Falha;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffEClassificacaoTest {

    @Test
    void tetoCresceDe2sAte5minSemEstourar() {
        assertThat(Backoff.teto(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(Backoff.teto(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(Backoff.teto(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(Backoff.teto(8)).isEqualTo(Duration.ofSeconds(256));
        assertThat(Backoff.teto(9)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Backoff.teto(10_000)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Backoff.teto(0)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void jitterFicaEntreMetadeEOTeto() {
        for (int t = 1; t <= 12; t++) {
            Duration max = Backoff.teto(t);
            assertThat(Backoff.espera(t, () -> 0.0)).isEqualTo(max.dividedBy(2));
            assertThat(Backoff.espera(t, () -> 0.999999)).isLessThanOrEqualTo(max).isGreaterThan(max.dividedBy(2));
        }
    }

    @ParameterizedTest
    @CsvSource({"408,TEMPORARIA", "429,TEMPORARIA", "500,TEMPORARIA", "502,TEMPORARIA", "503,TEMPORARIA",
            "400,DEFINITIVA", "401,DEFINITIVA", "403,DEFINITIVA", "404,DEFINITIVA", "413,DEFINITIVA", "422,DEFINITIVA",
            "409,VERSAO"})
    void statusHttp(int status, Falha esperado) {
        assertThat(ClassificadorErro.porStatus(status)).isEqualTo(esperado);
    }

    @Test
    void falhaDeRedeESempreTemporaria() {
        assertThat(ClassificadorErro.porExcecao(new ConnectException("x"))).isEqualTo(Falha.TEMPORARIA);
        assertThat(ClassificadorErro.porExcecao(new HttpTimeoutException("x"))).isEqualTo(Falha.TEMPORARIA);
        assertThat(ClassificadorErro.porExcecao(new IOException("x"))).isEqualTo(Falha.TEMPORARIA);
    }
}
