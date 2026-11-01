package br.com.quack.estoque.analise;

import java.util.List;
import java.util.function.Function;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Fábrica dos gráficos nativos da análise (javafx.scene.chart): linha com linhas de referência, barras horizontais e rosca.
 * Cores só por classes CSS (quack.css); tooltips em pt-BR montados por {@link AnaliseFmt}.
 */
public final class Graficos {
    private Graficos() {
    }

    /** Um ponto: rótulo do eixo X, valor e texto do tooltip. */
    public record Ponto(String rotulo, double valor, String dica) {
    }

    /** Uma linha de referência horizontal (mínimo, ponto de pedido, meta...). */
    public record Referencia(String nome, double valor, String classeCss) {
    }

    private static void dica(Node n, String texto) {
        Tooltip t = new Tooltip(texto);
        t.setShowDelay(Duration.millis(80));
        t.setHideDelay(Duration.millis(50));
        Tooltip.install(n, t);
    }

    /** Instala o tooltip assim que o nó do dado existir (o JavaFX cria os nós depois do layout). */
    private static <X, Y> void dicaNoDado(XYChart.Data<X, Y> d, String texto) {
        if (d.getNode() != null) {
            dica(d.getNode(), texto);
        } else {
            d.nodeProperty().addListener((o, a, n) -> {
                if (n != null) {
                    dica(n, texto);
                }
            });
        }
    }

    /** Gráfico de linha (uma série) com linhas de referência opcionais desenhadas como séries tracejadas. */
    public static LineChart<String, Number> linha(String nomeSerie, List<Ponto> pontos, String unidadeY, List<Referencia> referencias) {
        CategoryAxis x = new CategoryAxis();
        x.setTickLabelRotation(0);
        NumberAxis y = new NumberAxis();
        y.setForceZeroInRange(false);
        y.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return eixo(n.doubleValue(), unidadeY);
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        LineChart<String, Number> chart = new LineChart<>(x, y);
        chart.setCreateSymbols(pontos.size() <= 45);
        chart.setLegendVisible(!referencias.isEmpty());
        chart.setAnimated(false);
        chart.getStyleClass().add("rg-chart");

        XYChart.Series<String, Number> serie = new XYChart.Series<>();
        serie.setName(nomeSerie);
        for (Ponto p : pontos) {
            XYChart.Data<String, Number> d = new XYChart.Data<>(p.rotulo(), p.valor());
            serie.getData().add(d);
            dicaNoDado(d, p.dica());
        }
        chart.getData().add(serie);
        for (Referencia r : referencias) {
            XYChart.Series<String, Number> ref = new XYChart.Series<>();
            ref.setName(r.nome());
            for (Ponto p : pontos) {
                XYChart.Data<String, Number> d = new XYChart.Data<>(p.rotulo(), r.valor());
                ref.getData().add(d);
                // a referência é só uma linha: sem bolinhas nos pontos
                d.nodeProperty().addListener((o, a, n) -> {
                    if (n != null) {
                        n.setVisible(false);
                        n.setMouseTransparent(true);
                    }
                });
            }
            chart.getData().add(ref);
            ref.nodeProperty().addListener((o, a, n) -> estilizarReferencia(n, r));
            estilizarReferencia(ref.getNode(), r);
        }
        return chart;
    }

    private static void estilizarReferencia(Node n, Referencia r) {
        if (n != null) {
            n.getStyleClass().add(r.classeCss());
        }
    }

    /** Barras horizontais (maior em cima). */
    public static BarChart<Number, String> barrasHorizontais(String nomeSerie, List<Ponto> itens, String unidade) {
        NumberAxis x = new NumberAxis();
        x.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return eixo(n.doubleValue(), unidade);
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        CategoryAxis y = new CategoryAxis();
        y.setCategories(FXCollections.observableArrayList(itens.stream().map(Ponto::rotulo).toList()));
        BarChart<Number, String> chart = new BarChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setBarGap(2);
        chart.setCategoryGap(6);
        chart.getStyleClass().add("rg-chart");
        XYChart.Series<Number, String> serie = new XYChart.Series<>();
        serie.setName(nomeSerie);
        for (Ponto p : itens) {
            XYChart.Data<Number, String> d = new XYChart.Data<>(p.valor(), p.rotulo());
            serie.getData().add(d);
            dicaNoDado(d, p.dica());
        }
        chart.getData().add(serie);
        return chart;
    }

