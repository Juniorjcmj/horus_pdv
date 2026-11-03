package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.FiscalApi;
import br.com.quack.caixa.api.PriceAuthApi;
import br.com.quack.caixa.api.SaleHistoryApi;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.service.ReceiptPrinter;
import java.math.BigDecimal;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
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
 * "Cancelar Venda #N" (porta de SaleCancelModal.tsx): cancela a venda inteira com a senha do gerente. O servidor
 * estorna estoque e fiado e grava a trilha de auditoria completa, inclusive tentativas negadas (senha errada).
 * Venda com NFC-e/NF-e autorizada não cancela por aqui: o cancelamento tem de ir à SEFAZ, então a tela leva para o
 * cancelamento da nota (que também cancela a venda).
 */
public final class SaleCancelDialog {
    private static final String ICON_BAN = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm0 2a8 8 0 0 0-6.3 12.9L16.9 5.7A8 8 0 0 0 12 4zm6.3 3.1L7.1 18.3A8 8 0 0 0 18.3 7.1z";
    private static final String ICON_CHECK = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1.2 13.6 6.4-6.4-1.4-1.4-5 5-2.4-2.4-1.4 1.4 3.8 3.8z";
    static final List<String> MOTIVOS_CANCELAMENTO = List.of(
            "Desistência da compra pelo cliente.",
            "Erro na forma de pagamento registrada.",
            "Erro nas quantidades ou itens lançados.",
            "Lançamento de venda duplicado no sistema.",
            "Cliente sem saldo / desistiu no caixa.",
            "Estorno solicitado pelo consumidor.");

    private final AppContext ctx;
    private final String saleNumber;
    private final VBox root = new VBox(0);
    private final VBox body = new VBox(12);
    private final Label subtitle = new Label("Exige autorização do gerente e estorna o estoque");
    private final TextArea justificativa = new TextArea(MOTIVOS_CANCELAMENTO.get(0));
    private final ComboBox<PriceAuthApi.Supervisor> gerente = new ComboBox<>();
    private final PasswordField senha = new PasswordField();
    private Stage stage;
    private SaleHistoryApi.SaleDetail detail;
    private FiscalApi.FiscalDoc fiscal;
    private boolean submitting;
    private boolean changed;

    private SaleCancelDialog(AppContext ctx, String saleNumber) {
        this.ctx = ctx;
        this.saleNumber = saleNumber;
    }

    /** Devolve true se a venda foi cancelada (por aqui ou pela nota). */
    public static boolean show(Window owner, AppContext ctx, String saleNumber) {
        SaleCancelDialog d = new SaleCancelDialog(ctx, saleNumber);
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.load();
        d.stage.showAndWait();
        return d.changed;
    }

    private void build() {
        root.setPrefWidth(540);
        root.getStyleClass().add("rg-preview-modal");
        Label title = new Label("Cancelar Venda #" + saleNumber);
        title.getStyleClass().add("rg-modal-title");
        subtitle.getStyleClass().add("rg-mini-muted");
        StackPane icon = new StackPane(Fx.icon(ICON_BAN, "rg-icon-danger"));
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
        justificativa.setPromptText("Descreva o motivo do cancelamento...");
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
        Fx.async(() -> new Object[] {ctx.saleHistoryApi.details(saleNumber), ctx.fiscalApi.bySaleNumber(saleNumber)},
                r -> {
                    detail = (SaleHistoryApi.SaleDetail) r[0];
                    fiscal = (FiscalApi.FiscalDoc) r[1];
                    render();
                }, t -> {
                    body.getChildren().setAll(error("Não foi possível carregar a venda. O cancelamento precisa de "
                            + "conexão com o servidor. " + Fx.message(t)), footer(Fx.button("Fechar", "rg-btn-action",
                                    this::close)));
                    resize();
                });
    }

    private void render() {
        String st = detail.status() == null ? "" : detail.status().trim().toLowerCase();
        if (st.startsWith("cancel")) {
            body.getChildren().setAll(error("Esta venda já está cancelada."),
                    footer(Fx.button("Fechar", "rg-btn-action", this::close)));
            resize();
            return;
        }
        if (st.equals("corrigida")) {
            body.getChildren().setAll(error("Esta venda foi corrigida e substituída por outra. Cancele a venda nova "
                    + "gerada pela correção."), footer(Fx.button("Fechar", "rg-btn-action", this::close)));
            resize();
            return;
        }
        if (fiscal != null && fiscal.autorizado()) {
            renderFiscal();
            return;
        }
        renderForm();
    }

    /** Venda com nota autorizada: cancelamento vai à SEFAZ (tela de cancelamento da NFC-e). */
    private void renderFiscal() {
        String tipo = fiscal.modelo55() ? "NF-e" : "NFC-e";
        Label info = muted("Esta venda tem " + tipo + " autorizada (nº "
                + ReceiptPrinter.formatNumeroNf(fiscal.numeroNf() == null ? "" : String.valueOf(fiscal.numeroNf()))
                + "). Pela lei, o cancelamento precisa ser homologado na SEFAZ: cancelar a nota cancela a venda, "
                + "estorna o estoque e fica registrado com o gerente que autorizou.");
        Button fechar = Fx.button("Voltar", "rg-btn-action", this::close);
        Button nota = Fx.button("Cancelar a " + tipo + " desta venda", "rg-btn-action rg-btn-danger", () -> {
            Window owner = stage.getOwner();
            stage.close();
            changed = NfceCancelDialog.show(owner, ctx, saleNumber);
        });
        body.getChildren().setAll(summary(), info, footer(fechar, nota));
        resize();
    }

