package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.SaleHistoryApi;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * "Correção de Venda #N" (porta de SaleCorrectionModal.tsx): marca produtos para remover, justifica e o
 * servidor estorna a venda e gera uma nova com o restante (nova NFC-e). Exige perfil Financeiro/Administrador.
 */
public final class SaleCorrectionDialog {
    private static final String ICON_EDIT = "M3 17.2V21h3.8L17.8 10l-3.8-3.8L3 17.2zM20.7 7.1a1 1 0 0 0 0-1.4l-2.4-2.4a1 1 0 0 0-1.4 0l-1.8 1.8 3.8 3.8 1.8-1.8z";
    private static final String ICON_CHECK = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1.2 13.6 6.4-6.4-1.4-1.4-5 5-2.4-2.4-1.4 1.4 3.8 3.8z";
    static final List<String> MOTIVOS_CORRECAO = List.of(
            "Item lançado por engano.",
            "Cliente devolveu produto parcialmente.",
            "Erro de quantidade no lançamento.",
            "Produto com defeito / avariado.",
            "Troca parcial de mercadoria.");

    private enum Step { SELECT, JUSTIFY, DONE }

    private final AppContext ctx;
    private final String saleNumber;
    private final VBox root = new VBox(0);
    private final VBox body = new VBox(12);
    private final Label subtitle = new Label();
    private final Set<String> selected = new LinkedHashSet<>();
    private final TextArea justificativa = new TextArea(MOTIVOS_CORRECAO.get(0));
    /** Gerente/administrador que autoriza a correção com a senha (obrigatório no caixa). */
    private final javafx.scene.control.ComboBox<br.com.quack.caixa.api.PriceAuthApi.Supervisor> gerente =
            new javafx.scene.control.ComboBox<>();
    private final javafx.scene.control.PasswordField senha = new javafx.scene.control.PasswordField();
    private Stage stage;
    private SaleHistoryApi.SaleDetail detail;
    private Step step = Step.SELECT;
    private boolean submitting;
    private boolean changed;

    private SaleCorrectionDialog(AppContext ctx, String saleNumber) {
        this.ctx = ctx;
        this.saleNumber = saleNumber;
    }

    /** Devolve true se a venda foi corrigida. */
    public static boolean show(Window owner, AppContext ctx, String saleNumber) {
        SaleCorrectionDialog d = new SaleCorrectionDialog(ctx, saleNumber);
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.load();
        d.stage.showAndWait();
        return d.changed;
    }