    /**
     * Barras verticais agrupadas (uma série por nome) ou empilhadas (perdas por motivo, mês a mês). {@code valores.get(s)[c]} é o
     * valor da série {@code s} na categoria {@code c}; o tooltip mostra categoria, série e valor.
     */
    public static XYChart<String, Number> barrasVerticais(List<String> categorias, List<String> series, List<double[]> valores,
            String unidade, boolean empilhado) {
        CategoryAxis x = new CategoryAxis(FXCollections.observableArrayList(categorias));
        NumberAxis y = new NumberAxis();
        y.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return eixo(n.doubleValue(), unidade);
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        XYChart<String, Number> chart = empilhado ? new javafx.scene.chart.StackedBarChart<>(x, y) : new BarChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(series.size() > 1);
        chart.getStyleClass().add("rg-chart");
        for (int s = 0; s < series.size(); s++) {
            XYChart.Series<String, Number> serie = new XYChart.Series<>();
            serie.setName(series.get(s));
            for (int c = 0; c < categorias.size(); c++) {
                double v = valores.get(s)[c];
                XYChart.Data<String, Number> d = new XYChart.Data<>(categorias.get(c), v);
                serie.getData().add(d);
                dicaNoDado(d, categorias.get(c) + "\n" + series.get(s) + ": " + eixo(v, unidade));
            }
            chart.getData().add(serie);
        }
        return chart;
    }

    /** Rosca: PieChart com um círculo no meio mostrando o total. */
    public static StackPane rosca(List<Ponto> fatias, String textoCentro) {
        PieChart pie = new PieChart(FXCollections.observableArrayList(
                fatias.stream().map(p -> new PieChart.Data(p.rotulo(), Math.max(0, p.valor()))).toList()));
        pie.setLegendVisible(true);
        pie.setLabelsVisible(false);
        pie.setAnimated(false);
        pie.setStartAngle(90);
        pie.getStyleClass().add("rg-chart");
        for (int i = 0; i < fatias.size(); i++) {
            PieChart.Data d = pie.getData().get(i);
            String texto = fatias.get(i).dica();
            if (d.getNode() != null) {
                dica(d.getNode(), texto);
            } else {
                d.nodeProperty().addListener((o, a, n) -> {
                    if (n != null) {
                        dica(n, texto);
                    }
                });
            }
        }
        Circle furo = new Circle(60);
        furo.getStyleClass().add("rg-rosca-furo");
        furo.setMouseTransparent(true);
        Label centro = new Label(textoCentro);
        centro.getStyleClass().add("rg-rosca-total");
        centro.setMouseTransparent(true);
        centro.setAlignment(Pos.CENTER);
        StackPane stack = new StackPane(pie, furo, centro);
        stack.setMinHeight(0);
        return stack;
    }

    /** Rótulo curto do eixo: 12.500 vira "12,5 mil"; porcentagens e dias ficam inteiros. */
    public static String eixo(double v, String unidade) {
        String prefixo = "R$".equals(unidade) ? "R$ " : "";
        String sufixo = "%".equals(unidade) ? "%" : "";
        double a = Math.abs(v);
        String n;
        if (a >= 1_000_000) {
            n = fmt(v / 1_000_000) + " mi";
        } else if (a >= 10_000) {
            n = fmt(v / 1_000) + " mil";
        } else {
            n = fmt(v);
        }
        return prefixo + n + sufixo;
    }

    private static String fmt(double v) {
        return new java.text.DecimalFormat("#,##0.#", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.of("pt", "BR"))).format(v);
    }

    /** Extrai uma lista de pontos de um array JSON. */
    public static <T> List<Ponto> de(Iterable<T> itens, Function<T, Ponto> f) {
        java.util.ArrayList<Ponto> lista = new java.util.ArrayList<>();
        for (T t : itens) {
            lista.add(f.apply(t));
        }
        return lista;
    }
}
