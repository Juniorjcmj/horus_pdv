package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.Company;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.PaymentType;
import java.math.BigDecimal;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Modal do caixa (F6) no layout da web: turno aberto, abertura e fechamento. */
public final class CashPanelDialog {
    private final AppContext ctx;
    /** Frente de caixa: o resumo do fechamento oferece "Sair do sistema" (troca de operador). */
    private Runnable onLogout;
    private final VBox root = new VBox(12);
    private final VBox body = new VBox(12);
    private final Label subtitle = new Label();
    private final Label error = new Label();
    private final Label notice = new Label();
    private Stage stage;
    private boolean busy;

    private CashPanelDialog(AppContext ctx) {
        this.ctx = ctx;
    }

    public static void show(Window owner, AppContext ctx) {
        show(owner, ctx, null);
    }

    public static void show(Window owner, AppContext ctx, Runnable onLogout) {
        CashPanelDialog d = new CashPanelDialog(ctx);
        d.onLogout = onLogout;
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.reload();
        d.stage.showAndWait();
    }

    private void build() {
        root.setPrefWidth(540);
        subtitle.getStyleClass().add("rg-modal-subtitle");
        error.getStyleClass().add("rg-error");
        error.setWrapText(true);
        show(error, false);
        notice.getStyleClass().add("rg-offline-notice");
        notice.setWrapText(true);
        show(notice, false);
        root.getChildren().addAll(Fx.modalHeader("Caixa", subtitle, this::close), notice, body, error);
        body.getChildren().setAll(muted("Carregando..."));
    }

    private void close() {
        if (!busy) {
            stage.close();
        }
    }

    private void reload() {
        hideError();
        Fx.async(ctx.caixa::status, r -> {
            showNotice(r.offline() ? "Modo offline: abertura, fechamento, sangria e reforço ficam guardados e são "
                    + "enviados quando a conexão voltar." : null);
            render(r.status(), r.offline());
        }, t -> showError(Fx.message(t)));
    }

    private String companyName() {
        Company c = ctx.catalog.company();
        return c == null ? "" : c.displayName();
    }

    private void render(CaixaStatusDto s, boolean offline) {
        if (s != null && s.open() && s.currentSession() != null) {
            renderOpen(s.currentSession(), offline);
        } else {
            renderClosed(s, offline);
        }
        resize();
    }

    // ---------- turno aberto ----------

    private void renderOpen(CaixaStatusDto.Session cur, boolean offline) {
        setSubtitle("Aberto há " + elapsed(cur.elapsedMinutes()) + " • " + companyName(), "rg-text-success");

        // Card: vendas do turno (por forma de pagamento) + fundo de troco + esperado na gaveta.
        Label vt = cardTitle("Vendas do turno");
        VBox sales = new VBox(4, vt);
        List<CaixaStatusDto.Breakdown> pb = cur.paymentBreakdown();
        if (pb == null || pb.isEmpty()) {
            sales.getChildren().add(muted("Nenhuma venda neste turno ainda."));
        } else {
            for (CaixaStatusDto.Breakdown b : pb) {
                sales.getChildren().add(line(PaymentType.fromWire(nz(b.paymentType())).label(), "R$ " + nz(b.total()),
                        false));
            }
        }
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        sales.getChildren().addAll(grow, new Separator(),
                line("Fundo de troco", "R$ " + nz(cur.openingAmount()), false),
                line("Dinheiro esperado na gaveta", "R$ " + (cur.expectedCashAmount() == null ? "0,00"
                        : cur.expectedCashAmount()), true));
        sales.getStyleClass().add("rg-cash-card");

        // Card: sangrias e reforços.
        VBox moves = new VBox(4, cardTitle("Sangrias e reforços"));
        if (cur.movimentos() == null || cur.movimentos().isEmpty()) {
            moves.getChildren().add(muted("Nenhuma movimentação neste turno."));
        } else {
            for (CaixaStatusDto.Movement m : cur.movimentos()) {
                boolean sangria = "sangria".equalsIgnoreCase(nz(m.tipo()));
                HBox row = line((sangria ? "Sangria" : "Reforço") + (nz(m.motivo()).isBlank() ? "" : " • "
                        + m.motivo()), (sangria ? "- R$ " : "+ R$ ") + nz(m.valor()), false);
                row.getChildren().get(row.getChildren().size() - 1).getStyleClass()
                        .add(sangria ? "rg-text-danger" : "rg-text-success");
                moves.getChildren().add(row);
            }
        }
        moves.getStyleClass().add("rg-cash-card");

        HBox cards = new HBox(10, sales, moves);
        HBox.setHgrow(sales, Priority.ALWAYS);
        HBox.setHgrow(moves, Priority.ALWAYS);
        sales.setPrefWidth(220);
        moves.setPrefWidth(220);

        Button reforco = Fx.button("Reforço", "rg-cash-btn rg-cash-btn-success", () -> movement("Reforco"));
        reforco.setGraphic(Fx.icon(Fx.ICON_ARROW_UP, "rg-icon-success"));
        Button sangria = Fx.button("Sangria", "rg-cash-btn rg-cash-btn-danger", () -> movement("Sangria"));
        sangria.setGraphic(Fx.icon(Fx.ICON_ARROW_DOWN, "rg-icon-danger"));
        Button fechar = Fx.button("Fechar caixa", "rg-cash-btn rg-cash-btn-primary",
                () -> renderCloseForm(cur, offline));
        fechar.setGraphic(Fx.icon(Fx.ICON_LOCK, "rg-icon-light"));
        for (Button b : new Button[] {reforco, sangria, fechar}) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        body.getChildren().setAll(cards, new HBox(8, reforco, sangria, fechar));
    }

