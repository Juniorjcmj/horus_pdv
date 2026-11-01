package br.com.quack.estoque.analise;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;

/**
 * Curva ABC clássica: no eixo horizontal a % dos produtos (do que mais vende para o que menos vende), no vertical a % do
 * faturamento acumulado. As faixas A, B e C ficam sombreadas e o mouse mostra o produto daquele ponto. Funciona com milhares
 * de produtos (a curva é reduzida a ~400 pontos sem perder as mudanças de classe).
 */
public final class CurvaAbcChart extends Pane {
    /**
     * Um ponto da curva, na ordem decrescente de valor: posição do produto (0 a 1 = fração dos produtos até ele),
     * % acumulada do valor, classe e o texto da dica.
     */
    public record Ponto(double fracao, double acumulada, String classe, String dica) {
    }

    private static final double ESQ = 44;
    private static final double DIR = 14;
    private static final double TOPO = 10;
    private static final double BASE = 26;
    private static final int MAX_PONTOS = 400;

    private List<Ponto> pontos = List.of();
    private final Circle marcador = new Circle(4);
    private final Tooltip dica = new Tooltip();

    public CurvaAbcChart() {
        getStyleClass().add("rg-curva-abc");
        setMinHeight(180);
        marcador.getStyleClass().add("rg-curva-abc-marcador");
        marcador.setVisible(false);
        marcador.setMouseTransparent(true);
        dica.setShowDelay(javafx.util.Duration.millis(40));
        widthProperty().addListener((o, a, b) -> desenhar());
        heightProperty().addListener((o, a, b) -> desenhar());
        setOnMouseMoved(e -> apontar(e.getX(), e.getScreenX(), e.getScreenY()));
        setOnMouseExited(e -> {
            marcador.setVisible(false);
            dica.hide();
        });
    }

    public void setDados(List<Ponto> pontos) {
        this.pontos = pontos;
        desenhar();
    }

    /** Índices a desenhar: no máximo ~400, sempre incluindo o primeiro, o último e cada troca de classe. */
    static List<Integer> amostra(List<Ponto> p) {
        List<Integer> out = new ArrayList<>();
        int n = p.size();
        if (n == 0) {
            return out;
        }
        int passo = Math.max(1, n / MAX_PONTOS);
        for (int i = 0; i < n; i++) {
            boolean troca = i > 0 && !p.get(i).classe().equals(p.get(i - 1).classe());
            if (i % passo == 0 || troca || i == n - 1) {
                out.add(i);
            }
        }
        return out;
    }

    private double x(int i, double x0, double largura) {
        return x0 + largura * Math.min(1, Math.max(0, pontos.get(i).fracao()));
    }

    private double y(double acumulada, double yBase, double altura) {
        return yBase - altura * Math.min(100, Math.max(0, acumulada)) / 100;
    }

    private void desenhar() {
        getChildren().clear();
        double w = getWidth();
        double h = getHeight();
        if (pontos.isEmpty() || w < ESQ + DIR + 60 || h < 80) {
            return;
        }
        double x0 = ESQ;
        double x1 = w - DIR;
        double lw = x1 - x0;
        double yBase = h - BASE;
        double alt = yBase - TOPO;

        // faixas A, B e C (pelo número de produtos de cada classe)
        int fimA = ultimoDaClasse("A");
        int fimB = ultimoDaClasse("B");
        double xa = fimA < 0 ? x0 : x(fimA, x0, lw);
        double xb = fimB < 0 ? xa : x(fimB, x0, lw);
        faixa(x0, xa, yBase, alt, "rg-faixa-a", "A");
        faixa(xa, xb, yBase, alt, "rg-faixa-b", "B");
        faixa(xb, x1, yBase, alt, "rg-faixa-c", "C");

        // grade e eixos
        for (int i = 0; i <= 4; i++) {
            double y = yBase - alt * i / 4;
            Line g = new Line(x0, y, x1, y);
            g.getStyleClass().add("rg-pareto-grade");
            getChildren().add(g);
            rotulo((i * 25) + "%", 4, y - 8, "rg-pareto-eixo");
        }
        for (int i = 0; i <= 4; i++) {
            double xx = x0 + lw * i / 4;
            rotulo((i * 25) + "%", Math.min(xx - 10, x1 - 28), yBase + 6, "rg-pareto-eixo");
        }

        // área e curva
        List<Integer> idx = amostra(pontos); // a API já reduz; aqui só garante o limite
        Polygon area = new Polygon();
        area.getPoints().addAll(x0, yBase);
        Polyline curva = new Polyline();
        curva.getPoints().addAll(x0, yBase);
        for (int i : idx) {
            double px = x(i, x0, lw);
            double py = y(pontos.get(i).acumulada(), yBase, alt);
            area.getPoints().addAll(px, py);
            curva.getPoints().addAll(px, py);
        }
        area.getPoints().addAll(x1, yBase);
        area.getStyleClass().add("rg-curva-abc-area");
        curva.getStyleClass().add("rg-pareto-linha");
        area.setMouseTransparent(true);
        curva.setMouseTransparent(true);
        getChildren().addAll(area, curva);

        // marcas no fim de A e de B: "x% dos produtos = y% do valor"
        if (fimA >= 0) {
            marca(fimA, x0, lw, yBase, alt);
        }
        if (fimB > fimA) {
            marca(fimB, x0, lw, yBase, alt);
        }
        getChildren().add(marcador);
        Rectangle captura = new Rectangle(x0, TOPO, lw, alt);
        captura.setFill(javafx.scene.paint.Color.TRANSPARENT);
        getChildren().add(captura);
    }