    private void build() {
        root.setPrefWidth(520);
        root.getStyleClass().add("rg-preview-modal");
        Label title = new Label("Correção de Venda #" + saleNumber);
        title.getStyleClass().add("rg-modal-title");
        subtitle.getStyleClass().add("rg-mini-muted");
        StackPane icon = new StackPane(Fx.icon(ICON_EDIT, "rg-icon-brand"));
        icon.getStyleClass().add("rg-closing-icon");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = Fx.button("✕", "rg-btn-icon", this::close);
        close.setFocusTraversable(false);
        HBox header = new HBox(10, icon, new VBox(1, title, subtitle), sp, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("rg-preview-header");
        body.getStyleClass().add("rg-nfc-body");
        justificativa.setPrefRowCount(3);
        justificativa.setWrapText(true);
        justificativa.setPromptText("Descreva o motivo da correção...");
        gerente.setPromptText("Selecione o gerente...");
        gerente.setMaxWidth(Double.MAX_VALUE);
        senha.setPromptText("Senha do gerente selecionado...");
        root.getChildren().addAll(header, body);
        body.getChildren().setAll(muted("Carregando a venda..."));
    }

    private void load() {
        Fx.async(ctx.priceAuthApi::supervisors, list -> {
            gerente.getItems().setAll(list);
            if (list.size() == 1) {
                gerente.setValue(list.get(0));
            }
        }, t -> Toasts.error(stage, "Não foi possível carregar a lista de gerentes."));
        Fx.async(() -> ctx.saleHistoryApi.details(saleNumber), d -> {
            detail = d;
            render();
        }, t -> {
            body.getChildren().setAll(error("Não foi possível carregar os detalhes da venda. " + Fx.message(t)),
                    footer(Fx.button("Fechar", "rg-btn-action", this::close)));
            resize();
        });
    }

    private void render() {
        subtitle.setText(switch (step) {
            case SELECT -> "Selecione os itens para remover";
            case JUSTIFY -> "Informe a justificativa";
            case DONE -> "Correção concluída";
        });
        if (step == Step.SELECT) {
            renderSelect();
        } else if (step == Step.JUSTIFY) {
            renderJustify();
        }
        resize();
    }

    // ---------- etapa 1: itens ----------

    private void renderSelect() {
        List<SaleHistoryApi.Item> items = detail.uniqueItems();
        BigDecimal total = money(detail.totalAmount());

        Label venda = new Label("Venda #" + saleNumber);
        venda.getStyleClass().add("rg-cash-card-title");
        Label valor = new Label("R$ " + Money.formatBr(total));
        valor.getStyleClass().add("rg-cash-card-title");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label meta = muted(dash(detail.operatorName(), "Operador") + " · " + dash(detail.saleDate(), "-") + " · "
                + dash(detail.customerName(), "Consumidor"));
        VBox resumo = new VBox(4, new HBox(venda, sp, valor), meta);
        resumo.getStyleClass().add("rg-cash-card");

        Label marque = new Label("Marque os itens para remover:");
        marque.getStyleClass().add("rg-pay-field-label");
        VBox lista = new VBox(0);
        lista.getStyleClass().add("rg-corr-list");
        if (items.isEmpty()) {
            lista.getChildren().add(muted("Nenhum item encontrado."));
        }
        for (SaleHistoryApi.Item it : items) {
            boolean checked = selected.contains(it.productCode());
            CheckBox cb = new CheckBox();
            cb.setSelected(checked);
            cb.setFocusTraversable(false);
            Label nome = new Label(it.productName());
            nome.getStyleClass().add(checked ? "rg-corr-removed" : "rg-cash-line-strong");
            Label det = muted("Cód. " + it.productCode() + " · " + Money.formatQty(it.quantity()) + "x · R$ "
                    + Money.formatBr(money(it.unitPrice())));
            Region s2 = new Region();
            HBox.setHgrow(s2, Priority.ALWAYS);
            Label tot = new Label("R$ " + Money.formatBr(money(it.itemTotal())));
            tot.getStyleClass().add(checked ? "rg-corr-removed-total" : "rg-cash-line-strong");
            HBox row = new HBox(10, cb, new VBox(1, nome, det), s2, tot);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().addAll("rg-corr-row", checked ? "rg-corr-row-on" : "rg-corr-row-off");
            Runnable toggle = () -> {
                if (!selected.remove(it.productCode())) {
                    selected.add(it.productCode());
                }
                render();
            };
            row.setOnMouseClicked(e -> toggle.run());
            cb.setOnAction(e -> toggle.run());
            lista.getChildren().add(row);
        }

        BigDecimal removido = removedTotal(items);
        boolean none = selected.isEmpty();
        boolean all = !items.isEmpty() && selected.size() == items.size();
        Node preview = null;
        if (!none) {
            if (all) {
                preview = error("Todos os itens selecionados — use o cancelamento da venda.");
            } else {
                VBox p = new VBox(2, kv("Removendo:", "- R$ " + Money.formatBr(removido), "rg-text-danger"),
                        kv("Novo total:", "R$ " + Money.formatBr(total.subtract(removido)), "rg-text-success"));
                p.getStyleClass().add("rg-corr-ok-box");
                preview = p;
            }
        }

        Button cancelar = Fx.button("Cancelar", "rg-btn-action", this::close);
        Button continuar = Fx.button("Continuar (" + selected.size() + (selected.size() == 1 ? " item)" : " itens)"),
                "rg-btn-action rg-cash-btn-primary", () -> {
                    step = Step.JUSTIFY;
                    render();
                });
        continuar.setDisable(none || all);

        body.getChildren().setAll(resumo, marque, lista);
        if (preview != null) {
            body.getChildren().add(preview);
        }
        body.getChildren().add(footer(cancelar, continuar));
    }

    // ---------- etapa 2: justificativa ----------

    private void renderJustify() {
        List<SaleHistoryApi.Item> items = detail.uniqueItems();
        BigDecimal removido = removedTotal(items);
        BigDecimal total = money(detail.totalAmount());
        VBox resumo = new VBox(3,
                kv("Itens a remover:", String.valueOf(selected.size()), "rg-text-danger"),
                kv("Valor removido:", "- R$ " + Money.formatBr(removido), "rg-text-danger"),
                kv("Novo total da venda:", "R$ " + Money.formatBr(total.subtract(removido)), "rg-text-success"));
        resumo.getStyleClass().add("rg-cash-card");

        FlowPane motivos = new FlowPane(6, 6);
        for (String m : MOTIVOS_CORRECAO) {
            Button chip = Fx.button(m, "rg-filter-chip", () -> justificativa.setText(m));
            chip.setFocusTraversable(false);
            motivos.getChildren().add(chip);
        }

        Button voltar = Fx.button("Voltar", "rg-btn-action", () -> {
            if (!submitting) {
                step = Step.SELECT;
                render();
            }
        });
        Button confirmar = Fx.button("Confirmar Correção", "rg-btn-action rg-cash-btn-primary", () -> { });
        confirmar.setGraphic(Fx.icon(ICON_EDIT, "rg-icon-light"));
        // Confirma só com justificativa, gerente e senha (a correção é autorizada pelo gerente).
        Runnable refresh = () -> confirmar.setDisable(submitting || justificativa.getText().trim().length() < 5
                || gerente.getValue() == null || senha.getText().isBlank());
        justificativa.textProperty().addListener((o, a, b) -> refresh.run());
        gerente.valueProperty().addListener((o, a, b) -> refresh.run());
        senha.textProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();
        confirmar.setOnAction(e -> submit(confirmar, voltar));

        Label ml = new Label("Motivos frequentes:");
        ml.getStyleClass().add("rg-mini-muted");
        Label jl = new Label("Justificativa da correção *");
        jl.getStyleClass().add("rg-pay-field-label");
        javafx.scene.layout.GridPane auth = new javafx.scene.layout.GridPane();
        auth.setHgap(10);
        auth.setVgap(4);
        Label gl = new Label("Gerente / Supervisor *");
        gl.getStyleClass().add("rg-pay-field-label");
        Label sl = new Label("Senha do Gerente *");
        sl.getStyleClass().add("rg-pay-field-label");
        auth.add(gl, 0, 0);
        auth.add(sl, 1, 0);
        auth.add(gerente, 0, 1);
        auth.add(senha, 1, 1);
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            auth.getColumnConstraints().add(cc);
        }
        Label aviso = muted("A correção estorna a venda e gera uma nova com os itens restantes. Fica registrada com o "
                + "nome do gerente que autorizou.");
        body.getChildren().setAll(resumo, ml, motivos, jl, justificativa, auth, aviso, footer(voltar, confirmar));
        javafx.application.Platform.runLater(() -> (gerente.getValue() == null ? gerente : senha).requestFocus());
    }

