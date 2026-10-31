package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.ApiClient;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.PaymentType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Vendas do turno de caixa atual (F9), como o modal "Vendas do Caixa Atual" da web. */
public final class SessionSalesDialog {
    private static final String ICON_RECEIPT = "M5 2h14v20l-2.33-1.5L14.33 22 12 20.5 9.67 22 7.33 20.5 5 22V2zm2 2v14.3l.33-.21L9.67 19.6 12 18.1l2.33 1.5 2.34-1.5.33.21V4H7zm4 2h2v1.1c1.2.3 2 1.1 2 2.2h-2c0-.4-.4-.6-1-.6s-1 .3-1 .6c0 .4.3.5 1.3.8 1.4.4 2.7.9 2.7 2.5 0 1.1-.8 1.9-2 2.2V16h-2v-1.2c-1.2-.3-2-1.1-2-2.3h2c0 .4.4.7 1 .7s1-.3 1-.7c0-.4-.4-.6-1.4-.9C9.3 11.2 9 10.3 9 9.4c0-1.1.8-1.9 2-2.2V6z";
    private static final String ICON_BAG = "M6 7V6a6 6 0 0 1 12 0v1h3v15H3V7h3zm2 0h8V6a4 4 0 0 0-8 0v1zM5 9v11h14V9H5z";
    private static final String ICON_SEARCH = "M9.5 3a6.5 6.5 0 0 1 5.17 10.44l4.45 4.45-1.41 1.41-4.45-4.45A6.5 6.5 0 1 1 9.5 3zm0 2a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z";

