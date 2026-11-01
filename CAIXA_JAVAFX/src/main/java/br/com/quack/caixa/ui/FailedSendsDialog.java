package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.dto.CashEvent;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.persistence.OutboxStore;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * "Falhas no envio": vendas e operações de caixa feitas offline que a API recusou de vez. Mostra o que é, quando
 * foi feito e o motivo da recusa; deixa reenviar (volta para a fila na mesma posição — a API não duplica, o
 * eventId é o mesmo), copiar os detalhes para o suporte ou descartar (fica guardado no banco local).
 */
public final class FailedSendsDialog {
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final AppContext ctx;
    private final VBox root = new VBox(12);
    private final VBox list = new VBox(8);
    private final Label subtitle = new Label();
    private Stage stage;

    private FailedSendsDialog(AppContext ctx) {
        this.ctx = ctx;
    }

    public static void show(Window owner, AppContext ctx) {
        FailedSendsDialog d = new FailedSendsDialog(ctx);
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.load();
        d.stage.showAndWait();
    }

    private void build() {
        root.setPrefSize(640, 520);
        subtitle.getStyleClass().add("rg-mini-muted");
        subtitle.setWrapText(true);
        root.getChildren().add(Fx.modalHeader("Falhas no envio", subtitle, () -> stage.close()));

        Label help = new Label("Estes itens foram feitos sem internet e a nuvem recusou. Leia o motivo, corrija a causa "
                + "(no sistema web, se for cadastro ou usuário) e clique em \"Reenviar\". Reenviar não duplica a venda.");
        help.setWrapText(true);
        help.getStyleClass().add("rg-info-text");

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("rg-sales-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Button retryAll = Fx.button("Reenviar todos", "rg-btn-primary", this::retryAll);
        Button close = Fx.button("Fechar", "rg-btn-icon-flat", () -> stage.close());
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox footer = new HBox(8, sp, close, retryAll);
        footer.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(help, scroll, footer);
    }

    private void load() {
        list.getChildren().clear();
        List<OutboxStore.Failed> failed = ctx.outbox.listFailed();
        int pending = ctx.outbox.pendingCount();
        subtitle.setText(failed.size() + (failed.size() == 1 ? " item recusado" : " itens recusados")
                + (pending > 0 ? " • " + pending + " aguardando envio" : ""));
        if (failed.isEmpty()) {
            Label ok = new Label("Nenhuma falha. Tudo que foi feito offline já foi enviado ou está na fila.");
            ok.setWrapText(true);
            ok.getStyleClass().add("rg-ok");
            list.getChildren().add(ok);
            return;
        }
        for (OutboxStore.Failed f : failed) {
            list.getChildren().add(card(f));
        }
    }

    private VBox card(OutboxStore.Failed f) {
        Label what = new Label(titulo(f));
        what.getStyleClass().add("rg-info-strong");
        what.setWrapText(true);
        Label when = new Label(quando(f));
        when.getStyleClass().add("rg-mini-muted");
        Label error = new Label("Motivo: " + (f.error().isBlank() ? "recusado pela nuvem (sem detalhe)." : f.error()));
        error.getStyleClass().add("rg-error");
        error.setWrapText(true);

        Button retry = Fx.button("Reenviar", "rg-btn-primary", () -> retry(f));
        Button copy = Fx.button("Copiar detalhes", "rg-btn-link", () -> copy(f));
        Button discard = Fx.button("Descartar", "rg-btn-outline-danger", () -> discard(f));
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox actions = new HBox(8, copy, sp, discard, retry);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(4, what, when, error);
        if (f.payload() != null && f.payload().items() != null) {
            StringBuilder sb = new StringBuilder();
            for (VendaPayload.Item it : f.payload().items()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(br.com.quack.caixa.domain.Money.formatQty(it.quantity())).append(" × ")
                        .append(it.productName() == null ? it.productCode() : it.productName())
                        .append("  R$ ").append(br.com.quack.caixa.domain.Money.formatBr(it.itemTotal()));
            }
            Label items = new Label(sb.toString());
            items.getStyleClass().add("rg-mini-muted");
            items.setWrapText(true);
            box.getChildren().add(items);
        }
        box.getChildren().add(actions);
        box.getStyleClass().add("rg-sale-row");
        return box;
    }

    static String titulo(OutboxStore.Failed f) {
        if (f.payload() != null) {
            VendaPayload p = f.payload();
            String ref = p.offlineReference() == null ? "" : " " + p.offlineReference();
            return "Venda" + ref + " — R$ " + (p.totalAmount() == null ? "?" : p.totalAmount())
                    + (p.paymentType() == null ? "" : " (" + p.paymentType() + ")");
        }
        CashEvent c = f.cash();
        if (c != null) {
            return switch (c.eventType() == null ? "" : c.eventType()) {
                case CashEvent.OPEN -> "Abertura de caixa — fundo R$ " + nz(c.openingAmount());
                case CashEvent.CLOSE -> "Fechamento de caixa — contado R$ " + nz(c.closingAmount());
                case CashEvent.MOVEMENT -> ("sangria".equalsIgnoreCase(c.tipo()) ? "Sangria" : "Reforço") + " — R$ "
                        + nz(c.valor());
                default -> "Operação de caixa " + c.eventType();
            };
        }
        return "Item ilegível (" + f.fileName() + ")";
    }

    private static String quando(OutboxStore.Failed f) {
        String feito = f.payload() != null ? f.payload().occurredAt() : f.cash() != null ? f.cash().occurredAt() : null;
        return "Feito em " + data(feito) + " • recusado em " + data(f.failedAt());
    }

    private static String data(String iso) {
        if (iso == null || iso.isBlank()) {
            return "?";
        }
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DATA_HORA);
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String nz(String v) {
        return v == null || v.isBlank() ? "0,00" : v;
    }

    private void retry(OutboxStore.Failed f) {
        ctx.outbox.retryFailed(f.fileName());
        afterChange("Reenviando: " + titulo(f) + ". Se for recusado de novo, volta para esta lista com o motivo.");
    }

    private void retryAll() {
        List<OutboxStore.Failed> failed = ctx.outbox.listFailed();
        if (failed.isEmpty()) {
            return;
        }
        failed.forEach(f -> ctx.outbox.retryFailed(f.fileName()));
        afterChange(failed.size() + (failed.size() == 1 ? " item voltou" : " itens voltaram") + " para a fila de envio.");
    }

    private void discard(OutboxStore.Failed f) {
        boolean sale = f.payload() != null;
        if (!Fx.confirm(stage, "Descartar",
                "Descartar \"" + titulo(f) + "\"?\n\n" + (sale
                        ? "A venda NÃO vai para a nuvem: não entra no faturamento, no caixa nem baixa o estoque. "
                                + "Use só se ela já foi lançada de outro jeito."
                        : "A operação de caixa NÃO vai para a nuvem.")
                        + "\nO registro continua guardado neste computador.")) {
            return;
        }
        String who = ctx.session.user() == null ? null : ctx.session.user().name();
        ctx.outbox.discardFailed(f.fileName(), who);
        ctx.sync.notifyCounts();
        load();
        Toasts.info(stage, "Item descartado.");
    }

    private void copy(OutboxStore.Failed f) {
        String body = titulo(f) + "\n" + quando(f) + "\nMotivo: " + f.error() + "\nId: " + f.fileName();
        ClipboardContent cc = new ClipboardContent();
        cc.putString(body);
        Clipboard.getSystemClipboard().setContent(cc);
        Toasts.success(stage, "Detalhes copiados.");
    }

    private void afterChange(String message) {
        ctx.sync.notifyCounts();
        ctx.sync.triggerNow();
        load();
        Toasts.info(stage, message);
        // o envio roda em segundo plano: atualiza a lista quando o ciclo terminar
        javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(4));
        later.setOnFinished(e -> {
            if (stage.isShowing()) {
                load();
            }
        });
        later.play();
    }
}