    private void submit(Button confirmar, Button voltar) {
        String just = justificativa.getText().trim();
        if (just.length() < 5) {
            Toasts.error(stage, "Informe uma justificativa de no mínimo 5 caracteres.");
            return;
        }
        if (gerente.getValue() == null || senha.getText().isBlank()) {
            Toasts.error(stage, "Selecione o gerente e digite a senha.");
            return;
        }
        String supId = gerente.getValue().id();
        String pwd = senha.getText();
        submitting = true;
        Fx.setBusy(stage, true);
        confirmar.setDisable(true);
        voltar.setDisable(true);
        justificativa.setDisable(true);
        gerente.setDisable(true);
        senha.setDisable(true);
        confirmar.setText("Corrigindo venda...");
        List<String> codes = List.copyOf(selected);
        Fx.async(() -> ctx.saleHistoryApi.correct(saleNumber, codes, just, supId, pwd), result -> {
            submitting = false;
            Fx.setBusy(stage, false);
            changed = true;
            step = Step.DONE;
            subtitle.setText("Correção concluída");
            Toasts.success(stage.getOwner(), "Venda #" + saleNumber + " corrigida. Nova venda: #"
                    + result.novaVendaNumber() + ".");
            renderDone(result);
        }, t -> {
            submitting = false;
            Fx.setBusy(stage, false);
            voltar.setDisable(false);
            justificativa.setDisable(false);
            gerente.setDisable(false);
            senha.setDisable(false);
            senha.clear();
            confirmar.setText("Confirmar Correção");
            Toasts.error(stage, Fx.message(t));
        });
    }

