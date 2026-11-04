package br.com.quack.pdv.fiscal.fila;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.fiscal.fila.app.EmitenteConfigurado;
import br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima;
import br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal;
import br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository;
import br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository.VendaSemDocumento;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A fila fiscal minima por modo da API (spec fila-nfce, "Rotina fiscal desligavel"): nuvem nunca, loja so a retomada. */
class FilaFiscalPorModoTest {

    private final FilaFiscalRepository repo = mock(FilaFiscalRepository.class);
    private final PendenciaFiscal pendencia = mock(PendenciaFiscal.class);
    private final EmitenteConfigurado emitente = mock(EmitenteConfigurado.class);

    private FilaFiscalMinima fila(boolean ativa, String modo) {
        return new FilaFiscalMinima(repo, pendencia, emitente, Clock.systemUTC(), ativa, modo);
    }

    private void umaVendaSemNota() {
        when(repo.vendasSemDocumento(anyInt())).thenReturn(List.of(new VendaSemDocumento("emp", "vd-1")))
                .thenReturn(List.of());
    }

    @Test
    void modoNuvemNuncaRodaMesmoComAFilaLigada() {
        umaVendaSemNota();

        fila(true, "nuvem").executarAgendado();
        fila(true, "nuvem").retomarAntesDoEnvio();

        verifyNoInteractions(repo, pendencia);
    }

    @Test
    void modoLojaRodaSoARetomadaDasVendasSemNota() {
        umaVendaSemNota();

        fila(true, "loja").executarAgendado();

        verify(pendencia).enfileirar("emp", "vd-1");
        verify(repo, never()).pendentes(anyInt(), any());
        verify(repo, never()).cancelamentoDaVenda(any(), any());
    }

    @Test
    void modoLojaDesligadoNaoFazNada() {
        umaVendaSemNota();

        fila(false, "loja").executarAgendado();
        fila(false, "loja").retomarAntesDoEnvio();

        verifyNoInteractions(repo, pendencia);
    }

    @Test
    void modoPadraoMantemOCicloCompleto() {
        umaVendaSemNota();
        when(repo.pendentes(anyInt(), any())).thenReturn(List.of());

        fila(true, "padrao").executarAgendado();

        verify(pendencia).enfileirar("emp", "vd-1");
        verify(repo).pendentes(anyInt(), any());
    }

    @Test
    void retomadaAntesDoEnvioFalhaSeUmaVendaNaoGanharNota() {
        when(repo.vendasSemDocumento(anyInt())).thenReturn(List.of(new VendaSemDocumento("emp", "vd-ruim")));
        when(pendencia.enfileirar("emp", "vd-ruim")).thenThrow(new IllegalStateException("sem sequencia"));

        assertThatThrownBy(() -> fila(true, "loja").retomarAntesDoEnvio())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1 venda");
    }
}
