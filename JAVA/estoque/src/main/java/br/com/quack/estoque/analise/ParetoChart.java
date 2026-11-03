package br.com.quack.estoque.analise;

import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Gráfico de Pareto desenhado à mão: barras de valor (eixo esquerdo, R$) e linha de acumulado (eixo direito, 0 a 100%) com
 * marcas tracejadas em 80% e 95%. Desenho próprio porque dois eixos Y alinhados com {@code javafx.scene.chart} dependem de
 * larguras de eixo que o JavaFX não deixa fixar. Redesenha ao redimensionar.
 */
public final class ParetoChart extends Pane {
    /** Um ponto: valor da barra, % acumulada (0 a 100) e o texto do tooltip. */
    public record Ponto(double valor, double acumulada, String dica) {
    }

    private static final double MARGEM_ESQ = 62;
    private static final double MARGEM_DIR = 40;
    private static final double MARGEM_TOPO = 12;
    private static final double MARGEM_BASE = 22;

    private List<Ponto> pontos = List.of();
    private double limiteA = 80;
    private double limiteB = 95;

    public ParetoChart() {
        getStyleClass().add("rg-pareto");
        setMinHeight(180);
        widthProperty().addListener((o, a, b) -> desenhar());
        heightProperty().addListener((o, a, b) -> desenhar());
    }

    public void setDados(List<Ponto> pontos, double limiteA, double limiteB) {
        this.pontos = pontos;
        this.limiteA = limiteA;
        this.limiteB = limiteB;
        desenhar();
    }

    public int pontos() {
        return pontos.size();
    }

    private void desenhar() {
        getChildren().clear();
        double w = getWidth();
        double h = getHeight();
        if (w < MARGEM_ESQ + MARGEM_DIR + 40 || h < 100 || pontos.isEmpty()) {
            return;
        }
        double x0 = MARGEM_ESQ;
        double x1 = w - MARGEM_DIR;
        double yTopo = MARGEM_TOPO;
        double yBase = h - MARGEM_BASE;
        double alt = yBase - yTopo;
        double maxValor = pontos.stream().mapToDouble(Ponto::valor).max().orElse(1);
        double teto = escala(maxValor);

        // grade e eixos: esquerdo em R$, direito em %
        for (int i = 0; i <= 4; i++) {
            double y = yBase - alt * i / 4;
            Line grade = new Line(x0, y, x1, y);
            grade.getStyleClass().add("rg-pareto-grade");
            getChildren().add(grade);
            rotulo(Graficos.eixo(teto * i / 4, "R$"), 2, y - 7, "rg-pareto-eixo");
            rotulo((i * 25) + "%", x1 + 6, y - 7, "rg-pareto-eixo");
        }

        double passo = (x1 - x0) / pontos.size();
        double larguraBarra = Math.max(1, passo * 0.72);
        Polyline linha = new Polyline();
        linha.getStyleClass().add("rg-pareto-linha");
        for (int i = 0; i < pontos.size(); i++) {
            Ponto p = pontos.get(i);
            double bh = teto <= 0 ? 0 : alt * p.valor() / teto;
            Rectangle barra = new Rectangle(x0 + passo * i + (passo - larguraBarra) / 2, yBase - bh, larguraBarra, Math.max(0.5, bh));
            barra.getStyleClass().add("rg-pareto-barra");
            Tooltip t = new Tooltip(p.dica());
            t.setShowDelay(Duration.millis(80));
            Tooltip.install(barra, t);
            getChildren().add(barra);
            linha.getPoints().addAll(x0 + passo * i + passo / 2, yBase - alt * Math.min(100, p.acumulada()) / 100);
        }
        marca(limiteA, x0, x1, yBase, alt);
        marca(limiteB, x0, x1, yBase, alt);
        getChildren().add(linha);
        rotulo("1", x0, yBase + 4, "rg-pareto-eixo");
        rotulo(String.valueOf(pontos.size()), x1 - 10, yBase + 4, "rg-pareto-eixo");
    }

    private void marca(double pct, double x0, double x1, double yBase, double alt) {
        double y = yBase - alt * pct / 100;
        Line l = new Line(x0, y, x1, y);
        l.getStyleClass().add("rg-pareto-marca");
        getChildren().add(l);
        rotulo(Math.round(pct) + "%", x0 + 4, y - 16, "rg-pareto-marca-texto");
    }

    private void rotulo(String texto, double x, double y, String classe) {
        Label l = new Label(texto);
        l.getStyleClass().add(classe);
        l.setLayoutX(x);
        l.setLayoutY(y);
        l.setMouseTransparent(true);
        getChildren().add(l);
    }

    /** Teto "redondo" do eixo (1, 2, 2,5 ou 5 vezes uma potência de 10) acima do maior valor. */
    static double escala(double max) {
        if (max <= 0) {
            return 1;
        }
        double exp = Math.pow(10, Math.floor(Math.log10(max)));
        for (double f : new double[] {1, 2, 2.5, 5, 10}) {
            if (max <= f * exp) {
                return f * exp;
            }
        }
        return 10 * exp;
    }
}
