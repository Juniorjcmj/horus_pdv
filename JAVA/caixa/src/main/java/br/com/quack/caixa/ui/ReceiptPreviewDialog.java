package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.FiscalApi;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.service.ReceiptPrinter;
import java.util.function.Supplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * "Comprovante da venda N" (porta de ReceiptPreviewModal.tsx): DANFE NFC-e quando a venda tem nota,
 * cupom não fiscal quando não tem; enquanto a nota não fica pronta, consulta de novo a cada 1,5 s.
 */
public final class ReceiptPreviewDialog {
    private static final int MAX_ATTEMPTS = 20;

    private final AppContext ctx;
    private final SaleOutcome sale;
    private final StackPane paperArea = new StackPane();
    private final Button imprimir = Fx.button("Imprimir", "rg-btn-action rg-cash-btn-primary", () -> { });
    private Stage stage;
    private FiscalApi.FiscalDoc fiscal;
    private boolean requiresFiscal;
    private boolean forceCupom;
    private boolean printed;
    private boolean loading;
    private Supplier<Node> printable;
    private Timeline poll;
    private int attempts;

    private ReceiptPreviewDialog(AppContext ctx, SaleOutcome sale) {
        this.ctx = ctx;
        this.sale = sale;
        // Venda online com nota: o DANFE é o comprovante. Offline: cupom até a venda sincronizar.
        this.requiresFiscal = sale.emitirFiscal() && !sale.offline();
    }

    /** Devolve true se o comprovante foi enviado para a impressora. */
    public static boolean show(Window owner, AppContext ctx, SaleOutcome sale) {
        ReceiptPreviewDialog d = new ReceiptPreviewDialog(ctx, sale);
        VBox root = d.build();
        d.stage = Fx.card(owner, root);
        d.render();
        if (!sale.offline()) {
            d.loadDocument();
        }
        d.stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> d.stopPolling());
        d.stage.showAndWait();
        return d.printed;
    }

    private VBox build() {
        Label title = new Label("Comprovante da venda " + sale.saleNumber());
        title.getStyleClass().add("rg-modal-title");
        HBox titleBox = new HBox(8, Fx.icon(Fx.ICON_RECEIPT_SMALL, "rg-icon-brand"), title);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = Fx.button("✕", "rg-btn-icon-flat", () -> stage.close());
        close.setFocusTraversable(false);
        HBox header = new HBox(8, titleBox, sp, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("rg-preview-header");

        paperArea.getStyleClass().add("rg-preview-area");
        ScrollPane scroll = new ScrollPane(paperArea);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520);
        scroll.getStyleClass().add("rg-preview-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button fechar = Fx.button("Fechar", "rg-btn-action", () -> stage.close());
        imprimir.setGraphic(Fx.icon(Fx.ICON_PRINTER, "rg-icon-light"));
        imprimir.setDefaultButton(true);
        imprimir.setOnAction(e -> print());
        HBox footer = new HBox(8, fechar, imprimir);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("rg-preview-footer");

        VBox root = new VBox(0, header, scroll, footer);
        root.getStyleClass().add("rg-preview-modal");
        root.setPrefWidth(470);
        return root;
    }

    // ---------- documento fiscal ----------

    private void loadDocument() {
        if (loading) {
            return;
        }
        loading = true;
        render();
        Fx.async(() -> ctx.fiscalApi.bySaleNumber(sale.saleNumber()), doc -> {
            loading = false;
            if (doc != null) {
                fiscal = doc;
                requiresFiscal = true;
            }
            render();
            schedulePolling();
        }, t -> {
            loading = false;
            render();
            schedulePolling();
        });
    }

    /** Igual à web: enquanto precisa da nota e o XML não chegou, tenta de novo (até 20 vezes). */
    private void schedulePolling() {
        boolean waiting = requiresFiscal && (fiscal == null || !fiscal.hasXml());
        // attempts vira MAX_ATTEMPTS quando o modal fecha (stopPolling): nada é agendado depois disso.
        if (!waiting || attempts >= MAX_ATTEMPTS || poll != null) {
            return;
        }
        poll = new Timeline(new KeyFrame(Duration.millis(1500), e -> {
            poll = null;
            attempts++;
            loadDocument();
        }));
        poll.play();
    }

    private void stopPolling() {
        if (poll != null) {
            poll.stop();
            poll = null;
        }
        attempts = MAX_ATTEMPTS;
    }

    // ---------- desenho ----------

    private void render() {
        Node content;
        if (!forceCupom && fiscal != null) {
            try {
                FiscalApi.FiscalDoc doc = fiscal;
                content = ReceiptPrinter.buildDanfeFor(sale, ctx.catalog.company(), doc);
                printable = () -> ReceiptPrinter.buildDanfeFor(sale, ctx.catalog.company(), doc);
            } catch (RuntimeException e) {
                content = waiting(Fx.message(e));
                printable = null;
            }
        } else if (!forceCupom && requiresFiscal) {
            content = waiting(loading ? "Buscando a nota fiscal da venda..."
                    : "O comprovante está sendo preparado. Ele será impresso automaticamente quando estiver pronto.");
            printable = null;
        } else {
            FiscalApi.FiscalDoc doc = fiscal;
            content = ReceiptPrinter.buildCupom(sale, ctx.catalog.company(), doc);
            printable = () -> ReceiptPrinter.buildCupom(sale, ctx.catalog.company(), doc);
        }
        if (printable != null) {
            content.getStyleClass().add("rg-receipt-paper");
            StackPane.setAlignment(content, Pos.TOP_CENTER);
        }
        paperArea.getChildren().setAll(content);
        imprimir.setDisable(printable == null);
        if (stage != null && stage.isShowing()) {
            stage.sizeToScene();
        }
    }

    /** Aviso enquanto a NFC-e não está pronta, com "Atualizar comprovante" (e o cupom gerencial como saída). */
    private Node waiting(String message) {
        Label msg = new Label(message);
        msg.setWrapText(true);
        msg.getStyleClass().add("rg-preview-status");
        msg.setMaxWidth(360);
        Button refresh = Fx.button(loading ? "Atualizando..." : "Atualizar comprovante", "rg-btn-small", () -> {
            attempts = 0;
            loadDocument();
        });
        refresh.setGraphic(Fx.icon(Fx.ICON_REFRESH, "rg-icon-muted"));
        refresh.setDisable(loading);
        Button cupom = Fx.button("Mostrar cupom não fiscal", "rg-btn-link", () -> {
            forceCupom = true;
            render();
        });
        VBox v = new VBox(12, msg, refresh, cupom);
        v.setAlignment(Pos.CENTER);
        v.setMinHeight(220);
        v.setPrefWidth(400);
        return v;
    }

    // ---------- impressão ----------

    private void print() {
        if (printable == null) {
            return;
        }
        imprimir.setDisable(true);
        imprimir.setText("Imprimindo...");
        Node copy;
        try {
            // Imprime uma cópia nova: o nó da tela já está preso a esta cena.
            copy = printable.get();
        } catch (RuntimeException ex) {
            printFailed(Fx.message(ex));
            return;
        }
        // Em segundo plano: a tela não congela se o driver da impressora demorar.
        ReceiptPrinter.printAsync(copy, err -> {
            if (err == null) {
                printed = true;
                stage.close();
            } else {
                printFailed(err);
            }
        });
    }

    private void printFailed(String message) {
        if (stage.isShowing()) {
            Toasts.error(stage, message + ". Confira a impressora e tente novamente.");
        }
        imprimir.setDisable(false);
        imprimir.setText("Imprimir");
    }
}
