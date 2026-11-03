package br.com.quack.caixa.ui;

import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Vitrine da tipografia (só em modo dev: -Dquack.dev=true, atalho Ctrl+Shift+T): escala completa, botões, campos,
 * tabela, gráfico, acentos e o resultado do carregamento das fontes (família real e se caiu no fallback).
 */
public final class TypographyPreview {
    /** Linha de exemplo da tabela. */
    public record Item(String codigo, String produto, String quantidade, String valor) {
    }

    private TypographyPreview() {
    }

    public static boolean disponivel() {
        return Boolean.getBoolean("quack.dev");
    }

    public static void abrir(Window dono) {
        Stage st = new Stage();
        if (dono != null) {
            st.initOwner(dono);
        }
        st.setTitle("Tipografia — Inter + Manrope (dev)");
        Scene sc = new Scene(conteudo(), 1100, 820);
        sc.getStylesheets().setAll(Fx.stylesheets());
        st.setScene(sc);
        st.show();
    }

    /** Conteúdo da vitrine (também usado para gerar imagem nos testes). */
    public static Parent conteudo() {
        VBox raiz = new VBox(18);
        raiz.setPadding(new Insets(24));
        raiz.getStyleClass().add("rg-page");

        raiz.getChildren().add(secao("Fontes carregadas", status()));

        VBox escala = new VBox(8,
                rotulo("Título de página — Relatório de estoque", "h1", ".h1 · Manrope Bold 28"),
                rotulo("Seção — Pedidos de compra em aberto", "h2", ".h2 · Manrope SemiBold 22"),
                rotulo("Subseção — Itens do pedido", "h3", ".h3 · Inter SemiBold 18"),
                rotulo("Subtítulo — Gerado a partir da sugestão de reposição", "subtitle", ".subtitle · Inter Medium 16"),
                rotulo("Corpo — Ação, cotação, preço, promoção, informação, açúcar, pão, maçã, coração, avô.", "body",
                        ".body · Inter Regular 14"),
                rotulo("Legenda — Atualizado às 14:32 de 01/11/2026", "caption", ".caption · Inter Regular 12"),
                rotulo("Destaque — Total do pedido: R$ 1.234,56", "label-strong", ".label-strong · Inter SemiBold 14"));
        raiz.getChildren().add(secao("Escala", escala));

        HBox kpis = new HBox(16, kpi("VALOR EM ESTOQUE", "R$ 48.920,10"), kpi("PRODUTOS CRÍTICOS", "37"),
                kpi("GIRO (30 DIAS)", "2,4×"));
        raiz.getChildren().add(secao("KPIs (.kpi-value / .kpi-label)", kpis));

        Button primario = Fx.button("Finalizar venda", "rg-btn-primary", () -> { });
        Button normal = Fx.button("Imprimir cupom", null, () -> { });
        Button perigo = Fx.button("Cancelar venda", "rg-btn-outline-danger", () -> { });
        Button desab = Fx.button("Desabilitado", null, () -> { });
        desab.setDisable(true);
        normal.setTooltip(new Tooltip("Tooltip em Inter 12px — reimprime o último cupom"));
        TextField campo = new TextField();
        campo.setPromptText("Código, código de barras ou nome");
        TextField preenchido = new TextField();
        preenchido.setText("Água mineral sem gás 500 ml");
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(List.of("Dinheiro", "PIX", "Débito", "Crédito")));
        combo.setValue("PIX");
        FlowPane controles = new FlowPane(10, 10, primario, normal, perigo, desab, campo, preenchido, combo);
        raiz.getChildren().add(secao("Botões e campos", controles));

