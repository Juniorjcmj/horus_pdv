package br.com.quack.estoque.ui;

import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Documento de impressão em A4 (pedido de compra, relatórios): título, subtítulo, tabela e rodapé como linhas de um VBox,
 * para a impressão virar a página entre linhas (PrintService.pageRanges) sem cortar texto.
 */
public final class Documento {
    public static final double LARGURA = 560;

    /** Coluna: título, peso (largura relativa) e alinhamento à direita para números. */
    public record Col(String titulo, double peso, boolean direita) {
        public static Col texto(String titulo, double peso) {
            return new Col(titulo, peso, false);
        }

        public static Col numero(String titulo, double peso) {
            return new Col(titulo, peso, true);
        }
    }

    private Documento() {
    }

    public static VBox tabela(String titulo, List<String> subtitulos, List<Col> colunas, List<List<String>> linhas,
            List<String> rodape) {
        VBox raiz = new VBox(2);
        raiz.getStyleClass().add("rg-doc");
        raiz.setPrefWidth(LARGURA);
        raiz.setMinWidth(LARGURA);
        raiz.setMaxWidth(LARGURA);
        Label t = new Label(titulo);
        t.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        raiz.getChildren().add(t);
        for (String s : subtitulos) {
            Label l = new Label(s);
            l.setWrapText(true);
            l.setMaxWidth(LARGURA - 32);
            raiz.getChildren().add(l);
        }
        raiz.getChildren().add(linha(colunas, colunas.stream().map(Col::titulo).toList(), true));
        for (List<String> l : linhas) {
            raiz.getChildren().add(linha(colunas, l, false));
        }
        for (String r : rodape) {
            Label l = new Label(r);
            l.setStyle("-fx-font-weight: bold; -fx-padding: 6 0 0 0;");
            raiz.getChildren().add(l);
        }
        return raiz;
    }

    private static HBox linha(List<Col> colunas, List<String> valores, boolean cabecalho) {
        double soma = colunas.stream().mapToDouble(Col::peso).sum();
        double disponivel = LARGURA - 32;
        HBox h = new HBox();
        h.setStyle(cabecalho ? "-fx-border-color: transparent transparent -border-strong transparent; -fx-padding: 8 0 3 0;"
                : "-fx-border-color: transparent transparent -border transparent; -fx-padding: 2 0 2 0;");
        for (int i = 0; i < colunas.size(); i++) {
            Label l = new Label(i < valores.size() && valores.get(i) != null ? valores.get(i) : "");
            double w = disponivel * colunas.get(i).peso() / soma;
            l.setPrefWidth(w);
            l.setMinWidth(w);
            l.setMaxWidth(w);
            l.setWrapText(false);
            l.setAlignment(colunas.get(i).direita() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            l.setStyle("-fx-font-size: 10px;" + (cabecalho ? " -fx-font-weight: bold;" : ""));
            h.getChildren().add(l);
        }
        return h;
    }
}