    private int ultimoDaClasse(String classe) {
        int ultimo = -1;
        for (int i = 0; i < pontos.size(); i++) {
            if (classe.equals(pontos.get(i).classe())) {
                ultimo = i;
            }
        }
        return ultimo;
    }

    private void faixa(double xIni, double xFim, double yBase, double alt, String classe, String letra) {
        if (xFim - xIni < 1) {
            return;
        }
        Rectangle r = new Rectangle(xIni, yBase - alt, xFim - xIni, alt);
        r.getStyleClass().add(classe);
        r.setMouseTransparent(true);
        getChildren().add(r);
        if (xFim - xIni > 18) {
            rotulo(letra, xIni + (xFim - xIni) / 2 - 5, yBase - alt + 4, "rg-faixa-letra");
        }
    }

    private void marca(int i, double x0, double lw, double yBase, double alt) {
        double px = x(i, x0, lw);
        double py = y(pontos.get(i).acumulada(), yBase, alt);
        Line v = new Line(px, py, px, yBase);
        v.getStyleClass().add("rg-pareto-marca");
        v.setMouseTransparent(true);
        Circle c = new Circle(px, py, 3.5);
        c.getStyleClass().add("rg-curva-abc-ponto");
        c.setMouseTransparent(true);
        getChildren().addAll(v, c);
        String txt = AnaliseFmtCurva.pct(100.0 * pontos.get(i).fracao()) + " dos produtos · "
                + AnaliseFmtCurva.pct(pontos.get(i).acumulada()) + " do valor";
        rotulo(txt, Math.min(px + 6, getWidth() - DIR - 190), py - 2, "rg-curva-abc-nota");
    }

    private void apontar(double mx, double sx, double sy) {
        if (pontos.isEmpty()) {
            return;
        }
        double lw = getWidth() - DIR - ESQ;
        if (mx < ESQ || mx > ESQ + lw) {
            marcador.setVisible(false);
            dica.hide();
            return;
        }
        double alvo = (mx - ESQ) / lw;
        int i = 0;
        double melhor = Double.MAX_VALUE;
        for (int k = 0; k < pontos.size(); k++) {
            double d = Math.abs(pontos.get(k).fracao() - alvo);
            if (d < melhor) {
                melhor = d;
                i = k;
            }
        }
        double yBase = getHeight() - BASE;
        double alt = yBase - TOPO;
        marcador.setCenterX(x(i, ESQ, lw));
        marcador.setCenterY(y(pontos.get(i).acumulada(), yBase, alt));
        marcador.setVisible(true);
        dica.setText(pontos.get(i).dica());
        dica.show(this, sx + 14, sy + 10);
    }

    private void rotulo(String texto, double x, double y, String classe) {
        Label l = new Label(texto);
        l.getStyleClass().add(classe);
        l.setLayoutX(x);
        l.setLayoutY(y);
        l.setMouseTransparent(true);
        getChildren().add(l);
    }

    /** Formatação curta de percentuais da curva (pt-BR, uma casa). */
    static final class AnaliseFmtCurva {
        private AnaliseFmtCurva() {
        }

        static String pct(double v) {
            return String.format(new java.util.Locale("pt", "BR"), v < 10 ? "%.1f%%" : "%.0f%%", v);
        }
    }
}