    // ---------- etapa 3: resultado ----------

    private void renderDone(SaleHistoryApi.CorrectResult r) {
        StackPane ok = new StackPane(Fx.icon(ICON_CHECK, "rg-icon-success"));
        ok.setScaleX(1.6);
        ok.setScaleY(1.6);
        ok.setMinHeight(40);
        Label t = new Label("Venda Corrigida");
        t.getStyleClass().add("rg-modal-title");
        Label sub = muted("Venda original #" + r.vendaOriginalNumber() + " → nova venda #" + r.novaVendaNumber());
        VBox info = new VBox(3,
                kv("Itens removidos:", String.valueOf(r.itensRemovidos()), null),
                kv("Novo total:", "R$ " + r.novoTotal(), null),
                kv("Autorizado por:", dash(r.financeiroNome(), "-"), null),
                kv("Data/hora:", dash(r.corrigidaEm(), "-"), null));
        if (r.fiscalQueued()) {
            info.getChildren().add(kv("NFC-e:", "Enfileirada para emissão", "rg-text-success"));
        }
        info.getStyleClass().add("rg-corr-ok-box");
        Button imprimir = Fx.button("Imprimir Cupom da Nova Venda", "rg-btn-action rg-cash-btn-primary",
                () -> printNewSale(r));
        imprimir.setGraphic(Fx.icon(Fx.ICON_PRINTER, "rg-icon-light"));
        Button fechar = Fx.button("Fechar", "rg-btn-action", this::close);
        HBox actions = new HBox(8, imprimir, fechar);
        actions.setAlignment(Pos.CENTER);
        VBox v = new VBox(10, ok, t, sub, info, actions);
        v.setAlignment(Pos.TOP_CENTER);
        body.getChildren().setAll(v);
        resize();
    }

    /** Abre a pré-visualização do comprovante da venda nova (DANFE quando a NFC-e ficar pronta). */
    private void printNewSale(SaleHistoryApi.CorrectResult r) {
        Fx.async(() -> ctx.saleHistoryApi.details(r.novaVendaNumber()), d ->
                ReceiptPreviewDialog.show(stage, ctx, SaleHistoryApi.toOutcome(d, r.fiscalQueued())),
                t -> Toasts.error(stage, "Falha ao preparar impressão da nova venda: " + Fx.message(t)));
    }

    // ---------- utilitários ----------

    private BigDecimal removedTotal(List<SaleHistoryApi.Item> items) {
        BigDecimal s = BigDecimal.ZERO;
        for (SaleHistoryApi.Item it : items) {
            if (selected.contains(it.productCode())) {
                s = s.add(money(it.itemTotal()));
            }
        }
        return s;
    }

    private HBox footer(Button... buttons) {
        HBox f = new HBox(8, buttons);
        f.setAlignment(Pos.CENTER_RIGHT);
        f.getStyleClass().add("rg-nfc-footer");
        return f;
    }

    private static HBox kv(String k, String v, String valueClass) {
        Label a = new Label(k);
        a.getStyleClass().add("rg-mini-muted");
        Label b = new Label(v);
        b.getStyleClass().add("rg-cash-line-strong");
        if (valueClass != null) {
            b.getStyleClass().add(valueClass);
        }
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        return new HBox(8, a, sp, b);
    }

    private static Label muted(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-mini-muted");
        l.setWrapText(true);
        return l;
    }

    private static Label error(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-nfc-error-box");
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private void close() {
        if (!submitting) {
            stage.close();
        }
    }

    private void resize() {
        if (stage != null && stage.isShowing()) {
            javafx.application.Platform.runLater(stage::sizeToScene);
        }
    }

    private static String dash(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static BigDecimal money(String v) {
        try {
            return v == null || v.isBlank() ? BigDecimal.ZERO : Money.parseBr(v);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }
}
