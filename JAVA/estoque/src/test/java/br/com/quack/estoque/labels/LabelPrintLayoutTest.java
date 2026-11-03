package br.com.quack.estoque.labels;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.service.PrintService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Geometria de cada página dentro do papel, com e sem deslocamento de calibração. */
class LabelPrintLayoutTest {
    static final double PT = LabelModel.PT_POR_MM;
    // área imprimível típica de uma térmica 60x40 mm (margem mínima do driver ~2 mm por lado)
    static final double AREA_W = 56 * PT;
    static final double AREA_H = 36 * PT;

    @Test
    void roloUmaEtiquetaPorPaginaCentralizada() {
        LabelPrintLayout.Resultado r = LabelPrintLayout.calcular(LabelModel.R60X40, 3, AREA_W, AREA_H, 0, 0);
        assertEquals(3, r.paginas().size(), "uma etiqueta por página");
        assertTrue(r.escala() < 1, "60 x 40 não cabe em 56 x 36: reduz para caber");
        for (LabelPrintLayout.Pagina p : r.paginas()) {
            assertEquals(1, p.slots().size());
            LabelPrintLayout.Slot s = p.slots().get(0);
            assertTrue(s.x() >= -1e-9 && s.x() + r.larguraEtiqueta() <= AREA_W + 1e-9, "dentro da largura do papel");
            assertTrue(s.y() >= -1e-9 && s.y() + r.alturaEtiqueta() <= AREA_H + 1e-9, "dentro da altura do papel");
            assertEquals((AREA_W - r.larguraEtiqueta()) / 2, s.x(), 1e-9);
        }
    }

    @Test
    void roloQueCabeNaoReduz() {
        LabelPrintLayout.Resultado r = LabelPrintLayout.calcular(LabelModel.R40X25, 1, 100 * PT, 80 * PT, 0, 0);
        assertEquals(1.0, r.escala());
        LabelPrintLayout.Slot s = r.paginas().get(0).slots().get(0);
        assertEquals((100 * PT - 40 * PT) / 2, s.x(), 1e-9);
        assertEquals((80 * PT - 25 * PT) / 2, s.y(), 1e-9);
    }

    @Test
    void deslocamentoDeCalibracaoSomaNasPosicoes() {
        LabelPrintLayout.Resultado sem = LabelPrintLayout.calcular(LabelModel.R40X25, 2, 100 * PT, 80 * PT, 0, 0);
        double dx = -2 * PT;
        double dy = 1.5 * PT;
        LabelPrintLayout.Resultado com = LabelPrintLayout.calcular(LabelModel.R40X25, 2, 100 * PT, 80 * PT, dx, dy);
        for (int i = 0; i < 2; i++) {
            LabelPrintLayout.Slot a = sem.paginas().get(i).slots().get(0);
            LabelPrintLayout.Slot b = com.paginas().get(i).slots().get(0);
            assertEquals(a.x() + dx, b.x(), 1e-9, "2 mm para a esquerda");
            assertEquals(a.y() + dy, b.y(), 1e-9);
            assertTrue(b.x() >= 0 && b.x() + com.larguraEtiqueta() <= 100 * PT, "continua dentro do papel");
        }
    }

    @Test
    void pimacoGradeDe27PorFolha() {
        double a4w = 202 * PT; // A4 menos a margem de hardware (~4 mm por lado)
        double a4h = 289 * PT;
        double hw = 4 * PT;
        LabelPrintLayout.Resultado r = LabelPrintLayout.calcular(LabelModel.PIMACO_6180, 30, a4w, a4h, 0, 0, hw, hw);
        assertEquals(2, r.paginas().size());
        assertEquals(27, r.paginas().get(0).slots().size());
        assertEquals(3, r.paginas().get(1).slots().size());
        double maxX = 0;
        double maxY = 0;
        for (LabelPrintLayout.Slot s : r.paginas().get(0).slots()) {
            maxX = Math.max(maxX, s.x() + LabelModel.PIMACO_6180.larguraPt());
            maxY = Math.max(maxY, s.y() + LabelModel.PIMACO_6180.alturaPt());
        }
        assertTrue(maxX <= a4w, "3 colunas cabem na largura: " + maxX / PT + " mm");
        assertTrue(maxY <= a4h, "9 linhas cabem na altura: " + maxY / PT + " mm");
        LabelPrintLayout.Slot primeira = r.paginas().get(0).slots().get(0);
        assertEquals((9.75 - 4) * PT, primeira.x(), 1e-9);
        assertEquals((9 - 4) * PT, primeira.y(), 1e-9);
        LabelPrintLayout.Slot segunda = r.paginas().get(0).slots().get(1);
        assertEquals(primeira.x() + 63.5 * PT, segunda.x(), 1e-9);
        LabelPrintLayout.Slot quarta = r.paginas().get(0).slots().get(3); // 2a linha
        assertEquals(primeira.y() + 31 * PT, quarta.y(), 1e-9);
        // calibração desloca a grade inteira
        LabelPrintLayout.Resultado c = LabelPrintLayout.calcular(LabelModel.PIMACO_6180, 30, a4w, a4h, 2 * PT, -1 * PT, hw, hw);
        assertEquals(primeira.x() + 2 * PT, c.paginas().get(0).slots().get(0).x(), 1e-9);
        assertEquals(primeira.y() - PT, c.paginas().get(0).slots().get(0).y(), 1e-9);
    }

    @Test
    void semEtiquetasNaoGeraPaginas() {
        assertTrue(LabelPrintLayout.calcular(LabelModel.R60X40, 0, AREA_W, AREA_H, 0, 0).paginas().isEmpty());
    }

    @Test
    void papelPedidoAoDriver() {
        assertArrayEquals(new double[] {60, 40}, LabelPrintLayout.papelMm(LabelModel.R60X40));
        assertArrayEquals(new double[] {210, 297}, LabelPrintLayout.papelMm(LabelModel.PIMACO_6180));
    }

    @Test
    void escolheOPapelMaisProximoDaListaDoDriver() {
        List<javafx.print.Paper> papeis = List.of(javafx.print.Paper.A4, javafx.print.Paper.A6, javafx.print.Paper.NA_LETTER);
        assertEquals(javafx.print.Paper.A4, PrintService.papelMaisProximo(papeis, 210, 297));
        assertEquals(javafx.print.Paper.A6, PrintService.papelMaisProximo(papeis, 148, 105), "aceita rotação");
        assertNull(PrintService.papelMaisProximo(papeis, 60, 40), "driver sem o tamanho: usa o padrão e escala");
        assertNull(PrintService.papelMaisProximo(List.of(), 60, 40));
    }

    @Test
    void ajusteDeEscalaEPaginasDoDocumento() {
        assertEquals(1.0, PrintService.fitScale(200, 204));
        assertEquals(204.0 / 288, PrintService.fitScale(288, 204), 1e-9);
        List<double[]> pags = PrintService.pageRanges(List.of(30.0, 70.0, 110.0, 150.0, 190.0), 0, 200, 100);
        assertEquals(3, pags.size());
        assertArrayEquals(new double[] {0, 70}, pags.get(0));
        assertArrayEquals(new double[] {70, 150}, pags.get(1));
    }
}
