package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.FiscalApi;
import br.com.quack.caixa.api.PriceAuthApi;
import br.com.quack.caixa.service.ReceiptPrinter;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
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
 * Cancelamento de NFC-e/NF-e com senha de gerente, ou NF-e de devolução (modelo 55) quando o prazo legal
 * passou (porta de PdvNfceCancelModal.tsx). F7 na frente de caixa.
 */
public final class NfceCancelDialog {
    private static final String ICON_ALERT = "M8.3 2h7.4L22 8.3v7.4L15.7 22H8.3L2 15.7V8.3L8.3 2zm.8 2L4 9.1v5.8L9.1 20h5.8l5.1-5.1V9.1L14.9 4H9.1zM11 7h2v6h-2V7zm0 8h2v2h-2v-2z";
    private static final String ICON_SWAP = "M7 7h11l-3-3 1.4-1.4L21.8 8l-5.4 5.4L15 12l3-3H7V7zm10 10H6l3 3-1.4 1.4L2.2 16l5.4-5.4L9 12l-3 3h11v2z";
    private static final String ICON_CHECK = "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20zm-1.2 13.6 6.4-6.4-1.4-1.4-5 5-2.4-2.4-1.4 1.4 3.8 3.8z";
    private static final String ICON_SEARCH = "M9.5 3a6.5 6.5 0 0 1 5.17 10.44l4.45 4.45-1.41 1.41-4.45-4.45A6.5 6.5 0 1 1 9.5 3zm0 2a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z";

    static final List<String> MOTIVOS_CANCELAMENTO = List.of(
            "Desistência da compra pelo cliente.",
            "Erro na forma de pagamento registrada.",
            "Erro nas quantidades ou itens lançados.",
            "Emissão duplicada de documento fiscal.");
    static final List<String> MOTIVOS_DEVOLUCAO = List.of(
            "Devolução de mercadoria pelo cliente.",
            "Estorno de venda com prazo de NFC-e expirado.",
            "Devolução por desistência do consumidor.",
            "Troca e estorno integral de produtos.");

    private enum Mode { CANCELAR, DEVOLVER }

    private final AppContext ctx;
    private final VBox root = new VBox(0);
    private final VBox body = new VBox(12);
    private final HBox header = new HBox(10);
    private final StackPane headerIcon = new StackPane();
    private final Label headerTitle = new Label();
    private final Label headerSub = new Label();

    private final TextField codigo = new TextField();
    private final Button buscar = new Button("Buscar");
    private final Label searchError = new Label();
    private final VBox docArea = new VBox(10);
    private final VBox authArea = new VBox(10);
    private final ComboBox<PriceAuthApi.Supervisor> gerente = new ComboBox<>();
    private final PasswordField senha = new PasswordField();
    private final TextArea justificativa = new TextArea();
    private final Label contador = new Label();
    private final FlowPane motivos = new FlowPane(6, 6);
    private final VBox clienteBox = new VBox(6);
    private final TextField clienteCpf = new TextField();
    private final TextField clienteNome = new TextField();
    private final Button confirmar = new Button();
    private final Label motivosLabel = new Label();

    private Stage stage;
    private Mode mode = Mode.CANCELAR;
    private FiscalApi.FiscalDoc doc;
    private String sefazRejeicaoPrazo;
    private boolean busy;
    private boolean changed;
    /** "Identificação do Consumidor (Opcional)" começa recolhida, como na web. */
    private boolean clienteAberto;

    private NfceCancelDialog(AppContext ctx) {
        this.ctx = ctx;
    }

