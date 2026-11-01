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
        r.getStyleClass().add("rg-espaco");
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    /** Título da página, subtítulo opcional e botões à direita. */
    public static HBox cabecalho(String titulo, String subtitulo, Node... acoes) {
        VBox textos = new VBox(2, texto(titulo, "h2", "rg-page-title"));
        if (subtitulo != null && !subtitulo.isBlank()) {
            Label s = texto(subtitulo, "text-secondary", "rg-page-sub");
            s.setWrapText(true);
            textos.getChildren().add(s);
        }
        textos.setMinWidth(0);
        HBox.setHgrow(textos, Priority.ALWAYS);
        HBox h = new HBox(8, textos);
        for (Node a : acoes) {
            if (a instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
            h.getChildren().add(a);
        }
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /**
     * Barra de filtros/botões que quebra linha quando a janela é estreita (FlowPane). Espaçadores ({@link #espaco()}) são
     * ignorados; um campo marcado para crescer (HBox.hgrow) ganha largura fixa confortável.
     */
    public static javafx.scene.layout.FlowPane barra(Node... filhos) {
        javafx.scene.layout.FlowPane f = new javafx.scene.layout.FlowPane(8, 8);
        f.getStyleClass().add("rg-toolbar");
        for (Node n : filhos) {
            if (n.getStyleClass().contains("rg-espaco")) {
                continue;
            }
            if (n instanceof javafx.scene.control.TextField t && HBox.getHgrow(t) == Priority.ALWAYS) {
                t.setPrefWidth(Math.max(t.getPrefWidth(), 340));
                t.setMinWidth(220);
            }
            if (n instanceof Button b) {
                b.setMinWidth(Region.USE_PREF_SIZE);
            }
            f.getChildren().add(n);
        }
        return f;
    }

    private static Button inteiro(Button b) {
        b.setMinWidth(Region.USE_PREF_SIZE); // botão nunca vira "..."
        return b;
    }

    public static Button botao(String texto, Runnable acao) {
        return inteiro(Fx.button(texto, null, acao));
    }

    public static Button primario(String texto, Runnable acao) {
        return inteiro(Fx.button(texto, "rg-btn-primary", acao));
    }

    public static Button perigo(String texto, Runnable acao) {
        return inteiro(Fx.button(texto, "rg-btn-danger", acao));
    }

    public static Button pequeno(String texto, Runnable acao) {
        return inteiro(Fx.button(texto, "rg-btn-small", acao));
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
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        t.setPlaceholder(new Label("Nada para mostrar."));
        VBox.setVgrow(t, Priority.ALWAYS);
        t.widthProperty().addListener((o, a, b) -> colunasVisiveis(t));
        t.getColumns().addListener((javafx.collections.ListChangeListener<TableColumn<T, ?>>) ch -> colunasVisiveis(t));
        return t;
    }

    /**
     * Ordem em que as colunas saem quando a tabela fica estreita (primeiro da lista sai primeiro). Elas voltam quando a
     * janela cresce. Colunas fora da lista (produto, quantidades, valores) nunca saem.
     */
    static final java.util.List<String> OPCIONAIS = java.util.List.of("Categoria", "Máx.", "Mín.", "Mín./Máx.", "Usuário",
            "Aberto por", "Escopo", "Último impresso", "Código de barras", "1ª contagem", "Itens", "Situação", "Cobertura",
            "Un", "Fornecedor", "Código");

    /** Mostra todas as colunas e esconde as opcionais (na ordem de {@link #OPCIONAIS}) até a soma dos mínimos caber. */
    static <T> void colunasVisiveis(TableView<T> t) {
        double largura = t.getWidth() - 20; // barra de rolagem vertical
        if (largura <= 40) {
            return;
        }
        java.util.List<TableColumn<T, ?>> opcionais = new java.util.ArrayList<>();
        double necessario = 0;
        for (TableColumn<T, ?> c : t.getColumns()) {
            if (OPCIONAIS.contains(titulo(c))) {
                opcionais.add(c);
            }
            necessario += c.getMinWidth();
        }
        opcionais.sort(java.util.Comparator.comparingInt(c -> OPCIONAIS.indexOf(titulo(c))));
        for (TableColumn<T, ?> c : opcionais) {
            boolean esconder = necessario > largura;
            if (esconder) {
                necessario -= c.getMinWidth();
            }
            if (c.isVisible() == esconder) {
                c.setVisible(!esconder);
            }
        }
    }

    /** Título da coluna, mesmo quando ele virou um rótulo com dica. */
    public static String titulo(TableColumn<?, ?> c) {
        if (c.getText() != null) {
            return c.getText();
        }
        return c.getGraphic() instanceof Label l ? l.getText() : "";
    }

    public static <T> TableColumn<T, String> col(TableView<T> t, String titulo, Function<T, String> f, double largura) {
        TableColumn<T, String> c = new TableColumn<>(titulo);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(nz(f.apply(cd.getValue()))));
        c.setPrefWidth(largura);
        c.setSortable(false);
        ajustar(c, minimoTexto(titulo, largura), titulo);
        t.getColumns().add(c);
        return c;
    }

    /** Largura mínima de uma coluna de texto pelo que ela costuma mostrar. */
    static double minimoTexto(String titulo, double largura) {
        return switch (titulo) {
            case "Produto", "Descrição" -> 150;
            case "Código" -> 100; // EAN-13 inteiro
            case "Un" -> 40;
            case "Data", "Recebida em", "Aberto em", "1ª contagem" -> 132; // dd/MM/aaaa HH:mm
            default -> Math.max(70, Math.min(largura, largura * 0.6));
        };
    }

    /**
     * Largura mínima (o número/texto curto nunca fica cortado) e dica com o texto completo da célula e do título.
     * Usar nas tabelas com muitas colunas junto de {@link #distribuir(TableView)}.
     */
    public static <T> TableColumn<T, String> ajustar(TableColumn<T, String> c, double minimo, String dicaTitulo) {
        c.setMinWidth(minimo);
        if (c.getPrefWidth() < minimo) {
            c.setPrefWidth(minimo);
        }
        if (dicaTitulo != null) {
            if (c.getGraphic() instanceof Label atual) {
                atual.setTooltip(new javafx.scene.control.Tooltip(dicaTitulo));
            } else {
                Label h = new Label(c.getText());
                h.setTooltip(new javafx.scene.control.Tooltip(dicaTitulo));
                c.setText(null);
                c.setGraphic(h);
            }
        }
        String estilo = c.getStyle();
        c.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setTooltip(empty || item == null || item.isBlank() ? null : new javafx.scene.control.Tooltip(item));
                setStyle(estilo);
            }
        });
        return c;
    }

    /** Espalha a sobra de largura entre todas as colunas (proporcional), respeitando os mínimos. */
    public static <T> void distribuir(TableView<T> t) {
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
    }

    /** Coluna de número, alinhada à direita: mínimo para o número caber e sem crescer à toa. */
    public static <T> TableColumn<T, String> colNum(TableView<T> t, String titulo, Function<T, String> f, double largura) {
        TableColumn<T, String> c = col(t, titulo, f, largura);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.getStyleClass().add("mono-number");
        ajustar(c, Math.max(50, largura * 0.8), null);
        c.setMaxWidth(largura + 40);
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
