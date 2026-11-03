package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Sangria (F3) e reforço (F1): valor + motivo. */
public final class CashMovementDialog {
    private CashMovementDialog() {
    }

    /** tipo: "Sangria" ou "Reforço". Devolve true se o movimento foi registrado. */
    public static boolean show(Window owner, AppContext ctx, String tipo) {
        boolean sangria = "Sangria".equals(tipo);
        TextField valor = new TextField();
        valor.setPromptText("0,00");
        TextField motivo = new TextField();
        motivo.setPromptText(sangria ? "Motivo da sangria" : "Motivo do reforço");
        Label error = new Label();
        error.getStyleClass().add("rg-error");
        error.setWrapText(true);
        boolean[] done = {false};
        Stage[] st = new Stage[1];
        Button ok = new Button(sangria ? "Registrar sangria" : "Registrar reforço");
        ok.getStyleClass().add("rg-btn-primary");
        Runnable submit = () -> {
            error.setText("");
            String v;
            try {
                v = Money.formatBr(Money.parseBr(valor.getText()));
                if (Money.parseBr(valor.getText()).signum() <= 0) {
                    throw new DomainException("Informe um valor maior que zero.");
                }
                if (motivo.getText().isBlank()) {
                    throw new DomainException("Informe o motivo.");
                }
            } catch (DomainException ex) {
                error.setText(ex.getMessage());
                return;
            }
            ok.setDisable(true);
            Fx.async(() -> ctx.caixa.movimento(tipo, v, motivo.getText().trim()), s -> {
                done[0] = true;
                st[0].close();
            }, t -> {
                ok.setDisable(false);
                error.setText(Fx.message(t));
            });
        };
        ok.setOnAction(e -> submit.run());
        valor.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                motivo.requestFocus();
            }
        });
        motivo.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                submit.run();
            }
        });
        ok.getStyleClass().setAll("button", "rg-cash-btn", sangria ? "rg-cash-btn-danger" : "rg-cash-btn-success");
        Button cancel = Fx.button("Voltar", "rg-cash-btn", () -> st[0].close());
        for (Button b : new Button[] {ok, cancel}) {
            b.setMaxWidth(Double.MAX_VALUE);
            javafx.scene.layout.HBox.setHgrow(b, javafx.scene.layout.Priority.ALWAYS);
        }
        Label sub = new Label(sangria ? "Retirada de dinheiro da gaveta" : "Entrada de dinheiro na gaveta");
        sub.getStyleClass().addAll("rg-modal-subtitle", sangria ? "rg-text-danger" : "rg-text-success");
        Label vl = new Label("Valor (R$)");
        vl.getStyleClass().add("rg-pay-field-label");
        Label ml = new Label("Motivo");
        ml.getStyleClass().add("rg-pay-field-label");
        valor.getStyleClass().add("rg-pay-input");
        VBox root = new VBox(10);
        root.getChildren().addAll(
                Fx.modalHeader(sangria ? "Sangria (F3)" : "Reforço (F1)", sub, () -> st[0].close()),
                vl, valor, ml, motivo, error, new HBox(8, cancel, ok));
        root.setPrefWidth(400);
        st[0] = Fx.card(owner, root);
        st[0].addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> valor.requestFocus());
        st[0].showAndWait();
        return done[0];
    }
}
