package br.com.quack.estoque.ui;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/**
 * Login no layout da web (AuthLayout.tsx + LoginPage.tsx): painel institucional à esquerda e formulário à
 * direita. Os links de apresentação, cadastro e recuperação de senha abrem o site da web no navegador; o
 * endereço da API (só o aplicativo desktop precisa) fica no link "Servidor" abaixo do botão.
 */
public class LoginView extends StackPane {

    public interface Action {
        void login(String apiUrl, String email, String password, boolean remember);
    }

    private static final String ICON_MAIL = "M3 5h18v14H3V5zm2 2v.4l7 4.6 7-4.6V7H5zm14 2.8-7 4.6-7-4.6V17h14V9.8z";
    private static final String ICON_EYE = "M12 5c5 0 9 4.5 10 7-1 2.5-5 7-10 7S3 14.5 2 12c1-2.5 5-7 10-7zm0 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10zm0 2.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z";
    private static final String ICON_LOGIN = "M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5v-2h5V5h-5V3zm-4 4 1.4 1.4L8.8 11H17v2H8.8l2.6 2.6L10 17l-5-5 5-5z";

    private final TextField urlField = new TextField();
    private final TextField emailField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final TextField passwordVisible = new TextField();
    private final CheckBox remember = new CheckBox("Manter sessão conectada");
    private final Button submit = new Button("Entrar");
    private final Label error = new Label();
    private final VBox serverBox = new VBox(4);
    private final Hyperlink serverLink = new Hyperlink();

    public LoginView(String apiUrl, String lastEmail, boolean rememberEmail, String notice, Action action,
            Consumer<String> openUrl, String webBaseUrl) {
        getStyleClass().add("rg-auth-bg");

        // Brilhos de fundo (os "glows" da web).
        Circle glow1 = new Circle(110);
        glow1.getStyleClass().add("rg-auth-glow-secondary");
        glow1.setEffect(new GaussianBlur(60));
        glow1.setManaged(false);
        Circle glow2 = new Circle(120);
        glow2.getStyleClass().add("rg-auth-glow-accent");
        glow2.setEffect(new GaussianBlur(60));
        glow2.setManaged(false);
        layoutBoundsProperty().addListener((o, a, b) -> {
            glow1.setCenterX(40);
            glow1.setCenterY(30);
            glow2.setCenterX(b.getWidth() - 30);
            glow2.setCenterY(b.getHeight() * 0.55);
        });

        HBox card = new HBox(brandPanel(), formPanel(apiUrl, lastEmail, rememberEmail, notice, action, openUrl,
                webBaseUrl));
        card.getStyleClass().add("rg-auth-card");
        card.setMaxWidth(860);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        getChildren().addAll(glow1, glow2, card);
        setAlignment(Pos.CENTER);
        setPadding(new Insets(32));
    }

    // ---------- painel institucional ----------

    private VBox brandPanel() {
        ImageView logo = new ImageView(new Image(LoginView.class.getResource("quack-logo-negativo.png")
                .toExternalForm(), 0, 96, true, true));
        logo.setFitHeight(40);
        logo.setPreserveRatio(true);
        StackPane logoBox = new StackPane(logo);
        logoBox.getStyleClass().add("rg-auth-logo");

        Label name = new Label("Quack Sistemas");
        name.getStyleClass().add("rg-auth-brand-name");
        Label tagline = new Label("SOLUÇÕES PARA SEU NEGÓCIO");
        tagline.getStyleClass().add("rg-auth-brand-tag");
        HBox brand = new HBox(12, logoBox, new VBox(2, name, tagline));
        brand.setAlignment(Pos.CENTER_LEFT);

        Label headline = new Label("Controle de estoque rápido: entradas, inventário, etiquetas e compras.");
        headline.getStyleClass().add("rg-auth-headline");
        headline.setWrapText(true);

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Label footer = new Label("Receba mercadorias, conte a prateleira e imprima etiquetas de gôndola em um só lugar.");
        footer.getStyleClass().add("rg-auth-brand-foot");
        footer.setWrapText(true);

        VBox panel = new VBox(22, brand, headline, grow, footer);
        panel.getStyleClass().add("rg-auth-brand");
        panel.setPrefWidth(370);
        panel.setMinWidth(320);
        return panel;
    }

    // ---------- formulário ----------

