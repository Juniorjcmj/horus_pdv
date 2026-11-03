package br.com.quack.estoque.ui;

import br.com.quack.estoque.labels.LabelModel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;

/**
 * Pré-visualização da etiqueta: centralizada na área livre, em "Ajustar" (ampliada para ler) ou "Tamanho real" (os
 * milímetros da etiqueta na tela, descontando o zoom do app). Mostra a borda da etiqueta e o deslocamento do ajuste fino,
 * como a impressora vai aplicar.
 */
public final class EtiquetaPrevia extends VBox {
    /** Pontos (1/72") → pixels da tela (96 dpi). */
    private static final double TELA = 96.0 / 72.0;
    private static final double MARGEM = 24;
    private static final double AMPLIACAO_MAX = 3.5;

    private final StackPane area = new StackPane();
    private final Label legenda = Ui.muted("");
    private final Label escalaTxt = Ui.texto("", "rg-muted");
    private final ToggleButton ajustar = new ToggleButton("Ajustar");
    private final ToggleButton real = new ToggleButton("Tamanho real");
    private Node etiqueta;
    private LabelModel modelo;
    private double dxPt;
    private double dyPt;

    public EtiquetaPrevia() {
        super(8);
        ToggleGroup g = new ToggleGroup();
        ajustar.setToggleGroup(g);
        real.setToggleGroup(g);
        ajustar.setSelected(true);
        ajustar.getStyleClass().add("rg-btn-small");
        real.getStyleClass().add("rg-btn-small");
        g.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) {
                (a == null ? ajustar : (ToggleButton) a).setSelected(true); // sempre um modo marcado
            } else {
                redesenhar();
            }
        });
        Label titulo = Ui.texto("Pré-visualização", "rg-section-title");
        HBox topo = new HBox(6, titulo, Ui.espaco(), escalaTxt, ajustar, real);
        ajustar.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        real.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        topo.setAlignment(Pos.CENTER_LEFT);

        area.getStyleClass().add("rg-label-stage");
        area.setMinHeight(150);
        // A área segue a largura da coluna; a etiqueta é que se ajusta a ela (nunca empurra a janela).
        area.setMinWidth(0);
        area.setPrefWidth(0);
        area.setMaxWidth(Double.MAX_VALUE);
        Rectangle recorte = new Rectangle();
        recorte.widthProperty().bind(area.widthProperty());
        recorte.heightProperty().bind(area.heightProperty());
        area.setClip(recorte);
        setMinWidth(0);
        area.setPadding(new Insets(MARGEM));
        VBox.setVgrow(area, Priority.ALWAYS);
        area.widthProperty().addListener((o, a, b) -> redesenhar());
        area.heightProperty().addListener((o, a, b) -> redesenhar());
        Zoom.fator().addListener((o, a, b) -> redesenhar());
        legenda.setWrapText(true);
        getChildren().addAll(topo, area, legenda);
        vazio("Adicione produtos à fila para ver a etiqueta.");
    }

    /** Mostra uma mensagem no lugar da etiqueta. */
    public void vazio(String mensagem) {
        etiqueta = null;
        modelo = null;
        Label l = Ui.muted(mensagem);
        l.setAlignment(Pos.CENTER);
        area.getChildren().setAll(l);
        legenda.setText("");
        escalaTxt.setText("");
    }

    /**
     * Mostra a etiqueta desenhada (nó em pontos, do tamanho do modelo).
     *
     * @param dxMm deslocamento horizontal do ajuste fino (mm)
     * @param dyMm deslocamento vertical do ajuste fino (mm)
     */
    public void mostrar(Node desenho, LabelModel m, double dxMm, double dyMm, String legendaTexto) {
        this.etiqueta = desenho;
        this.modelo = m;
        this.dxPt = dxMm * 72 / 25.4;
        this.dyPt = dyMm * 72 / 25.4;
        legenda.setText(legendaTexto);
        redesenhar();
    }

    /** Escala da etiqueta na tela para o modo atual. */
    static double escala(boolean tamanhoReal, double larguraPt, double alturaPt, double areaW, double areaH, double zoomApp) {
        if (tamanhoReal) {
            return TELA / zoomApp; // o zoom do app reduz tudo: compensa para dar os milímetros reais
        }
        double s = Math.min(areaW / larguraPt, areaH / alturaPt);
        return Math.max(0.2, Math.min(AMPLIACAO_MAX, s));
    }

    private void redesenhar() {
        if (etiqueta == null || modelo == null) {
            return;
        }
        double w = modelo.larguraPt();
        double h = modelo.alturaPt();
        double areaW = Math.max(40, area.getWidth() - 2 * MARGEM);
        double areaH = Math.max(40, area.getHeight() - 2 * MARGEM);
        double s = escala(real.isSelected(), w, h, areaW, areaH, Zoom.get());

        // Papel da etiqueta (branco, com sombra) e o desenho deslocado pelo ajuste fino, recortado na borda.
        Rectangle papel = new Rectangle(w, h, Color.WHITE);
        papel.setArcWidth(Math.min(w, h) * 0.06);
        papel.setArcHeight(Math.min(w, h) * 0.06);
        Pane conteudo = new Pane(etiqueta);
        etiqueta.setLayoutX(dxPt);
        etiqueta.setLayoutY(dyPt);
        conteudo.setPrefSize(w, h);
        conteudo.setMinSize(w, h);
        conteudo.setMaxSize(w, h);
        conteudo.setClip(new Rectangle(w, h));
        Rectangle borda = new Rectangle(w, h);
        borda.setFill(Color.TRANSPARENT);
        borda.setStroke(Color.web("#9aa3b2"));
        borda.getStrokeDashArray().setAll(4.0 / s, 3.0 / s);
        borda.setStrokeWidth(1 / s);
        borda.setArcWidth(papel.getArcWidth());
        borda.setArcHeight(papel.getArcHeight());
        Group desenho = new Group(papel, conteudo, borda);
        papel.setEffect(new DropShadow(10 / s, 0, 2 / s, Color.rgb(0, 0, 0, 0.18)));
        desenho.getTransforms().setAll(new Scale(s, s, 0, 0));
        Group envoltorio = new Group(desenho); // bounds já escalados: o StackPane centraliza certo
        area.getChildren().setAll(envoltorio);

        int pct = (int) Math.round(s / (TELA / Zoom.get()) * 100);
        escalaTxt.setText(real.isSelected() ? "100%" : pct + "%");
    }
}