    private void movement(String tipo) {
        if (CashMovementDialog.show(stage, ctx, tipo)) {
            reload();
        }
    }

    private void renderCloseForm(CaixaStatusDto.Session cur, boolean offline) {
        BigDecimal expected = parseOrZero(cur.expectedCashAmount());
        HBox expectedRow = line("Dinheiro esperado na gaveta", "R$ " + Money.formatBr(expected), true);
        expectedRow.getStyleClass().add("rg-cash-card");

        TextField counted = new TextField(Money.formatBr(expected));
        counted.getStyleClass().add("rg-pay-input");
        Label diff = new Label();
        TextField reason = new TextField();
        reason.setPromptText("Motivo da diferença (obrigatório se houver diferença)");
        TextField note = new TextField();
        note.setPromptText("Observação (opcional)");

        Runnable updateDiff = () -> {
            BigDecimal d = parseOrZero(counted.getText()).subtract(expected);
            diff.getStyleClass().removeAll("rg-text-success", "rg-text-danger");
            if (d.signum() == 0) {
                diff.setText("Sem diferença");
                diff.getStyleClass().add("rg-text-success");
            } else {
                diff.setText((d.signum() > 0 ? "Sobra de R$ " : "Falta de R$ ") + Money.formatBr(d.abs()));
                diff.getStyleClass().add("rg-text-danger");
            }
        };
        counted.textProperty().addListener((o, a, b) -> updateDiff.run());
        updateDiff.run();

        Button back = Fx.button("Voltar", "rg-cash-btn", () -> render(statusOf(cur), offline));
        Button confirm = Fx.button("Confirmar fechamento", "rg-cash-btn rg-cash-btn-primary", () -> { });
        confirm.setGraphic(Fx.icon(Fx.ICON_LOCK, "rg-icon-light"));
        confirm.setOnAction(e -> {
            hideError();
            BigDecimal c;
            try {
                c = Money.parseBr(counted.getText());
            } catch (DomainException ex) {
                showError("Informe o valor contado na gaveta.");
                return;
            }
            boolean hasDiff = c.subtract(expected).signum() != 0;
            if (hasDiff && reason.getText().trim().length() < 3) {
                showError("Explique o motivo da diferença (mínimo 3 letras).");
                reason.requestFocus();
                return;
            }
            if (!Fx.confirm(stage, "Fechar caixa",
                    "Fechar o caixa atual? As vendas ficam bloqueadas até nova abertura.")) {
                return;
            }
            setBusy(true);
            String v = Money.formatBr(c);
            String why = hasDiff ? reason.getText().trim() : "";
            Fx.async(() -> ctx.caixa.fechar(v, note.getText().trim(), why), st -> {
                setBusy(false);
                afterClose(st);
            }, t -> {
                setBusy(false);
                showError(Fx.message(t));
            });
        });
        counted.setOnAction(e -> confirm.fire());
        for (Button b : new Button[] {back, confirm}) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        body.getChildren().setAll(expectedRow, fieldLabel("Valor contado na gaveta (dinheiro)"), counted, diff,
                fieldLabel("Motivo da diferença"), reason, fieldLabel("Observação"), note, new HBox(8, back, confirm));
        resize();
        Platform.runLater(() -> {
            counted.requestFocus();
            counted.selectAll();
        });
    }

    private static CaixaStatusDto statusOf(CaixaStatusDto.Session cur) {
        return new CaixaStatusDto("aberto", true, "", "", cur, null);
    }

