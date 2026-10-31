package br.com.quack.caixa;

import br.com.quack.caixa.ui.Fx;
import br.com.quack.caixa.ui.LoginView;
import br.com.quack.caixa.ui.PdvView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {
    private AppContext ctx;
    private Stage stage;
    private PdvView pdv;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        Fx.loadFonts();
        this.stage = primary;
        this.ctx = new AppContext();
        Fx.setGlass(ctx.config.glassTheme());
        stage.setTitle("Quack Caixa");
        // Ícone do patinho (o mesmo da web), em vários tamanhos para a barra de tarefas e o Alt+Tab.
        String icon = App.class.getResource("ui/quack-icon.png").toExternalForm();
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new javafx.scene.image.Image(icon, size, size, true, true));
        }
        stage.setMinWidth(1024);
        stage.setMinHeight(700);
        stage.setMaximized(true);
        ctx.api.onUnauthorized(() -> Platform.runLater(() -> {
            // Login offline: 401 só quer dizer "API voltou, falta refazer o login" (o SyncWorker refaz).
            if (ctx.session.loggedIn() && !ctx.session.offlineAuth()) {
                showLogin("Sessão expirada. Entre novamente.");
            }
        }));
        ctx.onRevalidationFailed(() -> Platform.runLater(() -> {
            if (ctx.session.loggedIn()) {
                showLogin("A conexão voltou, mas o servidor não aceitou o seu acesso. Entre novamente para "
                        + "enviar as vendas guardadas.");
            }
        }));
        java.util.List<String> avisos = br.com.quack.caixa.persistence.LocalDb.drainNotices();
        showLogin(avisos.isEmpty() ? null : String.join(" ", avisos));
        stage.show();
    }

    private void setRoot(javafx.scene.Parent root) {
        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().setAll(Fx.stylesheets());
        stage.setScene(scene);
    }

    private void showLogin(String notice) {
        if (pdv != null) {
            pdv.dispose();
            pdv = null;
        }
        ctx.sync.stop();
        ctx.session.clear();
        LoginView[] view = new LoginView[1];
        view[0] = new LoginView(ctx.config.apiBaseUrl(),
                ctx.config.rememberEmail() ? ctx.config.lastEmail() : "", ctx.config.rememberEmail(), notice,
                (url, email, password, remember) -> login(view[0], url, email, password, remember),
                url -> getHostServices().showDocument(url), ctx.config.webBaseUrl());
        setRoot(view[0]);
        Platform.runLater(view[0]::focusFirst);
    }

    /** Resultado do login: offline = entrou pelo acesso offline (sem internet). */
    private record LoginResult(boolean offline) {
    }

    private void login(LoginView view, String url, String email, String password, boolean remember) {
        view.setBusy(true);
        ctx.api.setBaseUrl(url);
        Fx.async(() -> {
            br.com.quack.caixa.api.AuthApi.User user;
            boolean offline = false;
            try {
                user = ctx.authApi.login(email, password);
                // Prepara o acesso offline (só o hash da senha). Falha local não invalida o login online.
                ctx.offlineUsers.saveAfterOnlineLogin(user, password);
            } catch (br.com.quack.caixa.api.ApiException e) {
                if (!e.isTransient()) {
                    // Senha errada, usuário desativado...: o acesso offline deste usuário deixa de valer aqui
                    // (senão a senha antiga continuaria entrando sem internet por até 7 dias).
                    if (AppContext.rejectsCredentials(e)) {
                        ctx.offlineUsers.forget(email);
                    }
                    throw e; // mostra a mensagem da API
                }
                // Sem conexão: entra pelo acesso offline deste terminal (mensagens iguais às da web).
                user = ctx.offlineUsers.authenticate(email, password);
                ctx.session.setOfflineAuth(email, password);
                offline = true;
            }
            ctx.session.setUser(user);
            ctx.cache.setCompanyId(user.companyId());
            ctx.catalog.load();
            ctx.session.setCompany(ctx.catalog.company());
            try {
                ctx.caixa.status();
            } catch (RuntimeException ignored) {
                // abre o caixa mesmo sem status; o painel mostra o motivo
            }
            return new LoginResult(offline);
        }, result -> {
            ctx.config.setApiBaseUrl(url);
            // "Manter sessão conectada": lembra o e-mail na próxima abertura.
            ctx.config.setRememberEmail(remember);
            ctx.config.setLastEmail(remember ? email : "");
            showPdv();
            if (result.offline()) {
                br.com.quack.caixa.ui.Toasts.info(stage, "Sem conexão: você entrou no modo offline. As vendas e "
                        + "operações de caixa ficam guardadas e são enviadas quando a internet voltar.");
            }
        }, t -> {
            ctx.session.clear();
            view.setBusy(false);
            view.showError(Fx.message(t));
        });
    }

    private void showPdv() {
        pdv = new PdvView(ctx, () -> {
            Fx.async(() -> {
                ctx.authApi.logout();
                return true;
            }, ok -> showLogin(null), t -> showLogin(null));
        });
        setRoot(pdv);
        pdv.install(stage.getScene());
        pdv.focusSearch();
        ctx.sync.start();
        ctx.sync.triggerNow();
    }

    @Override
    public void stop() {
        if (ctx != null) {
            ctx.sync.stop();
        }
        br.com.quack.caixa.persistence.LocalDb.closeAll();
        Fx.shutdown();
    }
}
