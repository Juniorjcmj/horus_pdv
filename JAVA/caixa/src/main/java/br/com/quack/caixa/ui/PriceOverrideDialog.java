package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.PriceAuthApi;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * "Alterar preço do produto" (porta de PriceOverrideModal.tsx): novo preço, gerente, senha e motivo. O servidor
 * valida a senha e devolve uma autorização de uso único (2 horas) que acompanha o item na venda.
 */
public final class PriceOverrideDialog {
    public static final String ICON_TAG = "M3 3h8l10 10-8 8L3 11V3zm2 2v5.2l8 8 5.2-5.2-8-8H5zm2.5 1a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z";
    private static final String ICON_SHIELD = "M12 2 4 5v6c0 5 3.4 9.7 8 11 4.6-1.3 8-6 8-11V5l-8-3zm-1 14-4-4 1.4-1.4L11 13.2l4.6-4.6L17 10l-6 6z";
    private static final List<String> MOTIVOS_FREQUENTES = List.of(
            "Preço da prateleira diferente do sistema.",
            "Produto avariado ou próximo do vencimento.",
            "Negociação com o cliente.");

    /** Resultado aprovado pelo servidor. */
    public record Approval(BigDecimal novoPreco, String autorizacaoId, String supervisorNome) {
    }

    private PriceOverrideDialog() {
    }

    public static Optional<Approval> show(Window owner, AppContext ctx, String productCode, String productName,
            BigDecimal currentPrice) {
        Approval[] result = new Approval[1];
        Stage[] st = new Stage[1];
        boolean[] busy = {false};

        Label title = new Label("Alterar preço do produto");
        title.getStyleClass().add("rg-modal-title");
        Label sub = new Label("Exige a senha de um gerente");
        sub.getStyleClass().add("rg-mini-muted");
        StackPane icon = new StackPane(Fx.icon(ICON_TAG, "rg-icon-brand"));
        icon.getStyleClass().add("rg-closing-icon");
        HBox titleBox = new HBox(10, icon, new VBox(1, title, sub));
        titleBox.setAlignment(Pos.CENTER_LEFT);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = Fx.button("✕", "rg-btn-icon", () -> {
            if (!busy[0]) {
                st[0].close();
            }
        });
        close.setFocusTraversable(false);
        HBox header = new HBox(8, titleBox, sp, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label prodName = new Label(productName);
        prodName.getStyleClass().add("rg-po-product");
        Label prodMeta = new Label("Cód. " + productCode + " · preço atual R$ " + Money.formatBr(currentPrice));
        prodMeta.getStyleClass().add("rg-mini-muted");
        VBox prodCard = new VBox(2, prodName, prodMeta);
        prodCard.getStyleClass().add("rg-cash-card");

        TextField preco = new TextField();
        preco.setPromptText("0,00");
        preco.getStyleClass().addAll("rg-pay-input", "rg-po-price");

        ComboBox<PriceAuthApi.Supervisor> gerente = new ComboBox<>();
        gerente.setPromptText("Carregando...");
        gerente.setMaxWidth(Double.MAX_VALUE);
        PasswordField senha = new PasswordField();
        senha.setPromptText("Digite a senha...");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(4);
        grid.add(label("Gerente *"), 0, 0);
        grid.add(label("Senha do gerente *"), 1, 0);
        grid.add(gerente, 0, 1);
        grid.add(senha, 1, 1);
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            grid.getColumnConstraints().add(cc);
        }

        TextField motivo = new TextField();
        motivo.setPromptText("Ex.: preço da prateleira diferente");
        motivo.textProperty().addListener((o, a, b) -> {
            if (b != null && b.length() > 200) {
                motivo.setText(b.substring(0, 200));
            }
        });
        FlowPane chips = new FlowPane(6, 6);
        for (String m : MOTIVOS_FREQUENTES) {
            Button chip = Fx.button(m, "rg-filter-chip", () -> motivo.setText(m));
            chip.setFocusTraversable(false);
            chips.getChildren().add(chip);
        }

        Label aviso = new Label("A autorização fica registrada com o nome do gerente e vale só para este produto e "
                + "preço, por 2 horas. Itens com preço alterado não recebem promoção.");
        aviso.setWrapText(true);
        aviso.getStyleClass().add("rg-mini-muted");
        HBox avisoBox = new HBox(8, Fx.icon(ICON_SHIELD, "rg-icon-brand"), aviso);
        avisoBox.getStyleClass().add("rg-cash-card");
        HBox.setHgrow(aviso, Priority.ALWAYS);

        Label error = new Label();
        error.getStyleClass().add("rg-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Button cancelar = Fx.button("Cancelar", "rg-btn-action", () -> {
            if (!busy[0]) {
                st[0].close();
            }
        });
        Button autorizar = Fx.button("Autorizar e aplicar preço", "rg-btn-action rg-cash-btn-primary", () -> { });
        autorizar.setDefaultButton(true);
        HBox footer = new HBox(8, cancelar, autorizar);
        footer.setAlignment(Pos.CENTER_RIGHT);