    /** Uma venda agrupada (a API devolve uma linha por item). */
    record Sale(String number, String time, String customer, String cpf, String status, boolean pending,
            BigDecimal total, List<String[]> payments, List<String[]> items) {
        /** Inativa (isCancelledStatus da web): cancelada, estornada ou corrigida (substituída por outra venda). */
        boolean cancelled() {
            String st = status == null ? "" : status.toLowerCase(Locale.ROOT);
            return st.startsWith("cancel") || st.startsWith("estorn") || st.equals("corrigida");
        }

        boolean corrected() {
            return "corrigida".equalsIgnoreCase(status);
        }

        boolean hasPayment(String... wires) {
            for (String[] p : payments) {
                for (String w : wires) {
                    if (p[0].equalsIgnoreCase(w)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private final AppContext ctx;
    private final VBox root = new VBox(0);
    private final Label badge = new Label("0 vendas");
    private final Label subtitle = new Label();
    private final Label countVal = new Label("0 concluídas");
    private final Label totalVal = new Label("R$ 0,00");
    private final TextField search = new TextField();
    private final ToggleGroup filter = new ToggleGroup();
    private final VBox list = new VBox(6);
    private final Set<String> expanded = new HashSet<>();
    private List<Sale> sales = new ArrayList<>();
    private Stage stage;

    private SessionSalesDialog(AppContext ctx) {
        this.ctx = ctx;
    }

    public static void show(Window owner, AppContext ctx) {
        SessionSalesDialog d = new SessionSalesDialog(ctx);
        d.build();
        d.stage = Fx.card(owner, d.root);
        d.load();
        d.stage.showAndWait();
    }

    // ---------- layout ----------

    private void build() {
        root.setPrefSize(660, 560);
        root.getStyleClass().add("rg-sales-modal");

        StackPane iconBox = new StackPane(icon(ICON_RECEIPT, "rg-icon-brand"));
        iconBox.getStyleClass().add("rg-sales-icon");
        Label title = new Label("Vendas do Caixa Atual");
        title.getStyleClass().add("rg-modal-title");
        badge.getStyleClass().add("rg-badge");
        HBox titleRow = new HBox(8, title, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        subtitle.getStyleClass().add("rg-mini-muted");
        VBox texts = new VBox(2, titleRow, subtitle);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button refresh = Fx.button("", "rg-btn-icon", this::load);
        refresh.setGraphic(Fx.icon(Fx.ICON_REFRESH, "rg-icon-muted"));
        Button close = Fx.button("✕", "rg-btn-icon-flat", () -> stage.close());
        refresh.setFocusTraversable(false);
        close.setFocusTraversable(false);
        HBox header = new HBox(12, iconBox, texts, sp, refresh, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("rg-sales-header");

        HBox stats = new HBox(10, stat(ICON_BAG, "rg-sales-stat-icon", "Total de Vendas", countVal),
                stat(null, "rg-sales-stat-icon-money", "Total Faturado", totalVal));
        totalVal.getStyleClass().add("rg-sales-money");
        stats.getStyleClass().add("rg-sales-section");

        search.setPromptText("Buscar por nº da venda, cliente ou CPF...");
        search.getStyleClass().add("rg-search-field");
        SVGPath lupa = icon(ICON_SEARCH, "rg-icon-muted");
        lupa.setMouseTransparent(true);
        StackPane searchBox = new StackPane(search, lupa);
        StackPane.setAlignment(lupa, Pos.CENTER_LEFT);
        StackPane.setMargin(lupa, new javafx.geometry.Insets(0, 0, 0, 12));
        HBox.setHgrow(searchBox, Priority.ALWAYS);
        HBox filters = new HBox(4);
        for (String f : new String[] {"Todos", "Dinheiro", "Cartão", "PIX", "Fiado"}) {
            ToggleButton tb = new ToggleButton(f);
            tb.setUserData(f);
            tb.setToggleGroup(filter);
            tb.getStyleClass().add("rg-filter-chip");
            tb.setFocusTraversable(false);
            filters.getChildren().add(tb);
        }
        ((ToggleButton) filters.getChildren().get(0)).setSelected(true);
        filter.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null && a != null) {
                a.setSelected(true);
            } else {
                renderList();
            }
        });
        search.textProperty().addListener((o, a, b) -> renderList());
        filters.setAlignment(Pos.CENTER_RIGHT);
        HBox searchRow = new HBox(10, searchBox, filters);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.getStyleClass().add("rg-sales-section");

        ScrollPane scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("rg-sales-scroll");
        list.getStyleClass().add("rg-sales-list");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label hint = new Label("Clique em qualquer venda para ver os itens e pagamentos.");
        hint.getStyleClass().add("rg-mini-muted");
        Region fsp = new Region();
        HBox.setHgrow(fsp, Priority.ALWAYS);
        Button fechar = Fx.button("Fechar (Esc)", "rg-btn-small", () -> stage.close());
        HBox footer = new HBox(hint, fsp, fechar);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("rg-sales-footer");

        root.getChildren().addAll(header, stats, searchRow, scroll, footer);
    }

    private static HBox stat(String iconPath, String iconCls, String title, Label value) {
        Node ic = iconPath == null ? new Label("$") : icon(iconPath, "rg-icon-muted");
        StackPane box = new StackPane(ic);
        box.getStyleClass().add(iconCls);
        if (ic instanceof Label l) {
            l.getStyleClass().add("rg-sales-dollar");
        }
        Label t = new Label(title);
        t.getStyleClass().add("rg-mini-muted");
        value.getStyleClass().add("rg-sales-stat-value");
        HBox h = new HBox(10, box, new VBox(0, t, value));
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("rg-sales-stat");
        HBox.setHgrow(h, Priority.ALWAYS);
        h.setMaxWidth(Double.MAX_VALUE);
        return h;
    }

    private static SVGPath icon(String path, String cls) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add(cls);
        return p;
    }

    // ---------- dados ----------

    private void load() {
        CaixaStatusDto s = ctx.session.caixaStatus();
        CaixaStatusDto.Session cur = s == null ? null : s.currentSession();
        if (cur == null || !s.open()) {
            subtitle.setText("Nenhum turno de caixa aberto.");
            sales = pendingSales();
            renderAll();
            return;
        }
        subtitle.setText("🕒 Turno aberto em: " + hhmm(cur.openedAt()) + "   •   Operador: " + nz(cur.operatorName()));
        list.getChildren().setAll(emptyState("Carregando vendas...", ""));
        String url = "/api/HistoricoVendas?caixaSessaoId=" + URLEncoder.encode(cur.id(), StandardCharsets.UTF_8);
        Fx.async(() -> ctx.api.send("GET", url, null, ApiClient.LIST_TIMEOUT), data -> {
            List<Sale> all = new ArrayList<>(pendingSales());
            all.addAll(group(data));
            sales = all;
            renderAll();
        }, t -> {
            sales = pendingSales();
            renderAll();
            Toasts.error(stage, "Não foi possível buscar as vendas do turno: " + Fx.message(t));
        });
    }

    /** Vendas feitas aqui que ainda estão na fila offline. */
    private List<Sale> pendingSales() {
        List<Sale> out = new ArrayList<>();
        for (VendaPayload p : ctx.outbox.pendingSales()) {
            List<String[]> pays = new ArrayList<>();
            for (VendaPayload.Payment pay : p.payments()) {
                pays.add(new String[] {pay.paymentType(), Money.formatBr(pay.amount())});
            }
            List<String[]> items = new ArrayList<>();
            for (VendaPayload.Item it : p.items()) {
                items.add(new String[] {it.productName(), Money.formatQty(it.quantity()),
                        Money.formatBr(it.unitPrice()), Money.formatBr(it.itemTotal())});
            }
            out.add(new Sale(p.offlineReference(), hhmm(p.occurredAt()), nz(p.customerName()), nz(p.customerCpf()),
                    "pendente", true, parse(p.totalAmount()), pays, items));
        }
        return out;
    }

    private static List<Sale> group(JsonNode rows) {
        Map<String, List<JsonNode>> byNumber = new LinkedHashMap<>();
        if (rows != null) {
            for (JsonNode r : rows) {
                byNumber.computeIfAbsent(r.path("saleNumber").asText(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Sale> out = new ArrayList<>();
        for (var e : byNumber.entrySet()) {
            JsonNode first = e.getValue().get(0);
            List<String[]> pays = new ArrayList<>();
            String breakdown = first.path("paymentBreakdown").asText("");
            if (!breakdown.isBlank()) {
                for (String part : breakdown.split(";")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2) {
                        pays.add(new String[] {kv[0].trim(), Money.formatBr(parse(kv[1].trim()))});
                    }
                }
            } else {
                pays.add(new String[] {first.path("paymentType").asText(""), first.path("totalAmount").asText("")});
            }
            List<String[]> items = new ArrayList<>();
            for (JsonNode r : e.getValue()) {
                items.add(new String[] {r.path("productName").asText(), Money.formatQty(r.path("quantity").decimalValue()),
                        r.path("unitPrice").asText(), r.path("itemTotal").asText()});
            }
            String date = first.path("saleDate").asText("");
            String time = date.length() >= 16 ? date.substring(11, 16) : date;
            out.add(new Sale(e.getKey(), time, first.path("customerName").asText(""),
                    first.path("customerCpf").asText(""), first.path("status").asText("finalizada"), false,
                    parse(first.path("totalAmount").asText("0")), pays, items));
        }
        return out;
    }

    // ---------- desenho ----------

    private void renderAll() {
        int done = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Sale s : sales) {
            if (!s.cancelled()) {
                done++;
                total = total.add(s.total());
            }
        }
        badge.setText(sales.size() + (sales.size() == 1 ? " venda" : " vendas"));
        countVal.setText(done + (done == 1 ? " concluída" : " concluídas"));
        totalVal.setText("R$ " + Money.formatBr(total));
        renderList();
    }

    private void renderList() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String f = filter.getSelectedToggle() == null ? "Todos" : (String) filter.getSelectedToggle().getUserData();
        String digits = q.replaceAll("\\D", "");
        List<Node> rows = new ArrayList<>();
        for (Sale s : sales) {
            boolean match = q.isEmpty() || s.number().toLowerCase(Locale.ROOT).contains(q)
                    || s.customer().toLowerCase(Locale.ROOT).contains(q)
                    || (!digits.isEmpty() && s.cpf().replaceAll("\\D", "").contains(digits));
            if (!match) {
                continue;
            }
            boolean ok = switch (f) {
                case "Dinheiro" -> s.hasPayment("dinheiro");
                case "Cartão" -> s.hasPayment("debito", "credito");
                case "PIX" -> s.hasPayment("pix");
                case "Fiado" -> s.hasPayment("fiado");
                default -> true;
            };
            if (ok) {
                rows.add(saleRow(s));
            }
        }
        if (rows.isEmpty()) {
            list.getChildren().setAll(emptyState("Nenhuma venda encontrada.",
                    "As vendas concluídas neste caixa aparecerão aqui."));
        } else {
            list.getChildren().setAll(rows);
        }
    }

    private VBox saleRow(Sale s) {
        Label num = new Label("Venda #" + s.number());
        num.getStyleClass().add("rg-sale-number");
        Label meta = new Label(s.time() + "   •   " + (s.customer().isBlank() ? "Consumidor" : s.customer()));
        meta.getStyleClass().add("rg-mini-muted");
        StringBuilder pays = new StringBuilder();
        for (String[] p : s.payments()) {
            if (pays.length() > 0) {
                pays.append(" + ");
            }
            pays.append(label(p[0]));
        }
        Label payLbl = new Label(pays.toString());
        payLbl.getStyleClass().add("rg-mini-muted");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Label total = new Label("R$ " + Money.formatBr(s.total()));
        total.getStyleClass().add("rg-sale-total");
        Label st = new Label(s.pending() ? "Pendente de envio" : s.corrected() ? "Corrigida"
                : s.cancelled() ? "Cancelada" : "Concluída");
        st.getStyleClass().addAll("rg-badge", s.pending() || s.corrected() ? "rg-badge-warn"
                : s.cancelled() ? "rg-badge-danger" : "rg-badge-ok");
        if (s.cancelled()) {
            num.getStyleClass().add("rg-corr-removed");
            total.getStyleClass().add("rg-corr-removed");
        }
        VBox right = new VBox(2, total, st);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox head = new HBox(10, new VBox(2, num, meta, payLbl), sp, right);
        head.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(8, head);
        card.getStyleClass().add("rg-sale-row");
        if (expanded.contains(s.number())) {
            VBox detail = new VBox(3);
            detail.getStyleClass().add("rg-sale-detail");
            Label it = new Label("Itens");
            it.getStyleClass().add("rg-mini-label");
            detail.getChildren().add(it);
            for (String[] i : s.items()) {
                detail.getChildren().add(line(i[0] + "   " + i[1] + " x " + i[2], "R$ " + i[3]));
            }
            Label pg = new Label("Pagamentos");
            pg.getStyleClass().add("rg-mini-label");
            detail.getChildren().add(pg);
            for (String[] p : s.payments()) {
                detail.getChildren().add(line(label(p[0]), "R$ " + p[1]));
            }
            if (!s.cpf().isBlank() && !"-".equals(s.cpf())) {
                detail.getChildren().add(line("CPF/CNPJ", s.cpf()));
            }
            // Atalho: cancelar a nota desta venda (abre o modal do F7 já com a venda buscada).
            if (!s.pending() && !s.cancelled()) {
                Button cancelar = Fx.button("Cancelar NFC-e desta venda", "rg-btn-small rg-btn-outline-danger", () -> {
                    if (NfceCancelDialog.show(stage, ctx, s.number())) {
                        load();
                    }
                });
                cancelar.setFocusTraversable(false);
                // Correção (igual à web): remove produtos e gera nova venda; exige perfil Financeiro.
                Button corrigir = Fx.button("Corrigir venda (remover itens)", "rg-btn-small", () -> {
                    if (SaleCorrectionDialog.show(stage, ctx, s.number())) {
                        load();
                    }
                });
                corrigir.setFocusTraversable(false);
                HBox actions = new HBox(8, corrigir, cancelar);
                actions.setAlignment(Pos.CENTER_RIGHT);
                detail.getChildren().add(actions);
            }
            card.getChildren().add(detail);
        }
        card.setOnMouseClicked(e -> {
            if (!expanded.remove(s.number())) {
                expanded.add(s.number());
            }
            renderList();
        });
        return card;
    }

    private static HBox line(String a, String b) {
        Label l = new Label(a);
        Label r = new Label(b);
        l.getStyleClass().add("rg-cash-line");
        r.getStyleClass().add("rg-cash-line");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        return new HBox(8, l, sp, r);
    }

    private static VBox emptyState(String title, String sub) {
        SVGPath bag = icon(ICON_BAG, "rg-icon-empty");
        bag.setScaleX(1.6);
        bag.setScaleY(1.6);
        StackPane ib = new StackPane(bag);
        ib.setMinHeight(44);
        Label t = new Label(title);
        t.getStyleClass().add("rg-empty-title");
        Label s = new Label(sub);
        s.getStyleClass().add("rg-mini-muted");
        VBox v = new VBox(6, ib, t, s);
        v.setAlignment(Pos.CENTER);
        v.setMinHeight(220);
        return v;
    }

    private static String label(String wire) {
        if (wire == null || wire.isBlank()) {
            return "-";
        }
        PaymentType t = PaymentType.fromWire(wire);
        return t.wire().equalsIgnoreCase(wire) ? t.label() : wire;
    }

    private static String hhmm(String iso) {
        if (iso == null || iso.isBlank()) {
            return "--:--";
        }
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        } catch (RuntimeException e) {
            return iso.length() >= 16 ? iso.substring(11, 16) : iso;
        }
    }

    private static BigDecimal parse(String s) {
        try {
            return s == null || s.isBlank() ? BigDecimal.ZERO : Money.parseBr(s);
        } catch (DomainException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
