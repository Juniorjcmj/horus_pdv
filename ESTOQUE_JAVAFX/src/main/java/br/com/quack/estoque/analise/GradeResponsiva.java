package br.com.quack.estoque.analise;

import javafx.scene.Node;
import javafx.scene.layout.Pane;

/**
 * Grade que distribui os filhos em quantas colunas couberem (largura mínima por coluna) e estica cada um para ocupar a
 * largura toda — sem sobrar espaço vazio à direita. Cada linha tem a altura do filho mais alto dela; em janela estreita
 * vira uma coluna só.
 */
public final class GradeResponsiva extends Pane {
    private final double larguraMinima;
    private final double espaco;
    private final int maxColunas;

    public GradeResponsiva(double larguraMinima, double espaco, int maxColunas, Node... filhos) {
        this.larguraMinima = larguraMinima;
        this.espaco = espaco;
        this.maxColunas = maxColunas;
        getChildren().addAll(filhos);
    }

    /** Quantas colunas cabem na largura. */
    static int colunas(double largura, double larguraMinima, double espaco, int maxColunas) {
        int n = (int) Math.floor((largura + espaco) / (larguraMinima + espaco));
        return Math.max(1, Math.min(maxColunas, n));
    }

    private int colunas(double largura) {
        return colunas(largura, larguraMinima, espaco, maxColunas);
    }

    private double larguraColuna(double largura, int n) {
        return (largura - espaco * (n - 1)) / n;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth() - snappedLeftInset() - snappedRightInset();
        int n = colunas(w);
        double cw = larguraColuna(w, n);
        double y = snappedTopInset();
        var visiveis = getManagedChildren();
        for (int i = 0; i < visiveis.size(); i += n) {
            double alturaLinha = 0;
            for (int j = i; j < Math.min(i + n, visiveis.size()); j++) {
                alturaLinha = Math.max(alturaLinha, visiveis.get(j).prefHeight(cw));
            }
            for (int j = i; j < Math.min(i + n, visiveis.size()); j++) {
                double x = snappedLeftInset() + (j - i) * (cw + espaco);
                visiveis.get(j).resizeRelocate(x, y, cw, alturaLinha);
            }
            y += alturaLinha + espaco;
        }
    }

    @Override
    protected double computePrefHeight(double largura) {
        double w = (largura < 0 ? getWidth() : largura) - snappedLeftInset() - snappedRightInset();
        if (w <= 0) {
            w = larguraMinima;
        }
        int n = colunas(w);
        double cw = larguraColuna(w, n);
        double h = snappedTopInset() + snappedBottomInset();
        var visiveis = getManagedChildren();
        for (int i = 0; i < visiveis.size(); i += n) {
            double alturaLinha = 0;
            for (int j = i; j < Math.min(i + n, visiveis.size()); j++) {
                alturaLinha = Math.max(alturaLinha, visiveis.get(j).prefHeight(cw));
            }
            h += alturaLinha + (i + n < visiveis.size() ? espaco : 0);
        }
        return h;
    }

    @Override
    protected double computeMinWidth(double height) {
        return Math.min(larguraMinima, 280);
    }

    @Override
    protected double computePrefWidth(double height) {
        return larguraMinima * Math.min(maxColunas, Math.max(1, getManagedChildren().size())) + espaco;
    }

    @Override
    public javafx.geometry.Orientation getContentBias() {
        return javafx.geometry.Orientation.HORIZONTAL; // altura depende da largura
    }
}