        Runnable showError = () -> {
            error.setVisible(!error.getText().isBlank());
            error.setManaged(!error.getText().isBlank());
            if (st[0] != null) {
                st[0].sizeToScene();
            }
        };
        // Habilita "Autorizar" só com preço > 0, gerente e senha (podeConfirmar da web).
        Runnable refreshButton = () -> {
            BigDecimal v = parseOrZero(preco.getText());
            autorizar.setDisable(busy[0] || v.signum() <= 0 || gerente.getValue() == null || senha.getText().isBlank());
        };
        preco.textProperty().addListener((o, a, b) -> refreshButton.run());
        senha.textProperty().addListener((o, a, b) -> refreshButton.run());
        gerente.valueProperty().addListener((o, a, b) -> refreshButton.run());
        refreshButton.run();

        autorizar.setOnAction(e -> {
            error.setText("");
            showError.run();
            BigDecimal novo = parseOrZero(preco.getText());
            if (novo.signum() <= 0) {
                error.setText("Informe o novo preço.");
                showError.run();
                preco.requestFocus();
                return;
            }
            if (Money.round2(novo).compareTo(Money.round2(currentPrice)) == 0) {
                error.setText("O novo preço é igual ao preço atual do produto.");
                showError.run();
                return;
            }
            if (gerente.getValue() == null || senha.getText().isBlank()) {
                error.setText("Selecione o gerente e digite a senha.");
                showError.run();
                return;
            }
            if (!ctx.api.apiReachable() || ctx.session.offlineAuth()) {
                error.setText("Sem conexão: a alteração de preço precisa validar a senha do gerente no servidor.");
                showError.run();
                return;
            }
            busy[0] = true;
            Fx.setBusy(st[0], true);
            autorizar.setText("Validando...");
            refreshButton.run();
            String supervisorId = gerente.getValue().id();
            String pwd = senha.getText().trim();
            Fx.async(() -> ctx.priceAuthApi.authorize(supervisorId, pwd, productCode, Money.round2(novo),
                    motivo.getText()), auth -> {
                        busy[0] = false;
                        Fx.setBusy(st[0], false);
                        senha.clear();
                        if (auth == null || auth.autorizacaoId() == null || auth.autorizacaoId().isBlank()) {
                            autorizar.setText("Autorizar e aplicar preço");
                            error.setText("O servidor não confirmou a autorização do preço.");
                            showError.run();
                            return;
                        }
                        result[0] = new Approval(auth.precoNovo() != null ? auth.precoNovo() : Money.round2(novo),
                                auth.autorizacaoId(), auth.supervisorNome());
                        st[0].close();
                    }, t -> {
                        busy[0] = false;
                        Fx.setBusy(st[0], false);
                        senha.clear();
                        autorizar.setText("Autorizar e aplicar preço");
                        refreshButton.run();
                        error.setText(Fx.message(t));
                        showError.run();
                    });
        });

        VBox root = new VBox(12, header, prodCard, label("Novo preço unitário (R$) *"), preco, grid,
                label("Motivo (opcional)"), motivo, chips, avisoBox, error, footer);
        root.setPrefWidth(460);
        st[0] = Fx.card(owner, root);
        st[0].addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> preco.requestFocus());

        // Gerentes/administradores da empresa (exige conexão, como na web).
        Fx.async(ctx.priceAuthApi::supervisors, list -> {
            gerente.getItems().setAll(list);
            gerente.setPromptText(list.isEmpty() ? "Nenhum gerente cadastrado" : "Selecione...");
            if (list.size() == 1) {
                gerente.setValue(list.get(0));
            }
        }, t -> {
            gerente.setPromptText("Indisponível");
            error.setText("Não foi possível carregar a lista de gerentes. A alteração de preço exige conexão.");
            showError.run();
        });

        st[0].showAndWait();
        return Optional.ofNullable(result[0]);
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("rg-pay-field-label");
        return l;
    }

    private static BigDecimal parseOrZero(String text) {
        try {
            return text == null || text.isBlank() ? BigDecimal.ZERO : Money.parseBr(text);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }
}
