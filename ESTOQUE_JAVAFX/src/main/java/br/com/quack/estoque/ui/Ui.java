package br.com.quack.estoque.ui;

import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Fmt;
import java.math.BigDecimal;
import java.util.function.Function;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Construtores de tela reutilizados pelas views (tabela, campos, cabeçalhos). */
public final class Ui {
    private Ui() {
    }

    public static Label texto(String s, String... classes) {
        Label l = new Label(s);
        l.getStyleClass().addAll(classes);
        return l;
    }

    public static Label muted(String s) {
        Label l = texto(s, "rg-muted");
        l.setWrapText(true);
        return l;
    }

    public static Region espaco() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Título da página, subtítulo opcional e botões à direita. */
    public static HBox cabecalho(String titulo, String subtitulo, Node... acoes) {
        VBox textos = new VBox(2, texto(titulo, "rg-page-title"));
        if (subtitulo != null && !subtitulo.isBlank()) {
            Label s = texto(subtitulo, "rg-page-sub");
            s.setWrapText(true);
            textos.getChildren().add(s);
        }
        HBox h = new HBox(8, textos, espaco());
        h.getChildren().addAll(acoes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static HBox barra(Node... filhos) {
        HBox h = new HBox(8, filhos);
        h.getStyleClass().add("rg-toolbar");
        return h;
    }

    public static Button botao(String texto, Runnable acao) {
        return Fx.button(texto, null, acao);
    }

    public static Button primario(String texto, Runnable acao) {
        return Fx.button(texto, "rg-btn-primary", acao);
    }

    public static Button perigo(String texto, Runnable acao) {
        return Fx.button(texto, "rg-btn-danger", acao);
    }

    public static Button pequeno(String texto, Runnable acao) {
        return Fx.button(texto, "rg-btn-small", acao);
    }

    public static TextField campo(String prompt) {
        TextField t = new TextField();
        t.setPromptText(prompt);
        return t;
    }

    public static VBox rotulado(String rotulo, Node entrada) {
        VBox v = new VBox(3, texto(rotulo, "rg-form-label"), entrada);
        if (entrada instanceof Region r) {
            r.setMaxWidth(Double.MAX_VALUE);
        }
        return v;
    }

    public static <T> TableView<T> tabela() {
        TableView<T> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(new Label("Nada para mostrar."));
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    public static <T> TableColumn<T, String> col(TableView<T> t, String titulo, Function<T, String> f, double largura) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(nz(f.apply(cd.getValue()))));
        c.setPrefWidth(largura);
        c.setSortable(false);
        t.getColumns().add(c);
        return c;
    }

    /** Coluna de número, alinhada à direita. */
    public static <T> TableColumn<T, String> colNum(TableView<T> t, String titulo, Function<T, String> f, double largura) {
        TableColumn<T, String> c = col(t, titulo, f, largura);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        return c;
    }

    public static String nz(String s) {
        return s == null ? "" : s;
    }

    public static <T> ComboBox<T> combo(java.util.List<T> itens) {
        ComboBox<T> c = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(itens));
        c.setMaxWidth(Double.MAX_VALUE);
        return c;
    }

    public static CheckBox check(String texto, boolean marcado) {
        CheckBox c = new CheckBox(texto);
        c.setSelected(marcado);
        return c;
    }

    /** Lê um número (pt-BR) de um campo; vazio vira erro com o nome do campo. */
    public static BigDecimal numero(TextField f, String nome) {
        String t = f.getText() == null ? "" : f.getText().trim();
        if (t.isEmpty()) {
            throw new DomainException("Informe " + nome + ".");
        }
        try {
            return Fmt.parse(t);
        } catch (DomainException e) {
            throw new DomainException("Valor inválido em " + nome + ": " + t);
        }
    }

    public static BigDecimal numeroOuZero(TextField f) {
        return f.getText() == null || f.getText().isBlank() ? BigDecimal.ZERO : Fmt.parseOrZero(f.getText());
    }

    /** Janela do nó (para toasts e donos de modais). */
    public static Window janela(Node n) {
        return n == null || n.getScene() == null ? null : n.getScene().getWindow();
    }

    public static Insets margem(double v) {
        return new Insets(v);
    }

    /** Caixa colorida com um texto: aviso (amarelo), perigo (vermelho) ou ok (verde). */
    public static Label caixa(String texto, String classe) {
        Label l = new Label(texto);
        l.setWrapText(true);
        l.getStyleClass().add(classe);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }
}
