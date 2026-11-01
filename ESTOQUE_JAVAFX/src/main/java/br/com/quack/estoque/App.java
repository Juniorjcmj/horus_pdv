package br.com.quack.estoque;

import br.com.quack.estoque.ui.Fx;
import br.com.quack.estoque.ui.LoginView;
import br.com.quack.estoque.ui.MainView;
import br.com.quack.estoque.ui.Toasts;
import java.util.ArrayList;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Quack Estoque: login pelo mesmo servidor do caixa e janela principal por perfil. */
public class App extends Application {
    private AppContext ctx;
    private Stage stage;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        Fx.loadFonts();
        this.stage = primary;
        this.ctx = new AppContext();
        Fx.setGlass(ctx.config.glassTheme());
        stage.setTitle("Quack Estoque");
        String icon = App.class.getResource("ui/quack-icon.png").toExternalForm();
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new javafx.scene.image.Image(icon, size, size, true, true));
        }
        // Cabe em notebook 1366×768 (área útil ~720 px de altura com a barra de tarefas): mínimo nunca maior que a tela.
        javafx.geometry.Rectangle2D area = javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setMinWidth(Math.min(1024, area.getWidth()));
        stage.setMinHeight(Math.min(600, area.getHeight()));
        stage.setWidth(Math.min(1280, area.getWidth()));
        stage.setHeight(Math.min(800, area.getHeight()));
        Double salvo = ctx.config.zoom();
        br.com.quack.estoque.ui.Zoom.set(salvo != null ? salvo : br.com.quack.estoque.ui.Zoom.padraoPara(area));
        stage.setMaximized(true);
        // 401 depois do login: volta ao login sem perder os rascunhos (conferência de NF-e e contagens ficam em disco).
        ctx.api.onUnauthorized(() -> Platform.runLater(() -> {
            if (ctx.session.loggedIn()) {
                showLogin("Sessão expirada. Entre novamente: a conferência de entrada e as contagens não enviadas foram guardadas neste computador.");
            }
        }));
        showLogin(null);
        stage.show();
    }

    private void setRoot(Parent root) {
        javafx.geometry.Rectangle2D area = javafx.stage.Screen.getPrimary().getVisualBounds();
        double w = stage.getScene() != null ? stage.getScene().getWidth() : Math.min(1280, area.getWidth());
        double h = stage.getScene() != null ? stage.getScene().getHeight() : Math.min(800, area.getHeight());
        Scene scene = new Scene(br.com.quack.estoque.ui.Zoom.envolver(root), w, h);
        scene.getStylesheets().setAll(Fx.stylesheets());
        // Ctrl + − / Ctrl + = (ou +) / Ctrl + 0: zoom da interface, salvo neste computador.
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!e.isShortcutDown()) {
                return;
            }
            double atual = br.com.quack.estoque.ui.Zoom.get();
            Double novo = switch (e.getCode()) {
                case MINUS, SUBTRACT -> atual - 0.05;
                case EQUALS, PLUS, ADD -> atual + 0.05;
                case DIGIT0, NUMPAD0 -> br.com.quack.estoque.ui.Zoom.padraoPara(area);
                default -> null;
            };
            if (novo != null) {
                br.com.quack.estoque.ui.Zoom.set(novo);
                ctx.config.setZoom(e.getCode() == javafx.scene.input.KeyCode.DIGIT0
                        || e.getCode() == javafx.scene.input.KeyCode.NUMPAD0 ? null : br.com.quack.estoque.ui.Zoom.get());
                Toasts.info(stage, "Tamanho da tela: " + Math.round(br.com.quack.estoque.ui.Zoom.get() * 100) + "% (Ctrl + − / Ctrl + = / Ctrl + 0)");
                e.consume();
            }
        });
        stage.setScene(scene);
    }

    private void fecharJanelasAbertas() {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w != stage && w.isShowing()) {
                w.hide();
            }
        }
    }

    private void showLogin(String aviso) {
        fecharJanelasAbertas();
        ctx.session.clear();
        LoginView[] view = new LoginView[1];
        view[0] = new LoginView(ctx.config.apiBaseUrl(), ctx.config.rememberEmail() ? ctx.config.lastEmail() : "", ctx.config.rememberEmail(), aviso,
                (url, email, senha, lembrar) -> login(view[0], url, email, senha, lembrar), url -> getHostServices().showDocument(url),
                ctx.config.webBaseUrl());
        setRoot(view[0]);
        Platform.runLater(view[0]::focusFirst);
    }

    private void login(LoginView view, String url, String email, String senha, boolean lembrar) {
        view.setBusy(true);
        ctx.api.setBaseUrl(url);
        Fx.async(() -> {
            ctx.entrar(email, senha); // recusa caixa, atendente e demais perfis
            ctx.garantirCatalogo(true);
            return true;
        }, ok -> {
            ctx.config.setApiBaseUrl(url);
            ctx.config.setRememberEmail(lembrar);
            ctx.config.setLastEmail(lembrar ? email : "");
            mostrarPrincipal();
        }, t -> {
            ctx.session.clear();
            view.setBusy(false);
            view.showError(Fx.message(t));
        });
    }

    private void mostrarPrincipal() {
        MainView principal = new MainView(ctx, () -> Fx.async(() -> {
            ctx.authApi.logout();
            return true;
        }, ok -> showLogin(null), t -> showLogin(null)));
        setRoot(principal);
        principal.instalar(stage.getScene());
        Toasts.info(stage, "Bem-vindo, " + ctx.session.user().name() + " (" + ctx.session.perfil().rotulo() + ").");
    }

    @Override
    public void stop() {
        Fx.shutdown();
    }
}
