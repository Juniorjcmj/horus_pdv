package br.com.quack.estoque.labels;

import java.util.List;

/**
 * Tamanho de etiqueta. Rolo da térmica: uma etiqueta por página (colunas = linhas = 1). Folha (A4 Pimaco): grade de
 * colunas x linhas com espaçamento e margens. Todas as medidas em milímetros.
 */
public record LabelModel(String id, String nome, double larguraMm, double alturaMm, int colunas, int linhas,
        double espacoXMm, double espacoYMm, double margemEsqMm, double margemTopMm, boolean folha) {

    public static final double PT_POR_MM = 72 / 25.4;

    public static LabelModel rolo(String id, String nome, double larguraMm, double alturaMm) {
        return new LabelModel(id, nome, larguraMm, alturaMm, 1, 1, 0, 0, 0, 0, false);
    }

    public static final LabelModel R60X40 = rolo("rolo-60x40", "Rolo 60 x 40 mm", 60, 40);
    public static final LabelModel R50X30 = rolo("rolo-50x30", "Rolo 50 x 30 mm", 50, 30);
    public static final LabelModel R40X25 = rolo("rolo-40x25", "Rolo 40 x 25 mm", 40, 25);
    public static final LabelModel R70X35 = rolo("rolo-70x35", "Rolo 70 x 35 mm", 70, 35);
    /** Folha A4 Pimaco 6180 como na web (GondolaLabelModal): 3 colunas x 9 linhas, 63,5 x 31 mm; margens que centralizam a grade na A4. */
    public static final LabelModel PIMACO_6180 = new LabelModel("a4-pimaco-6180", "Folha A4 Pimaco 6180 (27 por folha)",
            63.5, 31, 3, 9, 0, 0, 9.75, 9, true);

    public static List<LabelModel> padroes() {
        return List.of(R60X40, R50X30, R40X25, R70X35, PIMACO_6180);
    }

    /** Tamanho personalizado informado pelo usuário (rolo se 1 x 1, folha nos demais casos). */
    public static LabelModel personalizado(double larguraMm, double alturaMm, int colunas, int linhas, double espacoXMm,
            double espacoYMm, double margemEsqMm, double margemTopMm) {
        if (larguraMm < 15 || alturaMm < 10 || larguraMm > 210 || alturaMm > 297) {
            throw new IllegalArgumentException("Tamanho de etiqueta fora do limite (mínimo 15 x 10 mm).");
        }
        if (colunas < 1 || linhas < 1) {
            throw new IllegalArgumentException("Informe ao menos 1 coluna e 1 linha.");
        }
        return new LabelModel("personalizado", "Personalizado " + fmt(larguraMm) + " x " + fmt(alturaMm) + " mm", larguraMm,
                alturaMm, colunas, linhas, espacoXMm, espacoYMm, margemEsqMm, margemTopMm, colunas * linhas > 1);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    public double larguraPt() {
        return larguraMm * PT_POR_MM;
    }

    public double alturaPt() {
        return alturaMm * PT_POR_MM;
    }

    public int porFolha() {
        return colunas * linhas;
    }

    @Override
    public String toString() {
        return nome;
    }
}
