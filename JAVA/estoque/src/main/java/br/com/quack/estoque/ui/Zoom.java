package br.com.quack.estoque.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;
import javafx.stage.Screen;

/**
 * Zoom da interface inteira (janela principal e modais). Em telas pequenas (notebook 1366×768) o padrão é reduzir para o
 * conteúdo caber; o operador ajusta com Ctrl + − / Ctrl + = / Ctrl + 0 e o valor fica salvo no config.
 *
 * <p>O conteúdo é diagramado no tamanho "virtual" (área ÷ zoom) e desenhado com uma escala: textos e ícones continuam
 * nítidos (o JavaFX desenha vetorial) e o layout se comporta como numa tela maior.
 */
public final class Zoom {
    public static final double MIN = 0.7;
    public static final double MAX = 1.3;
    private static final DoubleProperty FATOR = new SimpleDoubleProperty(1.0);

    private Zoom() {
    }

    public static DoubleProperty fator() {
        return FATOR;
    }

    public static double get() {
        return FATOR.get();
    }

    public static void set(double v) {
        FATOR.set(limitar(v));
    }

    public static double limitar(double v) {
        return Math.round(Math.max(MIN, Math.min(MAX, v)) * 20) / 20.0; // passos de 5%
    }

    /** Zoom inicial para a tela: área útil baixa (≤ 800 px de altura) começa menor. */
    public static double padraoPara(Rectangle2D areaUtil) {
        double h = areaUtil.getHeight();
        double w = areaUtil.getWidth();
        if (h <= 740 || w <= 1300) {
            return 0.85;
        }
        if (h <= 820) {
            return 0.9;
        }
        return 1.0;
    }

    public static double padraoDaTela() {
        return padraoPara(Screen.getPrimary().getVisualBounds());
    }

    /** Envolve o conteúdo para que ele acompanhe o zoom. */
    public static Region envolver(Parent conteudo) {
        return new Pane(conteudo);
    }

    /** Região que diagrama o filho no tamanho virtual e aplica a escala. */
    private static final class Pane extends Region {
        private final Parent filho;
        private final Scale escala = new Scale(1, 1, 0, 0);

        Pane(Parent filho) {
            this.filho = filho;
            filho.getTransforms().add(escala);
            getChildren().add(filho);
            FATOR.addListener((o, a, b) -> requestLayout());
            getStyleClass().add("rg-zoom");
        }

        @Override
        protected void layoutChildren() {
            double z = FATOR.get();
            escala.setX(z);
            escala.setY(z);
            filho.resizeRelocate(0, 0, getWidth() / z, getHeight() / z);
        }

        @Override
        protected double computePrefWidth(double height) {
            double z = FATOR.get();
            return filho.prefWidth(height < 0 ? -1 : height / z) * z;
        }

        @Override
        protected double computePrefHeight(double width) {
            double z = FATOR.get();
            return filho.prefHeight(width < 0 ? -1 : width / z) * z;
        }

        @Override
        protected double computeMinWidth(double height) {
            return filho.minWidth(-1) * FATOR.get();
        }

        @Override
        protected double computeMinHeight(double width) {
            return filho.minHeight(-1) * FATOR.get();
        }
    }
}