        TableView<Item> tabela = new TableView<>(FXCollections.observableArrayList(
                new Item("7894900011517", "Refrigerante Coca-Cola lata 350 ml", "24", "R$ 139,20"),
                new Item("1001", "Pão de queijo congelado 1 kg", "6", "R$ 107,40"),
                new Item("7891000100103", "Leite condensado Moça 395 g", "12", "R$ 89,88"),
                new Item("2045", "Açúcar cristal União 5 kg", "4", "R$ 119,60")));
        tabela.getColumns().add(col("Código", Item::codigo, 140, false));
        tabela.getColumns().add(col("Produto", Item::produto, 360, false));
        tabela.getColumns().add(col("Quantidade", Item::quantidade, 110, true));
        tabela.getColumns().add(col("Valor", Item::valor, 120, true));
        tabela.setPrefHeight(170);
        tabela.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        raiz.getChildren().add(secao("Tabela (cabeçalho Inter SemiBold 13 · células Inter 13 · números à direita)", tabela));

        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        x.setLabel("Mês");
        y.setLabel("Vendas (R$ mil)");
        BarChart<String, Number> grafico = new BarChart<>(x, y);
        grafico.setTitle("Vendas por mês — gráfico (título Manrope SemiBold 16)");
        XYChart.Series<String, Number> s1 = new XYChart.Series<>();
        s1.setName("2025");
        XYChart.Series<String, Number> s2 = new XYChart.Series<>();
        s2.setName("2026");
        String[] meses = {"Jan", "Fev", "Mar", "Abr", "Mai", "Jun"};
        int[] a = {42, 38, 51, 47, 55, 60};
        int[] b = {48, 44, 58, 53, 61, 70};
        for (int i = 0; i < meses.length; i++) {
            s1.getData().add(new XYChart.Data<>(meses[i], a[i]));
            s2.getData().add(new XYChart.Data<>(meses[i], b[i]));
        }
        grafico.getData().add(s1);
        grafico.getData().add(s2);
        grafico.setPrefHeight(300);
        grafico.setAnimated(false);

        TabPane abas = new TabPane(new Tab("Gráfico", grafico), new Tab("Ação e informação"));
        abas.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        raiz.getChildren().add(secao("Abas e gráfico (eixos e legenda Inter 12)", abas));

        ScrollPane sp = new ScrollPane(raiz);
        sp.setFitToWidth(true);
        return sp;
    }

    private static VBox status() {
        VBox box = new VBox(4);
        boolean todas = true;
        for (FontLoader.Carregada c : FontLoader.carregar()) {
            todas &= c.ok();
            Label l = new Label(c.ok() ? c.arquivo() + " → família \"" + c.familia() + "\" · " + c.nome()
                    : c.arquivo() + " → NÃO CARREGOU (fallback System)");
            l.getStyleClass().add(c.ok() ? "caption" : "label-strong");
            box.getChildren().add(l);
        }
        Label resumo = new Label(todas ? "Todas as fontes carregaram — nenhuma caiu no fallback."
                : "Atenção: alguma fonte caiu no fallback (System).");
        resumo.getStyleClass().add("label-strong");
        box.getChildren().add(0, resumo);
        return box;
    }

    private static VBox secao(String titulo, javafx.scene.Node corpo) {
        Label t = new Label(titulo);
        t.getStyleClass().add("h3");
        VBox v = new VBox(8, t, corpo);
        v.getStyleClass().add("rg-card");
        v.setPadding(new Insets(14));
        return v;
    }

    private static HBox rotulo(String texto, String classe, String legenda) {
        Label l = new Label(texto);
        l.getStyleClass().add(classe);
        Label c = new Label(legenda);
        c.getStyleClass().add("caption");
        c.setMinWidth(220);
        return new HBox(16, c, l);
    }

    private static VBox kpi(String rotulo, String valor) {
        Label v = new Label(valor);
        v.getStyleClass().add("kpi-value");
        Label r = new Label(rotulo);
        r.getStyleClass().add("kpi-label");
        VBox box = new VBox(2, r, v);
        box.getStyleClass().add("rg-card");
        box.setPadding(new Insets(12, 16, 12, 16));
        box.setMinWidth(220);
        return box;
    }

    private static TableColumn<Item, String> col(String titulo, java.util.function.Function<Item, String> prop, double largura,
            boolean numero) {
        TableColumn<Item, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(prop.apply(cd.getValue())));
        c.setPrefWidth(largura);
        if (numero) {
            c.getStyleClass().add("mono-number");
            c.setStyle("-fx-alignment: CENTER-RIGHT;");
        }
        return c;
    }
}
