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
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
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
        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().setAll(Fx.stylesheets());
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
