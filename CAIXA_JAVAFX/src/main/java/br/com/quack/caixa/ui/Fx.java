package br.com.quack.caixa.ui;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.domain.DomainException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Utilitários de tela: chamadas assíncronas, diálogos modais e botões. */
public final class Fx {
    private static final ExecutorService IO = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "quack-io");
        t.setDaemon(true);
        return t;
    });

    private Fx() {
    }

    /**
     * Fontes embutidas. A interface usa Inter + Manrope ({@link FontLoader}); Nunito e Roboto Mono ficam só para os
     * impressos (etiquetas, cupom, documentos), que têm tamanhos ajustados ao papel. O JavaFX desenha o negrito das
     * fontes do sistema com as letras trocadas nesta configuração do Windows ("Quack" vira "Txdfn"), então nada usa
     * fonte do sistema.
     */
    public static void loadFonts() {
        FontLoader.carregar();
        for (String file : new String[] {"Nunito-Regular.ttf", "Nunito-Bold.ttf", "RobotoMono-Regular.ttf",
                "RobotoMono-Bold.ttf", "Nunito-ExtraBoldItalic.ttf"}) {
            try (var in = Fx.class.getResourceAsStream("/br/com/quack/caixa/fonts/" + file)) {
                if (in == null || javafx.scene.text.Font.loadFont(in, 14) == null) {
                    System.err.println("Fonte não carregada: " + file);
                }
            } catch (java.io.IOException e) {
                System.err.println("Fonte não carregada: " + file + " (" + e.getMessage() + ")");
            }
        }
    }

    // Ícones desenhados (SVG 24x24): emojis não renderizam de forma confiável no JavaFX.
    public static final String ICON_ARROW_UP = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm0 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm0 3l4.5 4.5-1.4 1.4L13 10.8V17h-2v-6.2l-2.1 2.1-1.4-1.4L12 7z";
    public static final String ICON_ARROW_DOWN = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm0 2a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm1 3v6.2l2.1-2.1 1.4 1.4L12 17l-4.5-4.5 1.4-1.4 2.1 2.1V7h2z";
    public static final String ICON_LOCK = "M7 10V7a5 5 0 0 1 10 0v3h2v12H5V10h2zm2 0h6V7a3 3 0 0 0-6 0v3z";
    public static final String ICON_UNLOCK = "M7 10V7a5 5 0 0 1 9.6-2l-1.8.9A3 3 0 0 0 9 7v3h10v12H5V10h2z";
    public static final String ICON_REFRESH = "M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z";
    public static final String ICON_PRINTER = "M6 2h12v5H6V2zm-2 6h16a2 2 0 0 1 2 2v7h-4v5H6v-5H2v-7a2 2 0 0 1 2-2zm4 9v3h8v-3H8zm10-6a1 1 0 1 0 0 2 1 1 0 0 0 0-2z";
    public static final String ICON_RECEIPT_SMALL = "M5 2h14v20l-2.33-1.5L14.33 22 12 20.5 9.67 22 7.33 20.5 5 22V2zm2 2v14.3l.33-.21L9.67 19.6 12 18.1l2.33 1.5 2.34-1.5.33.21V4H7zm2 3h6v2H9V7zm0 4h6v2H9v-2z";

    /** Ícone SVG reduzido para caber num botão (24px -> ~14px). */
    public static javafx.scene.Node icon(String path, String styleClass) {
        javafx.scene.shape.SVGPath p = new javafx.scene.shape.SVGPath();
        p.setContent(path);
        p.getStyleClass().add(styleClass);
        p.setScaleX(0.62);
        p.setScaleY(0.62);
        return new javafx.scene.Group(p);
    }

    public static String stylesheet() {
        return Fx.class.getResource("quack.css").toExternalForm();
    }

    /** Tema "vidro" (painéis translúcidos sobre fundo escuro com brilhos): glass.css por cima do quack.css. */
    private static volatile boolean glass;

    public static boolean glass() {
        return glass;
    }

    /** Folhas de estilo do tema atual (use em toda cena/popup no lugar de stylesheet()). */
    public static java.util.List<String> stylesheets() {
        // typography.css primeiro (base: Inter/Manrope e escala); o tema ajusta componentes específicos por cima.
        return glass ? java.util.List.of(typography(), stylesheet(), Fx.class.getResource("glass.css").toExternalForm())
                : java.util.List.of(typography(), stylesheet());
    }

    /** Tipografia (Inter + Manrope), comum aos apps JavaFX: /css/typography.css. */
    public static String typography() {
        return Fx.class.getResource("/css/typography.css").toExternalForm();
    }

    /** Liga/desliga o tema vidro e reaplica em todas as janelas abertas (cenas e popups). */
    public static void setGlass(boolean on) {
        glass = on;
        for (javafx.stage.Window w : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
            Scene sc = w.getScene();
            if (sc == null) {
                continue;
            }
            if (!sc.getStylesheets().isEmpty()) {
                sc.getStylesheets().setAll(stylesheets());
            }
            if (sc.getRoot() != null && !sc.getRoot().getStylesheets().isEmpty()) {
                sc.getRoot().getStylesheets().setAll(stylesheets());
            }
            if (sc.getRoot() instanceof Parent p) {
                for (javafx.scene.Node n : p.getChildrenUnmodifiable()) {
                    if (n instanceof Parent child && !child.getStylesheets().isEmpty()) {
                        child.getStylesheets().setAll(stylesheets());
                    }
                }
            }
        }
    }

    /** Executa fora da FX thread e devolve o resultado na FX thread. */
    public static <T> void async(Supplier<T> work, Consumer<T> ok, Consumer<Throwable> err) {
        IO.submit(() -> {
            try {
                T r = work.get();
                Platform.runLater(() -> ok.accept(r));
            } catch (Throwable t) {
                Platform.runLater(() -> err.accept(t));
            }
        });
    }

    public static void shutdown() {
        IO.shutdownNow();
    }

    public static String message(Throwable t) {
        if (t instanceof DomainException || t instanceof ApiException) {
            return t.getMessage();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    public static Button button(String text, String styleClass, Runnable action) {
        Button b = new Button(text);
        if (styleClass != null) {
            b.getStyleClass().addAll(styleClass.split(" "));
        }
        b.setOnAction(e -> action.run());
        return b;
    }

    public static boolean confirm(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO);
        a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(null);
        a.getDialogPane().getStylesheets().setAll(stylesheets());
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    /**
     * Modal em cartão, como os da web: sem barra do Windows, cantos arredondados, sombra, fundo da
     * janela de trás escurecido e Esc fecha. O conteúdo deve trazer o próprio cabeçalho (modalHeader).
     */
    public static Stage card(Window owner, javafx.scene.layout.Region content) {
        Stage st = new Stage(javafx.stage.StageStyle.TRANSPARENT);
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        content.getStyleClass().add("rg-modal");
        javafx.scene.layout.StackPane wrap = new javafx.scene.layout.StackPane(content);
        wrap.getStyleClass().add("rg-modal-wrap");
        Scene sc = new Scene(wrap);
        sc.setFill(javafx.scene.paint.Color.TRANSPARENT);
        sc.getStylesheets().setAll(stylesheets());
        sc.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                if (!isBusy(st)) {
                    st.close();
                }
                e.consume();
            }
        });
        st.setScene(sc);
        javafx.scene.Parent ownerRoot = owner != null && owner.getScene() != null ? owner.getScene().getRoot() : null;
        javafx.scene.effect.Effect[] previous = new javafx.scene.effect.Effect[1];
        // addEventHandler (e não setOn...): um modal que use setOnHidden não pode apagar a restauração do fundo.
        st.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWING, e -> {
            if (ownerRoot != null) {
                previous[0] = ownerRoot.getEffect();
                ownerRoot.setEffect(new javafx.scene.effect.ColorAdjust(0, -0.2, -0.45, 0));
            }
        });
        // Centraliza na janela PRINCIPAL do caixa (não no modal que abriu este) e recentraliza quando o
        // conteúdo muda de tamanho (etapas, mensagens): um modal aberto sobre outro menor ficava deslocado.
        st.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> center(st, owner));
        st.widthProperty().addListener((o, a, b) -> {
            if (st.isShowing()) {
                center(st, owner);
            }
        });
        st.heightProperty().addListener((o, a, b) -> {
            if (st.isShowing()) {
                center(st, owner);
            }
        });
        st.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> {
            if (ownerRoot != null) {
                ownerRoot.setEffect(previous[0]);
            }
        });
        return st;
    }

    /** Janela principal: sobe pelos donos até a janela que não é um modal (ex.: a frente de caixa). */
    static Window mainWindow(Window owner) {
        Window w = owner;
        while (w instanceof Stage s && s.getOwner() != null) {
            w = s.getOwner();
        }
        return w;
    }

    /**
     * Centraliza o modal na janela principal, sem sair da área útil da tela. Se o modal for mais alto que a
     * tela, encosta no topo (o conteúdo longo tem rolagem).
     */
    static void center(Stage st, Window owner) {
        Window anchor = mainWindow(owner);
        javafx.geometry.Rectangle2D area;
        if (anchor != null) {
            var screens = javafx.stage.Screen.getScreensForRectangle(anchor.getX(), anchor.getY(),
                    Math.max(1, anchor.getWidth()), Math.max(1, anchor.getHeight()));
            area = (screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.get(0)).getVisualBounds();
        } else {
            area = javafx.stage.Screen.getPrimary().getVisualBounds();
        }
        double cx = anchor != null ? anchor.getX() + anchor.getWidth() / 2 : area.getMinX() + area.getWidth() / 2;
        double cy = anchor != null ? anchor.getY() + anchor.getHeight() / 2 : area.getMinY() + area.getHeight() / 2;
        double x = cx - st.getWidth() / 2;
        double y = cy - st.getHeight() / 2;
        x = Math.max(area.getMinX(), Math.min(x, area.getMaxX() - st.getWidth()));
        y = Math.max(area.getMinY(), Math.min(y, area.getMaxY() - st.getHeight()));
        st.setX(x);
        st.setY(y);
    }

    /** Marca o modal como ocupado (ex.: venda sendo confirmada): Esc e o X não fecham. */
    public static void setBusy(Stage st, boolean busy) {
        st.getProperties().put("rg-busy", busy);
    }

    public static boolean isBusy(Stage st) {
        return Boolean.TRUE.equals(st.getProperties().get("rg-busy"));
    }

    /** Cabeçalho dos modais: título, subtítulo opcional e botão de fechar. */
    public static javafx.scene.layout.HBox modalHeader(String title, javafx.scene.Node subtitle, Runnable onClose) {
        javafx.scene.control.Label t = new javafx.scene.control.Label(title);
        t.getStyleClass().add("rg-modal-title");
        javafx.scene.layout.VBox texts = subtitle == null ? new javafx.scene.layout.VBox(t)
                : new javafx.scene.layout.VBox(2, t, subtitle);
        javafx.scene.layout.Region sp = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(sp, javafx.scene.layout.Priority.ALWAYS);
        Button close = button("✕", "rg-btn-icon", onClose);
        close.setFocusTraversable(false);
        javafx.scene.layout.HBox h = new javafx.scene.layout.HBox(8, texts, sp, close);
        h.setAlignment(javafx.geometry.Pos.TOP_LEFT);
        return h;
    }

    /** Cria uma janela modal com Esc para fechar. */
    public static Stage modal(Window owner, String title, Parent root) {
        Stage st = new Stage();
        st.initOwner(owner);
        st.initModality(Modality.WINDOW_MODAL);
        st.setTitle(title);
        Scene sc = new Scene(root);
        sc.getStylesheets().setAll(stylesheets());
        sc.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                st.close();
                e.consume();
            }
        });
        st.setScene(sc);
        return st;
    }
}