    private void renderForm() {
        FlowPane motivos = new FlowPane(6, 6);
        for (String m : MOTIVOS_CANCELAMENTO) {
            Button chip = Fx.button(m, "rg-filter-chip", () -> justificativa.setText(m));
            chip.setFocusTraversable(false);
            motivos.getChildren().add(chip);
        }
        Button voltar = Fx.button("Voltar", "rg-btn-action", this::close);
        Button confirmar = Fx.button("Confirmar Cancelamento", "rg-btn-action rg-btn-danger", () -> { });
        confirmar.setGraphic(Fx.icon(ICON_BAN, "rg-icon-light"));
        Runnable refresh = () -> confirmar.setDisable(submitting || justificativa.getText().trim().length() < 5
                || gerente.getValue() == null || senha.getText().isBlank());
        justificativa.textProperty().addListener((o, a, b) -> refresh.run());
        gerente.valueProperty().addListener((o, a, b) -> refresh.run());
        senha.textProperty().addListener((o, a, b) -> refresh.run());
        refresh.run();
        confirmar.setOnAction(e -> submit(confirmar, voltar));
        senha.setOnAction(e -> {
            if (!confirmar.isDisabled()) {
                submit(confirmar, voltar);
            }
        });

        Label ml = new Label("Motivos frequentes:");
        ml.getStyleClass().add("rg-mini-muted");
        Label jl = new Label("Justificativa do cancelamento *");
        jl.getStyleClass().add("rg-pay-field-label");
        GridPane auth = new GridPane();
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
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            auth.getColumnConstraints().add(cc);
        }
        Label aviso = muted("As mercadorias voltam ao estoque, o fiado (se houver) é estornado e o valor sai do "
                + "fechamento do caixa. Fica registrado quem pediu, o gerente que autorizou, o motivo, este terminal "
                + "e o horário; tentativas com senha errada também ficam registradas.");
        body.getChildren().setAll(summary(), ml, motivos, jl, justificativa, auth, aviso, footer(voltar, confirmar));
        if (fiscal != null && (fiscal.status() == FiscalApi.ASSINADO || fiscal.status() == FiscalApi.TRANSMITINDO
                || fiscal.status() == FiscalApi.CONTINGENCIA_PENDENTE)) {
            String tipo = fiscal.modelo55() ? "NF-e" : "NFC-e";
            Label nota = muted("A " + tipo + " desta venda ainda não foi autorizada pela SEFAZ"
                    + (fiscal.status() == FiscalApi.CONTINGENCIA_PENDENTE ? " (emitida em contingência)" : "")
                    + ". Ao cancelar a venda, o sistema não emite a nota (o número é inutilizado) ou, se ela já tiver "
                    + "sido transmitida, cancela a nota na SEFAZ assim que for autorizada. Tudo fica na trilha.");
            nota.getStyleClass().add("rg-nfc-warn-box");
            body.getChildren().add(1, nota);
        }
        resize();
        javafx.application.Platform.runLater(() -> (gerente.getValue() == null ? gerente : senha).requestFocus());
    }

    private VBox summary() {
        Label venda = new Label("Venda #" + saleNumber);
        venda.getStyleClass().add("rg-cash-card-title");
        Label valor = new Label("R$ " + dash(detail.totalAmount(), "0,00"));
        valor.getStyleClass().addAll("rg-cash-card-title", "rg-text-danger");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        VBox box = new VBox(4, new HBox(venda, sp, valor), muted(dash(detail.operatorName(), "Operador") + " · "
                + dash(detail.saleDate(), "-") + " · " + dash(detail.customerName(), "Consumidor")));
        for (SaleHistoryApi.Item it : detail.uniqueItems()) {
            box.getChildren().add(kv(Money.formatQty(it.quantity()) + "x " + it.productName(),
                    "R$ " + dash(it.itemTotal(), "0,00"), null));
        }
        for (SaleHistoryApi.Payment p : detail.payments() == null ? List.<SaleHistoryApi.Payment>of()
                : detail.payments()) {
            box.getChildren().add(kv(PaymentType.fromWire(p.paymentType()).label(),
                    "R$ " + Money.formatBr(p.amount() == null ? BigDecimal.ZERO : p.amount()), "rg-mini-muted"));
        }
        box.getStyleClass().add("rg-cash-card");
        return box;
    }

    private void submit(Button confirmar, Button voltar) {
        String just = justificativa.getText().trim();
        if (just.length() < 5 || gerente.getValue() == null || senha.getText().isBlank()) {
            Toasts.error(stage, "Informe a justificativa (mínimo 5 caracteres), o gerente e a senha.");
            return;
        }
        String supId = gerente.getValue().id();
        String pwd = senha.getText();
        submitting = true;
        Fx.setBusy(stage, true);
        setFormDisabled(true, confirmar, voltar);
        confirmar.setText("Cancelando...");
        Fx.async(() -> ctx.saleHistoryApi.cancel(saleNumber, supId, pwd, just, ctx.config.deviceId()), result -> {
            submitting = false;
            Fx.setBusy(stage, false);
            changed = true;
            // O estoque voltou no servidor: recarrega o catálogo em segundo plano.
            Fx.async(() -> {
                ctx.catalog.load();
                return true;
            }, ok -> { }, t -> { });
            Toasts.success(stage.getOwner(), "Venda #" + saleNumber + " cancelada. Autorizado por "
                    + dash(result.supervisorNome(), "gerente") + ".");
            renderDone(result, just);
        }, t -> {
            submitting = false;
            Fx.setBusy(stage, false);
            setFormDisabled(false, confirmar, voltar);
            senha.clear();
            confirmar.setText("Confirmar Cancelamento");
            if ("NOTA_AUTORIZADA".equals(SaleHistoryApi.cancelCode(t))) {
                // A nota foi autorizada depois que a tela abriu: segue pelo cancelamento fiscal.
                String msg = Fx.message(t);
                Fx.async(() -> ctx.fiscalApi.bySaleNumber(saleNumber), doc -> {
                    fiscal = doc;
                    if (fiscal != null && fiscal.autorizado()) {
                        renderFiscal();
                    } else {
                        Toasts.error(stage, msg);
                    }
                }, e -> Toasts.error(stage, msg));
                return;
            }
            Toasts.error(stage, Fx.message(t));
        });
    }

    private void setFormDisabled(boolean v, Button confirmar, Button voltar) {
        confirmar.setDisable(v);
        voltar.setDisable(v);
        justificativa.setDisable(v);
        gerente.setDisable(v);
        senha.setDisable(v);
    }

    private void renderDone(SaleHistoryApi.CancelResult r, String just) {
        subtitle.setText("Cancelamento concluído");
        StackPane ok = new StackPane(Fx.icon(ICON_CHECK, "rg-icon-success"));
        ok.setScaleX(1.6);
        ok.setScaleY(1.6);
        ok.setMinHeight(40);
        Label t = new Label("Venda Cancelada");
        t.getStyleClass().add("rg-modal-title");
        VBox info = new VBox(3,
                kv("Venda:", "#" + saleNumber, null),
                kv("Valor estornado:", "R$ " + dash(detail.totalAmount(), "0,00"), "rg-text-danger"),
                kv("Itens devolvidos ao estoque:", String.valueOf(r.itensEstornados()), null),
                kv("Autorizado por:", dash(r.supervisorNome(), "-"), null),
                kv("Data/hora:", dash(r.canceladoEm(), "-"), null));
        if (r.fiadoEstornado() != null && r.fiadoEstornado().signum() > 0) {
            info.getChildren().add(kv("Fiado estornado:", "R$ " + Money.formatBr(r.fiadoEstornado()), null));
        }
        info.getStyleClass().add("rg-corr-ok-box");
        String operador = ctx.session.user() == null ? "" : ctx.session.user().name();
        Button imprimir = Fx.button("Imprimir Comprovante (assinaturas)", "rg-btn-action rg-cash-btn-primary",
                () -> print(ReceiptPrinter.buildCancelamentoVenda(ctx.session.company(), detail, r, just, operador)));
        imprimir.setGraphic(Fx.icon(Fx.ICON_PRINTER, "rg-icon-light"));
        Button fechar = Fx.button("Fechar", "rg-btn-action", this::close);
        HBox actions = new HBox(8, imprimir, fechar);
        actions.setAlignment(Pos.CENTER);
        VBox v = new VBox(10, ok, t, info, actions);
        v.setAlignment(Pos.TOP_CENTER);
        body.getChildren().setAll(v);
        resize();
    }

    private void print(Node receipt) {
        ReceiptPrinter.printAsync(receipt, err -> {
            if (!stage.isShowing()) {
                return;
            }
            if (err == null) {
                Toasts.success(stage, "Comprovante enviado para a impressora.");
            } else {
                Toasts.error(stage, "Não foi possível imprimir: " + err + ". Confira a impressora.");
            }
        });
    }

    // ---------- utilitários ----------

    private HBox footer(Button... buttons) {
        HBox f = new HBox(8, buttons);
        f.setAlignment(Pos.CENTER_RIGHT);
        f.getStyleClass().add("rg-nfc-footer");
        return f;
    }

    private static HBox kv(String k, String v, String valueClass) {
        Label a = new Label(k);
        a.getStyleClass().add("rg-mini-muted");
        a.setWrapText(true);
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

    @SuppressWarnings("unused")
    private static BigDecimal money(String v) {
        try {
            return v == null || v.isBlank() ? BigDecimal.ZERO : Money.parseBr(v);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }
}
