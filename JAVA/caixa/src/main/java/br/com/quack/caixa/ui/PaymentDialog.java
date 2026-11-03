package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.domain.CartItem;
import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.PaymentPart;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.SaleOutcome;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Pagamento (F12) no layout do caixa web: resumo, troco, forma, valores e parcelas. */
public final class PaymentDialog {
    private static final String[] SHORT = {"Dinheiro", "PIX", "Débito", "Crédito", "Fiado"};

    private final AppContext ctx;
    private final List<CartItem> items;
    private final PaymentPlan plan;
    private Customer customer;

    private final Label totalVal = new Label();
    private final Label informedVal = new Label();
    private final Label remainingVal = new Label();
    private final Label changeTotalVal = new Label();
    private final Label changeBig = new Label("R$ 0,00");
    private final Label formStatus = new Label();
    private final Label customerLine = new Label();
    private final Label error = new Label();
    private final TextField cpfField = new TextField();
    private final HBox cpfRow = new HBox(8);
    private final TextField amountField = new TextField();
    private final TextField cashField = new TextField();
    private final TextField partChangeField = new TextField("R$ 0,00");
    private final GridPane cashRow = new GridPane();
    private final HBox billsRow = new HBox(6);
    private final VBox partsBox = new VBox(4);
    private final ToggleGroup typeGroup = new ToggleGroup();
    private final ToggleButton[] typeButtons = new ToggleButton[PaymentType.values().length];
    private final Button confirmBtn = new Button("Confirmar Venda");
    private final Button backBtn = new Button("Voltar");
    private final VBox root = new VBox(12);

    private Stage stage;
    private SaleOutcome result;
    private boolean busy;
    private boolean revertingType;

    private PaymentDialog(AppContext ctx, List<CartItem> items, BigDecimal total, Customer customer, String cpf) {
        this.ctx = ctx;
        this.items = items;
        this.customer = customer;
        this.plan = new PaymentPlan(total);
        cpfField.setText(cpf == null ? "" : cpf);
    }