    private VBox formPanel(String apiUrl, String lastEmail, boolean rememberEmail, String notice, Action action,
            Consumer<String> openUrl, String webBaseUrl) {
        Label title = new Label("Bem-vindo de volta!");
        title.getStyleClass().add("rg-auth-title");
        Label sub = new Label("Use seu e-mail e senha para acessar o painel.");
        sub.getStyleClass().add("rg-auth-sub");

        Hyperlink landing = link("← Conhecer o Quack Sistemas (Apresentação do Sistema)", "rg-auth-link-small",
                () -> openUrl.accept(webBaseUrl + "/"));
        VBox landingBox = new VBox(8, landing, new Separator());

        emailField.setText(lastEmail == null ? "" : lastEmail);
        emailField.setPromptText("usuario@hpdv.com.br");
        Node email = iconField(emailField, ICON_MAIL, null);

        passwordField.setPromptText("••••••••");
        passwordVisible.setPromptText("••••••••");
        passwordVisible.textProperty().bindBidirectional(passwordField.textProperty());
        passwordVisible.setVisible(false);
        passwordVisible.setManaged(false);
        Button eye = new Button();
        eye.setGraphic(Fx.icon(ICON_EYE, "rg-icon-muted"));
        eye.getStyleClass().add("rg-auth-eye");
        eye.setFocusTraversable(false);
        eye.setOnAction(e -> togglePassword());
        StackPane pwdStack = new StackPane(passwordField, passwordVisible);
        Node password = iconField(pwdStack, Fx.ICON_LOCK, eye);

        remember.setSelected(rememberEmail);
        remember.getStyleClass().add("rg-auth-check");
        remember.setFocusTraversable(false);
        Hyperlink forgot = link("Esqueci minha senha", "rg-auth-link",
                () -> openUrl.accept(webBaseUrl + "/?page=login"));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox options = new HBox(remember, sp, forgot);
        options.setAlignment(Pos.CENTER_LEFT);

        submit.getStyleClass().add("rg-auth-submit");
        submit.setGraphic(Fx.icon(ICON_LOGIN, "rg-icon-light"));
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setDefaultButton(true);
        submit.setOnAction(e -> {
            if (emailField.getText().isBlank() || passwordField.getText().isEmpty()) {
                showError("Informe e-mail e senha.");
                return;
            }
            if (urlField.getText().isBlank()) {
                showServer(true);
                showError("Informe o endereço do servidor (API).");
                return;
            }
            showError("");
            action.login(urlField.getText().trim(), emailField.getText().trim(), passwordField.getText(),
                    remember.isSelected());
        });

        Label noAccount = new Label("Não tem uma conta?");
        noAccount.getStyleClass().add("rg-auth-sub");
        Hyperlink register = link("Criar cadastro", "rg-auth-link", () -> openUrl.accept(webBaseUrl + "/register"));
        HBox registerRow = new HBox(4, noAccount, register);
        registerRow.setAlignment(Pos.CENTER);

        error.getStyleClass().add("rg-error");
        error.setWrapText(true);
        showError(notice == null ? "" : notice);

        // Servidor (API): só o aplicativo desktop precisa; fica recolhido.
        urlField.setText(apiUrl);
        urlField.setPromptText(br.com.quack.estoque.config.AppConfig.DEFAULT_API_URL);
        urlField.textProperty().addListener((o, a, b) -> updateServerLink());
        Label urlLabel = new Label("Endereço do servidor (API)");
        urlLabel.getStyleClass().add("rg-auth-label");
        serverBox.getChildren().addAll(urlLabel, urlField);
        showServer(false);
        serverLink.getStyleClass().add("rg-auth-link-small");
        serverLink.setFocusTraversable(false);
        serverLink.setOnAction(e -> showServer(!serverBox.isVisible()));
        updateServerLink();
        HBox serverRow = new HBox(serverLink);
        serverRow.setAlignment(Pos.CENTER);

        VBox form = new VBox(10, title, sub, landingBox, fieldLabel("E-mail"), email, fieldLabel("Senha"), password,
                options, submit, registerRow, error, serverBox, serverRow);
        VBox.setMargin(landingBox, new Insets(10, 0, 4, 0));
        VBox.setMargin(submit, new Insets(4, 0, 0, 0));
        form.getStyleClass().add("rg-auth-form");
        form.setPrefWidth(470);
        HBox.setHgrow(form, Priority.ALWAYS);
        return form;
    }

    private static Label fieldLabel(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-auth-label");
        return l;
    }

    private static Hyperlink link(String text, String cls, Runnable action) {
        Hyperlink h = new Hyperlink(text);
        h.getStyleClass().add(cls);
        h.setFocusTraversable(false);
        h.setOnAction(e -> action.run());
        return h;
    }

    /** Campo com ícone à esquerda (e botão opcional à direita), como os inputs da web. */
    private static Node iconField(Node field, String iconPath, Node right) {
        Node icon = Fx.icon(iconPath, "rg-icon-muted");
        StackPane box = new StackPane(field, icon);
        StackPane.setAlignment(icon, Pos.CENTER_LEFT);
        StackPane.setMargin(icon, new Insets(0, 0, 0, 10));
        icon.setMouseTransparent(true);
        if (right != null) {
            box.getChildren().add(right);
            StackPane.setAlignment(right, Pos.CENTER_RIGHT);
            StackPane.setMargin(right, new Insets(0, 4, 0, 0));
        }
        box.getStyleClass().add("rg-auth-field");
        return box;
    }

    private void togglePassword() {
        boolean show = !passwordVisible.isVisible();
        passwordVisible.setVisible(show);
        passwordVisible.setManaged(show);
        passwordField.setVisible(!show);
        passwordField.setManaged(!show);
        (show ? passwordVisible : passwordField).requestFocus();
        (show ? passwordVisible : passwordField).end();
    }

    private void showServer(boolean show) {
        serverBox.setVisible(show);
        serverBox.setManaged(show);
        updateServerLink();
    }

    private void updateServerLink() {
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        serverLink.setText((serverBox.isVisible() ? "Ocultar servidor" : "Servidor: " + (url.isEmpty() ? "não configurado"
                : url) + "  ·  alterar"));
    }

    public void setBusy(boolean busy) {
        submit.setDisable(busy);
        urlField.setDisable(busy);
        emailField.setDisable(busy);
        passwordField.setDisable(busy);
        passwordVisible.setDisable(busy);
        submit.setText(busy ? "Entrando..." : "Entrar");
    }

    public void showError(String message) {
        error.setText(message == null ? "" : message);
        boolean has = message != null && !message.isBlank();
        error.setVisible(has);
        error.setManaged(has);
    }

    public void focusFirst() {
        if (emailField.getText().isBlank()) {
            emailField.requestFocus();
        } else {
            passwordField.requestFocus();
        }
    }
}
