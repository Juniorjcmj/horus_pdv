package br.com.quack.pdv.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** O System.Text.Json do .NET apara zeros finais da fracao (verificado com o runtime .NET 8). */
class FormatoDataDotNetTest {

    private static OffsetDateTime com(int nanos) {
        return OffsetDateTime.of(2026, 11, 3, 20, 45, 18, nanos, ZoneOffset.UTC);
    }

    @Test
    void setimoDigitoZeroApara() {
        // 2315520 ticks -> .NET: 2026-11-03T20:45:18.231552+00:00
        assertThat(FormatoDataDotNet.formatar(com(231_552_000))).isEqualTo("2026-11-03T20:45:18.231552+00:00");
    }

    @Test
    void setimoDigitoDiferenteDeZeroMantemSeteCasas() {
        assertThat(FormatoDataDotNet.formatar(com(231_552_100))).isEqualTo("2026-11-03T20:45:18.2315521+00:00");
    }

    @Test
    void semFracaoNaoEscrevePonto() {
        assertThat(FormatoDataDotNet.formatar(com(0))).isEqualTo("2026-11-03T20:45:18+00:00");
    }
}