    public static Optional<SaleOutcome> show(Window owner, AppContext ctx, List<CartItem> items, BigDecimal total,
            Customer customer, String cpf) {
        PaymentDialog d = new PaymentDialog(ctx, items, total, customer, cpf);
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED, d::onKey);
        d.stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> d.focusValue());
        d.resetValues();
        d.stage.showAndWait();
        return Optional.ofNullable(d.result);
    }

    private PaymentType selectedType() {
        return (PaymentType) typeGroup.getSelectedToggle().getUserData();
    }

    // ---------- layout ----------

    private void build() {
        root.setPrefWidth(490);
        root.getChildren().add(Fx.modalHeader("Pagamento", null, this::close));

        Button cpfChip = Fx.button("CPF na nota / NF-e", "rg-btn-small", () -> {
            boolean show = !cpfRow.isVisible();
            show(cpfRow, show);
            if (show) {
                cpfField.requestFocus();
            }
        });
        cpfChip.setFocusTraversable(false);
        cpfChip.setGraphic(Fx.icon(Fx.ICON_RECEIPT_SMALL, "rg-icon-muted"));
        cpfField.setPromptText("CPF ou CNPJ do consumidor");
        HBox.setHgrow(cpfField, Priority.ALWAYS);
        cpfRow.getChildren().add(cpfField);
        show(cpfRow, !cpfField.getText().isBlank());
        HBox chipRow = new HBox(cpfChip);
        root.getChildren().addAll(chipRow, cpfRow);

        GridPane summary = new GridPane();
        summary.getStyleClass().add("rg-pay-summary");
        summary.setHgap(12);
        String[] titles = {"Total da Venda", "Total Informado", "Saldo Restante", "Troco Total"};
        Label[] values = {totalVal, informedVal, remainingVal, changeTotalVal};
        for (int i = 0; i < 4; i++) {
            Label t = new Label(titles[i]);
            t.getStyleClass().add("rg-pay-sum-title");
            values[i].getStyleClass().add("rg-pay-sum-value");
            summary.add(new VBox(2, t, values[i]), i, 0);
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(25);
            summary.getColumnConstraints().add(cc);
        }
        root.getChildren().add(summary);

        Label trocoTitle = new Label("TROCO A DEVOLVER");
        trocoTitle.getStyleClass().add("rg-pay-change-title");
        changeBig.getStyleClass().add("rg-pay-change-value");
        VBox trocoCard = new VBox(2, trocoTitle, changeBig);
        trocoCard.setAlignment(Pos.CENTER);
        trocoCard.getStyleClass().add("rg-pay-card");
        root.getChildren().add(trocoCard);

        root.getChildren().add(buildForm());

        error.getStyleClass().add("rg-error");
        error.setWrapText(true);
        show(error, false);
        root.getChildren().add(error);

        backBtn.getStyleClass().addAll("rg-btn-danger-fill", "rg-btn-action");
        backBtn.setOnAction(e -> close());
        confirmBtn.getStyleClass().addAll("rg-btn-success", "rg-btn-action");
        confirmBtn.setOnAction(e -> confirm());
        for (Button b : new Button[] {backBtn, confirmBtn}) {
            b.setMaxWidth(Double.MAX_VALUE);
            b.setFocusTraversable(false);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        root.getChildren().add(new HBox(8, backBtn, confirmBtn));

        amountField.textProperty().addListener((o, a, b) -> updateLive());
        cashField.textProperty().addListener((o, a, b) -> updateLive());
        amountField.setOnKeyPressed(this::enterKey);
        cashField.setOnKeyPressed(this::enterKey);
    }

    private VBox buildForm() {
        Label formTitle = new Label("FORMA DE PAGAMENTO");
        formTitle.getStyleClass().add("rg-pay-form-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(formTitle, sp, formStatus);
        head.setAlignment(Pos.CENTER_LEFT);

        Label forma = new Label("Forma");
        forma.getStyleClass().add("rg-pay-field-label");
        HBox tiles = new HBox(6);
        PaymentType[] all = PaymentType.values();
        for (int i = 0; i < all.length; i++) {
            Label name = new Label(SHORT[i]);
            name.getStyleClass().add("rg-tile-name");
            Label key = new Label("Alt+" + (i + 1));
            key.getStyleClass().add("rg-tile-key");
            VBox g = new VBox(0, name, key);
            g.setAlignment(Pos.CENTER);
            ToggleButton tb = new ToggleButton();
            tb.setGraphic(g);
            tb.getStyleClass().add("rg-pay-tile");
            tb.setUserData(all[i]);
            tb.setToggleGroup(typeGroup);
            tb.setFocusTraversable(false);
            tb.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tb, Priority.ALWAYS);
            typeButtons[i] = tb;
            tiles.getChildren().add(tb);
        }
        typeButtons[0].setSelected(true);
        typeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) {
                a.setSelected(true); // nunca fica sem forma
                return;
            }
            onTypeChanged((ToggleButton) a);
        });

        customerLine.getStyleClass().add("rg-pay-customer");
        customerLine.setWrapText(true);
        refreshCustomer();

        Label valLbl = new Label("Valor a pagar (R$)");
        valLbl.getStyleClass().add("rg-pay-field-label");
        amountField.getStyleClass().add("rg-pay-input");

        Label cashLbl = new Label("Valor entregue em dinheiro");
        cashLbl.getStyleClass().add("rg-pay-field-label");
        Label chLbl = new Label("Troco desta parcela");
        chLbl.getStyleClass().add("rg-pay-field-label");
        cashField.getStyleClass().add("rg-pay-input");
        partChangeField.setEditable(false);
        partChangeField.setFocusTraversable(false);
        partChangeField.getStyleClass().addAll("rg-pay-input", "rg-field-readonly", "rg-pay-change-field");
        cashRow.setHgap(8);
        cashRow.setVgap(4);
        cashRow.add(cashLbl, 0, 0);
        cashRow.add(chLbl, 1, 0);
        cashRow.add(cashField, 0, 1);
        cashRow.add(partChangeField, 1, 1);
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            cashRow.getColumnConstraints().add(cc);
        }

        Label recebido = new Label("RECEBIDO:");
        recebido.getStyleClass().add("rg-pay-form-title");
        billsRow.setAlignment(Pos.CENTER_LEFT);
        recebido.setMinWidth(Region.USE_PREF_SIZE);
        billsRow.getChildren().add(recebido);
        Button exact = Fx.button("Valor exato", "rg-bill rg-bill-exact", () -> setCash(amountOrZero()));
        exact.setFocusTraversable(false);
        exact.setMinWidth(Region.USE_PREF_SIZE);
        billsRow.getChildren().add(exact);
        for (int v : new int[] {10, 20, 50, 100, 200}) {
            Button b = Fx.button("R$ " + v, "rg-bill", () -> setCash(BigDecimal.valueOf(v)));
            b.setFocusTraversable(false);
            b.setMinWidth(Region.USE_PREF_SIZE);
            billsRow.getChildren().add(b);
        }

        Button addPart = Fx.button("+  Adicionar à lista de parcelas", "rg-btn-link", this::addPart);
        addPart.setFocusTraversable(false);
        HBox addRow = new HBox(addPart);
        addRow.setAlignment(Pos.CENTER);

        VBox form = new VBox(8, head, forma, tiles, customerLine, partsBox, valLbl, amountField, cashRow, billsRow,
                addRow);
        form.getStyleClass().add("rg-pay-card");
        return form;
    }

    // ---------- estado ----------

    private void onTypeChanged(ToggleButton previous) {
        if (revertingType) {
            return;
        }
        if (selectedType() == PaymentType.FIADO && customer == null) {
            Optional<CustomerPickerDialog.Choice> ch = CustomerPickerDialog.show(stage, ctx.catalog);
            if (ch.isPresent() && ch.get().customer() != null) {
                customer = ch.get().customer();
            } else {
                revertingType = true;
                (previous != null ? previous : typeButtons[0]).setSelected(true);
                revertingType = false;
                showError("Para vender no fiado, selecione um cliente cadastrado.");
            }
        }
        boolean cash = selectedType() == PaymentType.DINHEIRO;
        show(cashRow, cash);
        show(billsRow, cash);
        if (cash) {
            setCash(amountOrZero());
        }
        refreshCustomer();
        updateLive();
        Platform.runLater(this::focusValue);
    }

    private void refreshCustomer() {
        if (customer == null) {
            show(customerLine, false);
            return;
        }
        BigDecimal disp = customer.creditLimit().subtract(customer.debt()).max(BigDecimal.ZERO);
        customerLine.setText("Cliente: " + customer.name() + "   •   fiado disponível R$ " + Money.formatBr(disp));
        show(customerLine, true);
    }

    /** Depois de mudar as parcelas: valor = saldo restante; recebido = mesmo valor. */
    private void resetValues() {
        BigDecimal rem = plan.remaining();
        amountField.setText(rem.signum() > 0 ? Money.formatBr(rem) : "");
        setCash(rem);
        renderParts();
        hideError();
        updateLive();
    }

    private void renderParts() {
        partsBox.getChildren().clear();
        List<PaymentPart> parts = plan.parts();
        for (int i = 0; i < parts.size(); i++) {
            PaymentPart p = parts.get(i);
            int idx = i;
            Label l = new Label(p.type().label() + "   R$ " + Money.formatBr(p.amount())
                    + (p.change().signum() > 0 ? "   (troco R$ " + Money.formatBr(p.change()) + ")" : ""));
            l.getStyleClass().add("rg-pay-part-text");
            Region sp = new Region();
            HBox.setHgrow(sp, Priority.ALWAYS);
            Button rm = Fx.button("✕", "rg-btn-icon", () -> {
                if (!busy) {
                    plan.removeAt(idx);
                    resetValues();
                }
            });
            rm.setFocusTraversable(false);
            HBox row = new HBox(8, l, sp, rm);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("rg-pay-part");
            partsBox.getChildren().add(row);
        }
        show(partsBox, !parts.isEmpty());
    }

    /** Recalcula o resumo enquanto o operador digita. */
    private void updateLive() {
        BigDecimal amount = amountOrZero();
        boolean cash = selectedType() == PaymentType.DINHEIRO;
        BigDecimal given = cash ? cashOrNull() : amount;
        BigDecimal partChange = cash && given != null ? given.subtract(amount).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        BigDecimal informed = Money.round2(plan.paid().add(amount));
        BigDecimal remaining = plan.total().subtract(informed).max(BigDecimal.ZERO);
        BigDecimal changeTotal = plan.totalChange().add(partChange);

        totalVal.setText("R$ " + Money.formatBr(plan.total()));
        informedVal.setText("R$ " + Money.formatBr(informed));
        remainingVal.setText("R$ " + Money.formatBr(remaining));
        changeTotalVal.setText("R$ " + Money.formatBr(changeTotal));
        partChangeField.setText("R$ " + Money.formatBr(partChange));
        changeBig.setText("R$ " + Money.formatBr(changeTotal));
        toggle(remainingVal, "rg-text-success", remaining.signum() == 0);
        toggle(remainingVal, "rg-text-danger", remaining.signum() > 0);
        toggle(changeTotalVal, "rg-text-success", true);
        toggle(changeBig, "rg-pay-change-positive", changeTotal.signum() > 0);
        if (remaining.signum() == 0) {
            formStatus.setText("✓ Saldo quitado");
            formStatus.getStyleClass().setAll("label", "rg-pay-status-ok");
        } else {
            formStatus.setText("Faltam R$ " + Money.formatBr(remaining));
            formStatus.getStyleClass().setAll("label", "rg-pay-status-pending");
        }
    }

    private static void toggle(Label l, String cls, boolean on) {
        l.getStyleClass().remove(cls);
        if (on) {
            l.getStyleClass().add(cls);
        }
    }

    private BigDecimal amountOrZero() {
        try {
            BigDecimal a = parseAmount();
            return a == null ? BigDecimal.ZERO : Money.round2(a);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal cashOrNull() {
        try {
            return parseCash();
        } catch (DomainException e) {
            return null;
        }
    }

    private void setCash(BigDecimal v) {
        cashField.setText(v == null || v.signum() <= 0 ? "" : Money.formatBr(v));
        if (cashField.isFocused()) {
            cashField.selectAll();
        }
    }

    private void focusValue() {
        TextField f = selectedType() == PaymentType.DINHEIRO ? cashField : amountField;
        f.requestFocus();
        f.selectAll();
    }

    private BigDecimal parseAmount() {
        String t = amountField.getText();
        return t == null || t.isBlank() ? null : Money.parseBr(t);
    }

    private BigDecimal parseCash() {
        String t = cashField.getText();
        return t == null || t.isBlank() ? null : Money.parseBr(t);
    }

    private void showError(String m) {
        error.setText(m);
        show(error, true);
        if (stage != null) {
            Platform.runLater(stage::sizeToScene);
        }
    }

    private void hideError() {
        error.setText("");
        show(error, false);
    }

    private static void show(javafx.scene.Node n, boolean v) {
        n.setVisible(v);
        n.setManaged(v);
    }

    // ---------- ações ----------

    private void close() {
        if (!busy) {
            stage.close();
        }
    }

    private void onKey(KeyEvent e) {
        if (busy) {
            return;
        }
        if (e.isAltDown() && e.getCode().isDigitKey()) {
            int n = e.getCode().ordinal() - KeyCode.DIGIT1.ordinal();
            if (n >= 0 && n < typeButtons.length) {
                typeButtons[n].setSelected(true);
                e.consume();
            }
        } else if (e.getCode() == KeyCode.ENTER && !(e.getTarget() instanceof TextField)) {
            onEnter();
            e.consume();
        }
    }

    private void enterKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            onEnter();
            e.consume();
        }
    }

    private void addPart() {
        try {
            BigDecimal amount = parseAmount();
            plan.addPart(selectedType(), amount == null ? plan.remaining() : amount,
                    selectedType() == PaymentType.DINHEIRO ? parseCash() : null, customer);
            resetValues();
            if (plan.remaining().signum() == 0) {
                confirmBtn.requestFocus();
            } else {
                focusValue();
            }
        } catch (DomainException ex) {
            showError(ex.getMessage());
        }
        if (stage != null) {
            stage.sizeToScene();
        }
    }

    /** Enter: parcela parcial vai para a lista; valor que fecha o saldo confirma a venda. */
    private void onEnter() {
        if (busy) {
            return;
        }
        try {
            if (plan.remaining().signum() == 0) {
                confirm();
                return;
            }
            BigDecimal amount = parseAmount();
            BigDecimal cash = selectedType() == PaymentType.DINHEIRO ? parseCash() : null;
            BigDecimal rem = plan.remaining();
            boolean cashCoversAll = selectedType() == PaymentType.DINHEIRO && plan.parts().isEmpty()
                    && cash != null && cash.compareTo(plan.total()) >= 0;
            if (amount != null && amount.compareTo(rem) < 0 && !cashCoversAll) {
                addPart();
                return;
            }
            confirm();
        } catch (DomainException ex) {
            showError(ex.getMessage());
        }
    }

    private void confirm() {
        if (busy) {
            return;
        }
        int partsBefore = plan.parts().size();
        try {
            if (plan.remaining().signum() != 0) {
                plan.finalizeWith(selectedType(), parseAmount(),
                        selectedType() == PaymentType.DINHEIRO ? parseCash() : null, customer);
            }
        } catch (DomainException ex) {
            showError(ex.getMessage());
            return;
        }
        hideError();
        setBusy(true);
        var user = ctx.session.user();
        String cpf = cpfField.getText() == null ? null : cpfField.getText().trim();
        Fx.async(() -> ctx.sales.confirm(items, plan, customer, cpf, user.name(), user.id(),
                ctx.config.deviceId()), outcome -> {
                    result = outcome;
                    setBusy(false);
                    stage.close();
                }, t -> {
                    setBusy(false);
                    // Desfaz a parcela criada só para confirmar: o operador pode corrigir e tentar de novo.
                    while (plan.parts().size() > partsBefore) {
                        plan.removeAt(plan.parts().size() - 1);
                    }
                    resetValues();
                    showError(Fx.message(t));
                });
    }

    private void setBusy(boolean b) {
        busy = b;
        if (stage != null) {
            Fx.setBusy(stage, b);
        }
        root.setDisable(b);
        confirmBtn.setText(b ? "Confirmando..." : "Confirmar Venda");
    }
}
