package br.com.quack.pdv.plataforma.monitoramento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteJsonNuvem;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RelatorEstadoLojaTest {

    /** Relogio que o teste avanca a mao. */
    static final class RelogioManual extends Clock {
        private Instant agora = Instant.parse("2026-11-07T12:00:00Z");

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    private static RetratoLoja.Retrato retrato(boolean ativada) {
        return new RetratoLoja.Retrato(Instant.parse("2026-11-07T12:00:00Z"), ativada, 12L, false, null, true, null, 0, "1.0");
    }

    @Test
    @DisplayName("receptor antigo (404) gera uma unica linha de log por hora")
    void receptorAntigoLogaUmaVezPorHora() throws Exception {
        RetratoLoja retrato = mock(RetratoLoja.class);
        ClienteJsonNuvem nuvem = mock(ClienteJsonNuvem.class);
        RelogioManual clock = new RelogioManual();
        when(retrato.obter()).thenReturn(retrato(true));
        when(nuvem.post(eq(RelatorEstadoLoja.ROTA), any(), anyInt()))
                .thenThrow(new ClienteJsonNuvem.RespostaHttpException(404, "Nuvem respondeu 404"));
        RelatorEstadoLoja relator = new RelatorEstadoLoja(retrato, nuvem, clock);

        for (int i = 0; i < 5; i++) {
            assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.RECEPTOR_ANTIGO);
            clock.avancar(Duration.ofMinutes(1));
        }
        assertThat(relator.logsDe404()).isEqualTo(1);

        clock.avancar(Duration.ofHours(1));
        relator.relatar();
        assertThat(relator.logsDe404()).isEqualTo(2);
    }

    @Test
    @DisplayName("sem rede o relato e descartado sem excecao")
    void semRedeDescarta() throws Exception {
        RetratoLoja retrato = mock(RetratoLoja.class);
        ClienteJsonNuvem nuvem = mock(ClienteJsonNuvem.class);
        when(retrato.obter()).thenReturn(retrato(true));
        when(nuvem.post(any(), any(), anyInt())).thenThrow(new ConnectException("sem internet"));
        RelatorEstadoLoja relator = new RelatorEstadoLoja(retrato, nuvem, new RelogioManual());

        assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.DESCARTADO);
        assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.DESCARTADO);
    }

    @Test
    @DisplayName("outro erro HTTP (500) tambem e descartado, sem contar como receptor antigo")
    void erroDoServidorDescarta() throws Exception {
        RetratoLoja retrato = mock(RetratoLoja.class);
        ClienteJsonNuvem nuvem = mock(ClienteJsonNuvem.class);
        when(retrato.obter()).thenReturn(retrato(true));
        when(nuvem.post(any(), any(), anyInt())).thenThrow(new ClienteJsonNuvem.RespostaHttpException(500, "x"));
        RelatorEstadoLoja relator = new RelatorEstadoLoja(retrato, nuvem, new RelogioManual());

        assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.DESCARTADO);
        assertThat(relator.logsDe404()).isZero();
    }

    @Test
    @DisplayName("com a sincronizacao desativada nada e enviado")
    void semSincronizacaoAtivadaNaoEnvia() throws Exception {
        RetratoLoja retrato = mock(RetratoLoja.class);
        ClienteJsonNuvem nuvem = mock(ClienteJsonNuvem.class);
        when(retrato.obter()).thenReturn(retrato(false));
        RelatorEstadoLoja relator = new RelatorEstadoLoja(retrato, nuvem, new RelogioManual());

        assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.DESATIVADA);
        verify(nuvem, times(0)).post(any(), any(), anyInt());
    }

    @Test
    @DisplayName("envio com sucesso devolve ENVIADO")
    void enviaComSucesso() throws Exception {
        RetratoLoja retrato = mock(RetratoLoja.class);
        ClienteJsonNuvem nuvem = mock(ClienteJsonNuvem.class);
        when(retrato.obter()).thenReturn(retrato(true));
        when(nuvem.post(any(), any(), anyInt())).thenReturn(mock(JsonNode.class));
        RelatorEstadoLoja relator = new RelatorEstadoLoja(retrato, nuvem, new RelogioManual());

        assertThat(relator.relatar()).isEqualTo(RelatorEstadoLoja.Resultado.ENVIADO);
        verify(nuvem).post(eq(RelatorEstadoLoja.ROTA), any(), eq(RelatorEstadoLoja.TIMEOUT_S));
    }
}