    /** initialCode: nº da venda/nota/chave para já buscar ao abrir (ou null). Devolve true se cancelou/devolveu. */
    public static boolean show(Window owner, AppContext ctx, String initialCode) {
        NfceCancelDialog d = new NfceCancelDialog(ctx);
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.loadSupervisors();
        d.stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> {
            if (initialCode != null && !initialCode.isBlank()) {
                d.codigo.setText(initialCode);
                d.search();
            } else {
                d.codigo.requestFocus();
            }
        });
        d.stage.showAndWait();
        return d.changed;
    }

    // ---------- layout ----------

    private void build() {
        root.setPrefWidth(600);
        root.getStyleClass().add("rg-preview-modal");

        headerTitle.getStyleClass().add("rg-modal-title");
        headerSub.getStyleClass().add("rg-mini-muted");
        headerIcon.getStyleClass().add("rg-nfc-icon");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = Fx.button("✕", "rg-btn-icon-flat", this::close);
        close.setFocusTraversable(false);
        header.getChildren().addAll(headerIcon, new VBox(1, headerTitle, headerSub), sp, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("rg-nfc-header");

        body.getStyleClass().add("rg-nfc-body");
        root.getChildren().addAll(header, body);
        renderForm();
    }

    private void updateHeader() {
        boolean m55 = doc != null && doc.modelo55();
        headerIcon.getChildren().setAll(Fx.icon(mode == Mode.DEVOLVER ? ICON_SWAP : ICON_ALERT,
                mode == Mode.DEVOLVER ? "rg-icon-brand" : "rg-icon-danger"));
        header.getStyleClass().removeAll("rg-nfc-header-danger", "rg-nfc-header-accent");
        header.getStyleClass().add(mode == Mode.DEVOLVER ? "rg-nfc-header-accent" : "rg-nfc-header-danger");
        if (mode == Mode.DEVOLVER) {
            headerTitle.setText(m55 ? "Devolução Fiscal de NF-e (Modelo 55 Entrada)" : "Devolução Fiscal (NF-e 55 Entrada)");
            headerSub.setText("Emissão de NF-e Modelo 55 de entrada referenciando a " + (m55 ? "NF-e" : "NFC-e"));
        } else {
            headerTitle.setText(m55 ? "Cancelar NF-e (Modelo 55)" : "Cancelar NFC-e (Modelo 65)");
            headerSub.setText("Cancelamento oficial perante a SEFAZ com autorização gerencial");
        }
    }

    private void renderForm() {
        updateHeader();
        boolean m55 = doc != null && doc.modelo55();

        Label lbl = label("Código da " + (m55 ? "NF-e" : "NFC-e") + ", Número da Venda ou Chave de Acesso *");
        codigo.setPromptText("Digite o número e tecle Enter (ou bipe a chave de acesso)...");
        codigo.setOnAction(e -> search());
        HBox.setHgrow(codigo, Priority.ALWAYS);
        buscar.getStyleClass().setAll("button", "rg-cash-btn", "rg-cash-btn-primary");
        buscar.setGraphic(Fx.icon(ICON_SEARCH, "rg-icon-light"));
        buscar.setOnAction(e -> search());
        HBox searchRow = new HBox(8, codigo, buscar);
        Label dica = new Label("Dica: Digite o número da venda (ex.: 1024), número da NFC-e ou bipe a chave de 44 "
                + "dígitos e aperte Enter.");
        dica.setWrapText(true);
        dica.getStyleClass().add("rg-mini-muted");

        searchError.getStyleClass().add("rg-nfc-error-box");
        searchError.setWrapText(true);
        show(searchError, searchError.getText() != null && !searchError.getText().isBlank());

        renderDocArea();
        renderAuthArea();

        Button voltar = Fx.button("Voltar / Fechar", "rg-btn-action", this::close);
        confirmar.setOnAction(e -> submit());
        confirmar.getStyleClass().setAll("button", "rg-btn-action");
        HBox footer = new HBox(8, voltar, confirmar);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("rg-nfc-footer");
        refreshConfirm();

        body.getChildren().setAll(new VBox(4, lbl, searchRow, dica), searchError, docArea, authArea, footer);
        resize();
    }

    private void renderDocArea() {
        docArea.getChildren().clear();
        if (doc == null) {
            show(docArea, false);
            return;
        }
        show(docArea, true);
        boolean m55 = doc.modelo55();
        Label titulo = new Label("Venda #" + nz(doc.saleNumber()) + " · " + (m55 ? "NF-e " : "NFC-e ")
                + ReceiptPrinter.formatNumeroNf(str(doc.numeroNf())) + " (Série " + str(doc.serie()) + ")");
        titulo.getStyleClass().add("rg-cash-card-title");
        Label badge = new Label(FiscalApi.statusLabel(doc.status(), doc.modelo()));
        badge.getStyleClass().addAll("rg-badge", badgeClass(doc.status()));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(8, titulo, sp, badge);
        top.setAlignment(Pos.CENTER_LEFT);

        GridPane g = new GridPane();
        g.setHgap(12);
        g.setVgap(2);
        String[][] cells = {
                {"Valor Total", doc.totalAmount() == null || doc.totalAmount().isBlank() ? "—" : "R$ " + doc.totalAmount()},
                {"Emitido em", formatDate(doc.dhAutorizacao() != null ? doc.dhAutorizacao() : doc.criadoEm())},
                {"Protocolo SEFAZ", dash(doc.protocolo())},
                {"Cliente", nz(doc.customerName()).isBlank() ? "Consumidor" : doc.customerName()}};
        for (int i = 0; i < cells.length; i++) {
            Label k = new Label(cells[i][0]);
            k.getStyleClass().add("rg-mini-muted");
            Label v = new Label(cells[i][1]);
            v.getStyleClass().add("rg-cash-line-strong");
            g.add(new VBox(1, k, v), i, 0);
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(25);
            g.getColumnConstraints().add(cc);
        }
        VBox card = new VBox(8, top, g);
        if (doc.chaveAcesso() != null && !doc.chaveAcesso().isBlank()) {
            Label k = new Label("Chave de Acesso:");
            k.getStyleClass().add("rg-mini-muted");
            Label v = new Label(ReceiptPrinter.formatChave(doc.chaveAcesso()));
            v.getStyleClass().add("rg-nfc-key");
            v.setWrapText(true);
            card.getChildren().add(new VBox(1, k, v));
        }
        card.getStyleClass().add("rg-cash-card");
        docArea.getChildren().add(card);

        if (!doc.autorizado()) {
            docArea.getChildren().add(warn("Este documento fiscal está com status "
                    + FiscalApi.statusLabel(doc.status(), doc.modelo()) + " e não pode ser estornado. Apenas notas "
                    + "com status Autorizado são passíveis de cancelamento ou devolução perante a SEFAZ.", false));
            return;
        }
        long min = minutesElapsed();
        if (expired()) {
            docArea.getChildren().add(warn((m55
                    ? "Prazo legal de 24 horas para cancelamento da NF-e expirado (" + (min / 60) + "h decorridas)."
                    : "Prazo legal de 30 minutos expirado (" + min + " min desde a autorização).")
                    + "\nA SEFAZ rejeitará o cancelamento direto. Para anular os tributos e reverter o estoque "
                    + "conforme a legislação, emita uma NF-e de Devolução (Modelo 55).", false));
        }
        if (sefazRejeicaoPrazo != null) {
            docArea.getChildren().add(warn("Rejeição da SEFAZ: Prazo expirado.\n" + sefazRejeicaoPrazo, true));
        }

        // Abas: cancelar x devolver
        ToggleGroup tg = new ToggleGroup();
        ToggleButton tCancel = new ToggleButton("Cancelar " + (m55 ? "NF-e" : "NFC-e") + " "
                + (expired() ? "(Prazo Expirado)" : m55 ? "(Até 24h)" : "(Até 30 min)"));
        ToggleButton tDev = new ToggleButton("Emitir Devolução (NF-e 55)" + (expired() ? " ★ Recomendado" : ""));
        for (ToggleButton t : new ToggleButton[] {tCancel, tDev}) {
            t.setToggleGroup(tg);
            t.getStyleClass().add("rg-nfc-tab");
            t.setMaxWidth(Double.MAX_VALUE);
            t.setFocusTraversable(false);
            HBox.setHgrow(t, Priority.ALWAYS);
        }
        tCancel.getStyleClass().add("rg-nfc-tab-danger");
        (mode == Mode.CANCELAR ? tCancel : tDev).setSelected(true);
        tCancel.setOnAction(e -> switchMode(Mode.CANCELAR, MOTIVOS_CANCELAMENTO.get(0)));
        tDev.setOnAction(e -> switchMode(Mode.DEVOLVER, MOTIVOS_DEVOLUCAO.get(0)));
        HBox tabs = new HBox(4, tCancel, tDev);
        tabs.getStyleClass().add("rg-nfc-tabs");
        docArea.getChildren().add(tabs);
    }

    private void renderAuthArea() {
        authArea.getChildren().clear();
        boolean visible = doc != null && doc.autorizado();
        show(authArea, visible);
        if (!visible) {
            return;
        }
        gerente.setMaxWidth(Double.MAX_VALUE);
        senha.setPromptText("Senha do gerente selecionado...");
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(4);
        g.add(label("Gerente / Supervisor *"), 0, 0);
        g.add(label("Senha do Gerente *"), 1, 0);
        g.add(gerente, 0, 1);
        g.add(senha, 1, 1);
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            g.getColumnConstraints().add(cc);
        }

        motivosLabel.setText("Motivos sugeridos para " + (mode == Mode.DEVOLVER ? "devolução" : "cancelamento") + ":");
        motivosLabel.getStyleClass().add("rg-mini-muted");
        motivos.getChildren().clear();
        for (String m : mode == Mode.DEVOLVER ? MOTIVOS_DEVOLUCAO : MOTIVOS_CANCELAMENTO) {
            Button chip = Fx.button(m, "rg-filter-chip", () -> justificativa.setText(m));
            chip.setFocusTraversable(false);
            motivos.getChildren().add(chip);
        }

        Label jl = label("Justificativa da Operação *");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox jHead = new HBox(jl, sp, contador);
        justificativa.setPrefRowCount(2);
        justificativa.setWrapText(true);
        justificativa.setPromptText(mode == Mode.DEVOLVER ? "Descreva o motivo da devolução fiscal perante a SEFAZ..."
                : "Descreva o motivo do cancelamento perante a SEFAZ...");

        authArea.getChildren().addAll(g, motivosLabel, motivos, jHead, justificativa);
        if (mode == Mode.DEVOLVER) {
            authArea.getChildren().add(buildClienteBox());
        }
        authArea.getStyleClass().add("rg-nfc-auth");
    }

    private Node buildClienteBox() {
        Button toggle = Fx.button("Identificação do Consumidor (Opcional)  ▾", "rg-btn-link", () -> { });
        clienteCpf.setPromptText("000.000.000-00 (opcional)");
        clienteNome.setPromptText("Nome Completo (opcional)");
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(2);
        g.add(small("CPF / CNPJ do Cliente"), 0, 0);
        g.add(small("Nome do Cliente"), 1, 0);
        g.add(clienteCpf, 0, 1);
        g.add(clienteNome, 1, 1);
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setPercentWidth(50);
            g.getColumnConstraints().add(cc);
        }
        Label note = small("* Se mantido em branco, a NF-e será emitida como Entrada Própria com os dados da loja, "
                + "amparada pela SEFAZ.");
        note.setWrapText(true);
        clienteBox.getChildren().setAll(g, note);
        show(clienteBox, clienteAberto);
        toggle.setOnAction(e -> {
            clienteAberto = !clienteAberto;
            show(clienteBox, clienteAberto);
            resize();
        });
        VBox box = new VBox(6, toggle, clienteBox);
        box.getStyleClass().add("rg-cash-card");
        return box;
    }

    private void switchMode(Mode m, String motivo) {
        mode = m;
        justificativa.setText(motivo);
        renderForm();
    }

    private void refreshConfirm() {
        boolean m55 = doc != null && doc.modelo55();
        boolean show = doc != null && doc.autorizado();
        show(confirmar, show);
        if (!show) {
            return;
        }
        int chars = justificativa.getText() == null ? 0 : justificativa.getText().trim().length();
        contador.setText(chars + " / 15 caracteres mínimos");
        contador.getStyleClass().setAll("label", chars >= 15 ? "rg-text-success" : "rg-nfc-count-warn");
        boolean ok = !busy && gerente.getValue() != null && !senha.getText().isBlank() && chars >= 15;
        confirmar.setDisable(!ok);
        confirmar.getStyleClass().removeAll("rg-btn-danger-fill", "rg-cash-btn-primary");
        confirmar.getStyleClass().add(mode == Mode.DEVOLVER ? "rg-cash-btn-primary" : "rg-btn-danger-fill");
        confirmar.setText(busy ? "Homologando na SEFAZ..." : mode == Mode.DEVOLVER
                ? "Autorizar e Emitir NF-e Devolução" : "Autorizar e Cancelar " + (m55 ? "NF-e" : "NFC-e"));
    }

    // ---------- ações ----------

    private void loadSupervisors() {
        gerente.setPromptText("Selecione o gerente...");
        gerente.valueProperty().addListener((o, a, b) -> refreshConfirm());
        senha.textProperty().addListener((o, a, b) -> refreshConfirm());
        justificativa.textProperty().addListener((o, a, b) -> refreshConfirm());
        Fx.async(ctx.priceAuthApi::supervisors, list -> {
            gerente.getItems().setAll(list);
            if (list.size() == 1) {
                gerente.setValue(list.get(0));
            }
        }, t -> Toasts.error(stage, "Não foi possível carregar a lista de gerentes."));
    }

    private void search() {
        String termo = codigo.getText() == null ? "" : codigo.getText().trim();
        if (termo.isEmpty()) {
            Toasts.error(stage, "Informe o código da venda, número da nota ou chave de acesso.");
            return;
        }
        buscar.setDisable(true);
        buscar.setText("Buscando...");
        searchError.setText("");
        doc = null;
        sefazRejeicaoPrazo = null;
        Fx.async(() -> ctx.fiscalApi.search(termo), found -> {
            buscar.setDisable(false);
            buscar.setText("Buscar");
            if (found == null) {
                searchError.setText("Nenhum documento fiscal encontrado com os dados informados.");
                renderForm();
                return;
            }
            doc = found;
            clienteCpf.setText(nz(found.customerCpf()).equals("-") ? "" : nz(found.customerCpf()));
            clienteNome.setText(nz(found.customerName()).equals("Consumidor Final") ? "" : nz(found.customerName()));
            // Passou do prazo: sugere a devolução automaticamente.
            if (expired()) {
                mode = Mode.DEVOLVER;
                justificativa.setText(MOTIVOS_DEVOLUCAO.get(1));
            } else {
                mode = Mode.CANCELAR;
                justificativa.setText(MOTIVOS_CANCELAMENTO.get(0));
            }
            renderForm();
            if (found.autorizado()) {
                Platform.runLater(senha::requestFocus);
            }
        }, t -> {
            buscar.setDisable(false);
            buscar.setText("Buscar");
            searchError.setText(t instanceof br.com.quack.caixa.api.ApiException ae && ae.isTransient()
                    ? "Sem conexão: o cancelamento de nota precisa falar com o servidor e a SEFAZ."
                    : Fx.message(t));
            renderForm();
        });
    }

    private void submit() {
        if (doc == null || confirmar.isDisabled()) {
            return;
        }
        String supId = gerente.getValue().id();
        String pwd = senha.getText();
        String just = justificativa.getText().trim();
        setBusy(true);
        sefazRejeicaoPrazo = null;
        if (mode == Mode.CANCELAR) {
            FiscalApi.FiscalDoc d = doc;
            Fx.async(() -> ctx.fiscalApi.cancelWithSupervisor(d, supId, pwd, just, ctx.config.deviceId()), res -> {
                setBusy(false);
                changed = true;
                FiscalApi.CancelResult r = res != null ? res
                        : new FiscalApi.CancelResult(d.id(), "Homologado", null, gerente.getValue().name());
                Toasts.success(stage.getOwner(), (d.modelo55() ? "NF-e" : "NFC-e")
                        + " cancelada e mercadorias estornadas ao estoque!");
                renderCancelSuccess(r, just);
            }, t -> {
                setBusy(false);
                String msg = Fx.message(t);
                if (isPrazoRejection(msg)) {
                    sefazRejeicaoPrazo = msg;
                    mode = Mode.DEVOLVER;
                    justificativa.setText(MOTIVOS_DEVOLUCAO.get(1));
                    renderForm();
                    Toasts.info(stage, "Prazo legal de cancelamento expirado na SEFAZ. Prossiga com a Devolução.");
                } else {
                    senha.clear();
                    Toasts.error(stage, msg);
                }
            });
        } else {
            FiscalApi.FiscalDoc d = doc;
            String cpf = clienteCpf.getText();
            String nome = clienteNome.getText();
            Fx.async(() -> ctx.fiscalApi.devolver(d, supId, pwd, just, cpf, nome), res -> {
                setBusy(false);
                if (res == null) {
                    Toasts.error(stage, "Falha ao emitir NF-e de Devolução.");
                    return;
                }
                changed = true;
                Toasts.success(stage.getOwner(), "NF-e de Devolução autorizada na SEFAZ e estoque estornado!");
                renderDevolucaoSuccess(res, just);
            }, t -> {
                setBusy(false);
                senha.clear();
                Toasts.error(stage, Fx.message(t));
            });
        }
    }

    /** Mesma detecção da web: mensagens de prazo vencido na SEFAZ levam para a devolução. */
    static boolean isPrazoRejection(String msg) {
        String m = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        return m.contains("prazo") || m.contains("30") || m.contains("24") || m.contains("502") || m.contains("218")
                || m.contains("220") || m.contains("superior ao previsto");
    }

    // ---------- sucesso ----------

    private void renderCancelSuccess(FiscalApi.CancelResult r, String just) {
        boolean m55 = doc.modelo55();
        VBox info = new VBox(4,
                kv("Venda:", "#" + nz(doc.saleNumber())),
                kv((m55 ? "NF-e" : "NFC-e") + " Nº:", ReceiptPrinter.formatNumeroNf(str(doc.numeroNf()))),
                kv("Protocolo SEFAZ:", nz(r.protocoloCancelamento()).isBlank() ? "Homologado" : r.protocoloCancelamento()),
                kv("Autorizado por:", nz(r.supervisorNome()).isBlank() ? "Gerente" : r.supervisorNome()));
        info.getStyleClass().add("rg-cash-card");
        Button concluir = Fx.button("Concluir e Voltar ao Caixa", "rg-btn-action", this::close);
        Button imprimir = Fx.button("Imprimir Comprovante", "rg-btn-action rg-cash-btn-primary",
                () -> print(ReceiptPrinter.buildCancelamento(doc, r, just)));
        imprimir.setGraphic(Fx.icon(Fx.ICON_PRINTER, "rg-icon-light"));
        success((m55 ? "NF-e" : "NFC-e") + " Cancelada com Sucesso!",
                "O evento de cancelamento foi homologado pela SEFAZ. O estoque dos produtos foi estornado e a venda "
                        + "marcada como cancelada.", info, concluir, imprimir);
    }

    private void renderDevolucaoSuccess(FiscalApi.DevolucaoResult r, String just) {
        boolean m55 = doc.modelo55();
        VBox info = new VBox(4,
                kv("NF-e Devolução:", "Nº " + ReceiptPrinter.formatNumeroNf(str(r.numeroNfe())) + " (Série "
                        + str(r.serieNfe()) + ")"),
                kv((m55 ? "NF-e" : "NFC-e") + " Referenciada:", ReceiptPrinter.formatNumeroNf(str(doc.numeroNf()))),
                kv("Protocolo SEFAZ:", dash(r.protocolo())),
                kv("Destinatário/Remetente:", dash(r.destinatarioNome())),
                kv("Autorizado por:", dash(r.supervisorNome())));
        info.getStyleClass().add("rg-cash-card");
        Button concluir = Fx.button("Concluir e Voltar ao Caixa", "rg-btn-action", this::close);
        Button termica = Fx.button("Comprovante Térmico (Assinatura)", "rg-btn-action rg-cash-btn-primary",
                () -> print(ReceiptPrinter.buildDevolucao(doc, r, just)));
        termica.setGraphic(Fx.icon(Fx.ICON_PRINTER, "rg-icon-light"));
        Button a4 = Fx.button("Imprimir DANFE A4", "rg-btn-action",
                () -> print(ReceiptPrinter.buildDevolucaoA4(doc, r, just)));
        success("NF-e de Devolução Homologada!",
                "A NF-e Modelo 55 de Entrada foi autorizada pela SEFAZ. O débito fiscal foi anulado e as mercadorias "
                        + "foram devolvidas ao estoque da loja.", info, concluir, termica, a4);
    }

    private void success(String title, String text, Node info, Button... buttons) {
        StackPane ok = new StackPane(Fx.icon(ICON_CHECK, "rg-icon-success"));
        ok.getStyleClass().add("rg-nfc-success-icon");
        ok.setScaleX(1.6);
        ok.setScaleY(1.6);
        Label t = new Label(title);
        t.getStyleClass().add("rg-modal-title");
        Label d = new Label(text);
        d.setWrapText(true);
        d.getStyleClass().add("rg-mini-muted");
        d.setMaxWidth(520);
        d.setStyle("-fx-text-alignment: center;");
        HBox actions = new HBox(8, buttons);
        actions.setAlignment(Pos.CENTER);
        VBox v = new VBox(12, ok, t, d, info, actions);
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

    private boolean expired() {
        long min = minutesElapsed();
        return doc != null && (doc.modelo55() ? min > 1440 : min > 30);
    }

    private long minutesElapsed() {
        if (doc == null) {
            return 0;
        }
        String ts = doc.dhAutorizacao() != null && !doc.dhAutorizacao().isBlank() ? doc.dhAutorizacao() : doc.criadoEm();
        Instant at = parse(ts);
        return at == null ? 0 : Math.max(0, Duration.between(at, Instant.now()).toMinutes());
    }

    private static Instant parse(String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(ts).toInstant();
        } catch (RuntimeException e) {
            try {
                return Instant.parse(ts);
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static String formatDate(String ts) {
        Instant at = parse(ts);
        return at == null ? "—" : java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .format(at.atZone(java.time.ZoneId.of("America/Sao_Paulo")));
    }

    private static String badgeClass(int status) {
        return switch (status) {
            case FiscalApi.AUTORIZADO -> "rg-badge-ok";
            case FiscalApi.CANCELADO, FiscalApi.REJEITADO, FiscalApi.DENEGADO -> "rg-badge-danger";
            case FiscalApi.DEVOLVIDO -> "rg-badge-purple";
            default -> "rg-badge-warn";
        };
    }

    private Node warn(String text, boolean danger) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add(danger ? "rg-nfc-error-box" : "rg-nfc-warn-box");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private static HBox kv(String k, String v) {
        Label a = new Label(k);
        a.getStyleClass().add("rg-mini-muted");
        Label b = new Label(v);
        b.getStyleClass().add("rg-cash-line-strong");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        return new HBox(8, a, sp, b);
    }

    private static Label label(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-pay-field-label");
        return l;
    }

    private static Label small(String t) {
        Label l = new Label(t);
        l.getStyleClass().add("rg-mini-muted");
        return l;
    }

    private void setBusy(boolean b) {
        busy = b;
        Fx.setBusy(stage, b);
        codigo.setDisable(b);
        buscar.setDisable(b);
        authArea.setDisable(b);
        refreshConfirm();
    }

    private void close() {
        if (!busy) {
            stage.close();
        }
    }

    private void resize() {
        if (stage != null && stage.isShowing()) {
            Platform.runLater(stage::sizeToScene);
        }
    }

    private static void show(Node n, boolean v) {
        n.setVisible(v);
        n.setManaged(v);
    }

    private static String str(Integer v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String dash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
