package br.com.quack.pdv.fiscal.fila;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.fiscal.fila.app.DecisaoEmissaoNfce;
import br.com.quack.pdv.fiscal.fila.app.FiscalDaVenda;
import br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal;
import br.com.quack.pdv.shared.NegocioException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FiscalDaVendaTest {

    private final DecisaoEmissaoNfce decisao = mock(DecisaoEmissaoNfce.class);
    private final PendenciaFiscal pendencia = mock(PendenciaFiscal.class);
    private final FiscalDaVenda fiscal = new FiscalDaVenda(decisao, pendencia);

    @Test
    void falhaNaPendenciaDa503ComAMensagemEOsDadosDaVenda() {
        when(decisao.avaliarEmissaoParaVenda(any(), any(), any(), any(), any())).thenReturn(true);
        when(pendencia.enfileirar("emp", "sale-emp-1")).thenThrow(new IllegalStateException("banco fora"));

        assertThatThrownBy(() -> fiscal.aposVenda("emp", "sale-emp-1", "1", "dinheiro", List.of(), ""))
                .isInstanceOfSatisfying(NegocioException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage())
                            .isEqualTo("Venda guardada; a preparação da nota fiscal será retomada automaticamente.");
                    assertThat(e.getData()).isEqualTo(Map.of("saleNumber", "1", "vendaId", "sale-emp-1"));
                });
    }

    @Test
    void naoEmitirNaoEnfileira() {
        when(decisao.avaliarEmissaoParaVenda(any(), any(), any(), any(), any())).thenReturn(false);

        FiscalDaVenda.Resultado r = fiscal.aposVenda("emp", "v", "1", "dinheiro", List.of(), "");

        assertThat(r.emitirFiscal()).isFalse();
        assertThat(r.fiscalQueued()).isFalse();
        verify(pendencia, never()).enfileirar(any(), any());
    }

    @Test
    void emitirEnfileiraEMarcaFilaDoDocumento() {
        when(decisao.avaliarEmissaoParaVenda(any(), any(), any(), any(), any())).thenReturn(true);

        FiscalDaVenda.Resultado r = fiscal.aposVenda("emp", "v", "1", "pix", List.of("pix"), "");

        assertThat(r.emitirFiscal()).isTrue();
        assertThat(r.fiscalQueued()).isTrue();
    }

    @Test
    void vendaInexistenteNaDecisaoVira400() {
        when(decisao.avaliarEmissaoParaVenda(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Venda não encontrada para a empresa."));

        assertThatThrownBy(() -> fiscal.aposVenda("emp", "v", "1", "x", List.of(), ""))
                .isInstanceOfSatisfying(NegocioException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
