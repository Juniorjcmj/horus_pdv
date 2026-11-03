package br.com.quack.estoque.labels;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometria da impressão (em pontos, relativa à área imprimível da página): no rolo, uma etiqueta por página,
 * centralizada e reduzida se for maior que a área; na folha, grade de colunas x linhas com margens e espaçamento.
 * O deslocamento de calibração (dx, dy) soma a todas as posições. Sem JavaFX para poder ser testada em qualquer lugar.
 */
public final class LabelPrintLayout {
    private LabelPrintLayout() {
    }

    /** Posição de uma etiqueta (índice na lista a imprimir) na página. */
    public record Slot(int indice, double x, double y) {
    }

    public record Pagina(List<Slot> slots) {
    }

    public record Resultado(double escala, List<Pagina> paginas, double larguraEtiqueta, double alturaEtiqueta) {
    }

    /**
     * @param total número de etiquetas a imprimir (com as cópias)
     * @param areaW largura imprimível da página em pontos
     * @param areaH altura imprimível da página em pontos
     * @param dxPt  deslocamento horizontal de calibração em pontos
     * @param dyPt  deslocamento vertical de calibração em pontos
     */
    public static Resultado calcular(LabelModel m, int total, double areaW, double areaH, double dxPt, double dyPt) {
        return calcular(m, total, areaW, areaH, dxPt, dyPt, 0, 0);
    }

    /**
     * Como acima, informando a margem de hardware (esquerda e topo, em pontos): na folha, as coordenadas são relativas à
     * área imprimível, que começa depois dessa margem.
     */
    public static Resultado calcular(LabelModel m, int total, double areaW, double areaH, double dxPt, double dyPt,
            double hwEsqPt, double hwTopPt) {
        List<Pagina> paginas = new ArrayList<>();
        if (total <= 0) {
            return new Resultado(1, paginas, m.larguraPt(), m.alturaPt());
        }
        if (!m.folha()) {
            double escala = Math.min(1.0, Math.min(areaW / m.larguraPt(), areaH / m.alturaPt()));
            double lw = m.larguraPt() * escala;
            double lh = m.alturaPt() * escala;
            double x = (areaW - lw) / 2 + dxPt;
            double y = (areaH - lh) / 2 + dyPt;
            for (int i = 0; i < total; i++) {
                paginas.add(new Pagina(List.of(new Slot(i, x, y))));
            }
            return new Resultado(escala, paginas, lw, lh);
        }
        double pt = LabelModel.PT_POR_MM;
        int porFolha = m.porFolha();
        for (int ini = 0; ini < total; ini += porFolha) {
            List<Slot> slots = new ArrayList<>();
            for (int k = 0; k < porFolha && ini + k < total; k++) {
                int col = k % m.colunas();
                int lin = k / m.colunas();
                double x = m.margemEsqMm() * pt - hwEsqPt + col * (m.larguraMm() + m.espacoXMm()) * pt + dxPt;
                double y = m.margemTopMm() * pt - hwTopPt + lin * (m.alturaMm() + m.espacoYMm()) * pt + dyPt;
                slots.add(new Slot(ini + k, x, y));
            }
            paginas.add(new Pagina(slots));
        }
        return new Resultado(1, paginas, m.larguraPt(), m.alturaPt());
    }

    /** Tamanho do papel (mm) que o modelo pede ao driver: o da etiqueta no rolo, A4 na folha. */
    public static double[] papelMm(LabelModel m) {
        return m.folha() ? new double[] {210, 297} : new double[] {m.larguraMm(), m.alturaMm()};
    }
}