    /**
     * Depois de fechar (como na web): imprime o cupom de fechamento e mostra o resumo do turno
     * ("Fechamento de Caixa (Cupom 80mm)") com sangrias/reforços detalhados e "Sair do sistema".
     */
    private void afterClose(CaixaStatusDto st) {
        CaixaStatusDto.Session closed = st == null ? null : st.lastSession();
        boolean offline = ctx.caixa.lastWriteOffline();
        Window owner = stage.getOwner();
        stage.close();
        if (closed == null) {
            Toasts.success(owner, "Caixa fechado. Vendas bloqueadas até nova abertura.");
            return;
        }
        if (offline) {
            Toasts.info(owner, "Caixa fechado localmente (modo offline). O encerramento será sincronizado ao reconectar.");
        } else {
            Toasts.success(owner, "Caixa fechado. Vendas bloqueadas até nova abertura.");
        }
        // O cupom vai para a impressora em segundo plano e o resumo abre na hora: um driver lento ou parado
        // não deixa mais a tela congelada e escura.
        CashClosingSummaryDialog.print(ctx, closed, owner, false,
                "Caixa fechado, mas o cupom de fechamento não foi impresso: ");
        // Abre o resumo depois que o painel terminou de fechar (fora do laço do painel).
        javafx.application.Platform.runLater(() -> CashClosingSummaryDialog.show(owner, ctx, closed, onLogout));
    }

    // ---------- caixa fechado ----------

    private void renderClosed(CaixaStatusDto s, boolean offline) {
        String reason = s == null || s.blockReason() == null || s.blockReason().isBlank() ? "Caixa fechado"
                : s.blockReason();
        setSubtitle(reason + " • " + companyName(), "rg-text-danger");

        VBox card = new VBox(6, cardTitle("Abrir caixa"),
                muted("Informe o fundo de troco que está na gaveta para começar o turno."));
        TextField opening = new TextField("0,00");
        opening.getStyleClass().add("rg-pay-input");
        card.getChildren().addAll(fieldLabel("Fundo de troco (R$)"), opening);
        if (s != null && s.lastSession() != null) {
            CaixaStatusDto.Session l = s.lastSession();
            card.getChildren().addAll(new Separator(), muted("Último turno: esperado R$ " + nz(l.expectedCashAmount())
                    + " • contado R$ " + nz(l.closingAmount()) + " • diferença R$ " + nz(l.differenceAmount())));
        }
        card.getStyleClass().add("rg-cash-card");

        Button open = Fx.button("Abrir caixa", "rg-cash-btn rg-cash-btn-primary", () -> { });
        open.setGraphic(Fx.icon(Fx.ICON_UNLOCK, "rg-icon-light"));
        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(e -> {
            hideError();
            String v;
            try {
                v = Money.formatBr(Money.parseBr(opening.getText()));
            } catch (DomainException ex) {
                showError(ex.getMessage());
                return;
            }
            setBusy(true);
            Fx.async(() -> ctx.caixa.abrir(v), st -> {
                setBusy(false);
                if (ctx.caixa.lastWriteOffline()) {
                    Toasts.info(stage.getOwner(), "Caixa aberto localmente (modo offline). Será sincronizado ao reconectar.");
                }
                stage.close();
            }, t -> {
                setBusy(false);
                showError(Fx.message(t));
            });
        });
        opening.setOnAction(e -> open.fire());
        body.getChildren().setAll(card, open);
        Platform.runLater(() -> {
            opening.requestFocus();
            opening.selectAll();
        });
    }

    // ---------- utilitários ----------

    private void setSubtitle(String text, String cls) {
        subtitle.setText(text);
        subtitle.getStyleClass().setAll("label", "rg-modal-subtitle", cls);
    }

    private void setBusy(boolean b) {
        busy = b;
        Fx.setBusy(stage, b);
        body.setDisable(b);
    }

    private void resize() {
        if (stage != null) {
            Platform.runLater(stage::sizeToScene);
        }
    }

    private void showNotice(String m) {
        notice.setText(m == null ? "" : m);
        show(notice, m != null);
        resize();
    }

    private void showError(String m) {
        error.setText(m);
        show(error, true);
        resize();
    }

    private void hideError() {
        show(error, false);
    }

    private static Label cardTitle(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-cash-card-title");
        return l;
    }

    private static Label fieldLabel(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-pay-field-label");
        return l;
    }

    private static Label muted(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-mini-muted");
        l.setWrapText(true);
        return l;
    }

    private static HBox line(String left, String right, boolean strong) {
        Label a = new Label(left);
        Label b = new Label(right);
        a.getStyleClass().add(strong ? "rg-cash-line-strong" : "rg-cash-line");
        b.getStyleClass().add(strong ? "rg-cash-line-strong" : "rg-cash-line");
        a.setWrapText(true);
        b.setMinWidth(Region.USE_PREF_SIZE);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox h = new HBox(8, a, sp, b);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static BigDecimal parseOrZero(String s) {
        try {
            return s == null || s.isBlank() ? BigDecimal.ZERO : Money.parseBr(s);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    private static void show(Node n, boolean v) {
        n.setVisible(v);
        n.setManaged(v);
    }

    private static String elapsed(long minutes) {
        if (minutes < 1) {
            return "menos de 1 min";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h > 0 ? h + "h " + String.format("%02d", m) + "min" : m + " min";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
