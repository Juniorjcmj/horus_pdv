package br.com.quack.pdv.produto.lote.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.quack.pdv.produto.lote.web.LoteDtos.CategoriaValidadeRequest;
import br.com.quack.pdv.produto.lote.web.LoteDtos.LoteConsultaFiltro;
import br.com.quack.pdv.produto.lote.web.LoteDtos.RegistrarLoteRequest;
import br.com.quack.pdv.shared.NegocioException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoteServiceTest {

    private final LoteOperacoes ops = mock(LoteOperacoes.class);
    private final LoteService service = new LoteService(ops);

    @BeforeEach
    void hoje() {
        when(ops.hojeBrasilia()).thenReturn(LocalDate.parse("2026-11-02"));
        when(ops.obterModo("e1")).thenReturn("sombra");
    }

    private static LoteLinha linha(String id, String produto, String nome, String validade, String inicial,
            String estoque, String cat, String pai, Integer alertaCat, int alertaProduto) {
        return new LoteLinha(id, produto, "L-" + id, LocalDate.parse(validade), new BigDecimal(inicial),
                new BigDecimal(inicial), "manual", false, OffsetDateTime.parse("2026-01-15T09:00:00-03:00"),
                "C-" + id, nome, new BigDecimal(estoque), new BigDecimal("2.5"), BigDecimal.TEN, alertaProduto,
                "Cat", cat, pai, alertaCat);
    }

    @Test
    void alertasSomenteVencidoCriticoEAtencaoOrdenadosPorDias() {
        when(ops.listarLinhas("e1", null)).thenReturn(List.of(
                linha("a", "p1", "Leite", "2026-11-04", "2", "2", "c1", "", null, 0),
                linha("b", "p1", "Leite", "2026-10-30", "2", "2", "c1", "", null, 0),
                linha("c", "p2", "Arroz", "2027-06-01", "2", "2", "c1", "", null, 0),
                linha("d", "p3", "Sem saldo", "2026-11-03", "2", "0", "c1", "", null, 0)));
        when(ops.contarProdutosSemLote("e1")).thenReturn(7);

        var r = service.listarAlertas("e1");

        // p1: estoque 2 vai para o lote mais distante (a); b fica esgotado
        assertThat(r.itens()).extracting("id").containsExactly("a");
        assertThat(r.criticos()).isEqualTo(1);
        assertThat(r.vencidos()).isZero();
        assertThat(r.valorEmRisco()).isEqualByComparingTo("5.00");
        assertThat(r.produtosSemLote()).isEqualTo(7);
        assertThat(r.modo()).isEqualTo("sombra");
    }

    @Test
    void janelaUsaCategoriaDepoisProdutoDepoisPadrao() {
        when(ops.listarLinhas("e1", null)).thenReturn(List.of(
                linha("a", "p1", "A", "2026-11-12", "1", "1", "c1", "", 10, 99),
                linha("b", "p2", "B", "2026-11-12", "1", "1", "c1", "", null, 9),
                linha("c", "p3", "C", "2026-11-12", "1", "1", "c1", "", null, 0)));

        var itens = service.consultar("e1", new LoteConsultaFiltro(null, null, null, null, null, null, null, 1, 25)).itens();

        assertThat(itens).extracting("janelaAlertaDias").containsExactly(10, 9, 15);
        assertThat(itens).extracting("faixa").containsExactly("atencao", "ok", "atencao");
    }

    @Test
    void consultaFiltraPorDepartamentoEPagina() {
        when(ops.listarLinhas("e1", null)).thenReturn(List.of(
                linha("a", "p1", "A", "2026-11-12", "1", "1", "sub", "dep", null, 0),
                linha("b", "p2", "B", "2026-11-13", "1", "1", "outra", "", null, 0)));

        var r = service.consultar("e1", new LoteConsultaFiltro(null, null, null, "dep", null, null, null, 9, 1));

        assertThat(r.total()).isEqualTo(1);
        assertThat(r.tamanhoPagina()).isEqualTo(5);
        assertThat(r.pagina()).isEqualTo(1);
        assertThat(r.itens()).extracting("id").containsExactly("a");
    }

    @Test
    void periodoInvertidoEDataInvalidaDaoMensagensDoDotnet() {
        assertThatThrownBy(() -> service.consultar("e1",
                new LoteConsultaFiltro(null, "2026-12-10", "2026-12-01", null, null, null, null, 1, 25)))
                .hasMessage("Período inválido: a validade final é anterior à inicial.");
        assertThatThrownBy(() -> service.consultar("e1",
                new LoteConsultaFiltro(null, "ruim", null, null, null, null, null, 1, 25)))
                .hasMessage("Data inicial inválida no filtro de validade.");
        assertThatThrownBy(() -> service.consultar("e1",
                new LoteConsultaFiltro(null, null, "ruim", null, null, null, null, 1, 25)))
                .hasMessage("Data final inválida no filtro de validade.");
    }

    @Test
    void registrarManualValidaProdutoDataEQuantidade() {
        assertThatThrownBy(() -> service.registrarManual("e1", "U", new RegistrarLoteRequest(" ", "2027-01-01", BigDecimal.ONE, null)))
                .hasMessage("Informe o produto do lote.");
        assertThatThrownBy(() -> service.registrarManual("e1", "U", new RegistrarLoteRequest("p", "01/01/2027", BigDecimal.ONE, null)))
                .hasMessage("Data de validade inválida.");
        assertThatThrownBy(() -> service.registrarManual("e1", "U", new RegistrarLoteRequest("p", "2027-01-01", BigDecimal.ZERO, null)))
                .hasMessage("A quantidade do lote deve ser maior que zero.");
        verify(ops, never()).registrarEntrada(any(), any(), any(), any(), any(), any(), any());

        service.registrarManual("e1", "U", new RegistrarLoteRequest(" p ", "2027-01-01", BigDecimal.ONE, "L1"));
        verify(ops).registrarEntrada("e1", "p", LocalDate.parse("2027-01-01"), BigDecimal.ONE, "L1", "manual", "U");
    }

    @Test
    void modoInvalidoEFaixasDeCategoria() {
        assertThatThrownBy(() -> service.definirModo("e1", "ligado"))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Modo inválido. Use 'desligado', 'sombra' ou 'ativo'.");
        service.definirModo("e1", " ATIVO ");
        verify(ops).definirModo("e1", "ativo");

        assertThatThrownBy(() -> service.salvarCategoria("e1", "c", new CategoriaValidadeRequest(0, 5)))
                .hasMessage("O prazo padrão deve ficar entre 1 e 3650 dias.");
        assertThatThrownBy(() -> service.salvarCategoria("e1", "c", new CategoriaValidadeRequest(30, 4000)))
                .hasMessage("A janela de alerta deve ficar entre 0 e 3650 dias.");
        when(ops.salvarCategoriaValidade("e1", "c", null, null)).thenReturn(true);
        assertThat(service.salvarCategoria("e1", "c", new CategoriaValidadeRequest(null, null))).isTrue();
    }

    @Test
    void fefoStatusApontaDivergenciaEEstoqueSemCobertura() {
        LoteLinha l = new LoteLinha("a", "p1", "L", LocalDate.parse("2026-11-12"), new BigDecimal("4"),
                new BigDecimal("1"), "manual", false, OffsetDateTime.parse("2026-01-15T09:00:00-03:00"),
                "C", "N", new BigDecimal("4"), BigDecimal.ONE, BigDecimal.TEN, 0, "", "", "", null);
        when(ops.listarLinhas("e1", null)).thenReturn(List.of(l));

        var r = service.obterFefoStatus("e1");

        assertThat(r.lotesComSaldo()).isEqualTo(1);
        assertThat(r.lotesComDivergencia()).isEqualTo(1);
        assertThat(r.produtosComEstoqueSemLote()).isEqualTo(1);
        assertThat(r.itens().getFirst().diferenca()).isEqualByComparingTo("-3");
    }
}
