package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.service.ReceiptPrinter;
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

/**
 * "Fechamento de Caixa (Cupom 80mm)" (porta de CashClosingSummaryModal.tsx): prévia do cupom de fechamento com
 * sangrias e reforços detalhados, "Sair do sistema" para o próximo operador e reimpressão.
 */
public final class CashClosingSummaryDialog {
    private static final String ICON_LOGOUT = "M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5v-2H5V5h5V3zm6 4-1.4 1.4 2.6 2.6H9v2h8.2l-2.6 2.6L16 17l5-5-5-5z";

    private CashClosingSummaryDialog() {
    }

    /**
     * Imprime o cupom de fechamento em segundo plano (não trava a tela) e avisa o resultado em "where".
     * prefixoErro: começo da mensagem quando a impressão falha.
     */
    public static void print(AppContext ctx, CaixaStatusDto.Session session, Window where, boolean avisarSucesso,
            String prefixoErro) {
        ReceiptPrinter.printAsync(ReceiptPrinter.buildCashClosing(session, ctx.catalog.company()), err -> {
            if (where == null || !where.isShowing()) {
                return;
            }
            if (err == null) {
                if (avisarSucesso) {
                    Toasts.success(where, "Fechamento enviado para a impressora.");
                }
            } else {
                Toasts.error(where, prefixoErro + err + ". Confira a impressora e use \"Imprimir Fechamento\".");
            }
        });
    }

    /** onLogout != null mostra "Sair do sistema" (fim de turno na frente de caixa). */
    public static void show(Window owner, AppContext ctx, CaixaStatusDto.Session session, Runnable onLogout) {
        Stage[] st = new Stage[1];
        boolean[] logout = {false};

        Label title = new Label("Fechamento de Caixa (Cupom 80mm)");
        title.getStyleClass().add("rg-modal-title");
        String id = session.id() == null ? "" : session.id();
        Label sub = new Label("Turno #" + id.substring(0, Math.min(8, id.length())).toUpperCase() + " · "
                + ReceiptPrinter.formatDate(session.closedAt() != null ? session.closedAt() : session.openedAt()));
        sub.getStyleClass().add("rg-mini-muted");
        HBox titleBox = new HBox(10, iconBox(Fx.icon(Fx.ICON_RECEIPT_SMALL, "rg-icon-brand")), new VBox(1, title, sub));
        titleBox.setAlignment(Pos.CENTER_LEFT);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button close = Fx.button("✕", "rg-btn-icon", () -> st[0].close());
        close.setFocusTraversable(false);
        HBox header = new HBox(8, titleBox, sp, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("rg-preview-header");

        Node paper = ReceiptPrinter.buildCashClosing(session, ctx.catalog.company());
        paper.getStyleClass().add("rg-receipt-paper");
        StackPane area = new StackPane(paper);
        StackPane.setAlignment(paper, Pos.TOP_CENTER);
        area.getStyleClass().add("rg-preview-area");
        ScrollPane scroll = new ScrollPane(area);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(470);
        scroll.getStyleClass().add("rg-preview-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox root = new VBox(0, header, scroll);

        // Fim de turno: sair libera o caixa para o próximo operador entrar com o login dele.
        if (onLogout != null) {
            Label msg = new Label("Terminou o turno? Saia do sistema para o próximo operador entrar com o login dele.");
            msg.setWrapText(true);
            msg.getStyleClass().add("rg-auth-sub");
            HBox.setHgrow(msg, Priority.ALWAYS);
            Button sair = Fx.button("Sair do sistema", "rg-btn-success rg-btn-action", () -> {
                logout[0] = true;
                st[0].close();
            });
            sair.setGraphic(Fx.icon(ICON_LOGOUT, "rg-icon-light"));
            sair.setMinWidth(Region.USE_PREF_SIZE);
            HBox band = new HBox(12, msg, sair);
            band.setAlignment(Pos.CENTER_LEFT);
            band.getStyleClass().add("rg-closing-band");
            root.getChildren().add(band);
        }

        Button cont = Fx.button(onLogout != null ? "Continuar no sistema" : "Fechar", "rg-btn-action",
                () -> st[0].close());
        Button imprimir = Fx.button("Imprimir Fechamento (80mm)", "rg-btn-action rg-cash-btn-primary", () -> {
            print(ctx, session, st[0], true, "Não foi possível imprimir: ");
        });
        imprimir.setGraphic(Fx.icon(Fx.ICON_PRINTER, "rg-icon-light"));
        HBox footer = new HBox(8, cont, imprimir);
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.getStyleClass().add("rg-preview-footer");
        root.getChildren().add(footer);

        root.getStyleClass().add("rg-preview-modal");
        root.setPrefWidth(520);
        st[0] = Fx.card(owner, root);
        st[0].showAndWait();
        if (logout[0]) {
            onLogout.run();
        }
    }

    private static Node iconBox(Node icon) {
        StackPane b = new StackPane(icon);
        b.getStyleClass().add("rg-closing-icon");
        return b;
    }
}
