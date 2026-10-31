package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.BalancaBarcode;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.CartItem;
import br.com.quack.caixa.domain.Company;
import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.Money;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.service.CaixaService;
import br.com.quack.caixa.service.CatalogService;
import br.com.quack.caixa.service.ReceiptPrinter;
import br.com.quack.caixa.service.SyncWorker;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Frente de caixa no mesmo layout do caixa web (SalesStartPage): cabeçalho, coluna de produto, cupom. */
public class PdvView extends BorderPane {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final String ICON_SEARCH = "M9.5 3a6.5 6.5 0 0 1 5.17 10.44l4.45 4.45-1.41 1.41-4.45-4.45A6.5 6.5 0 1 1 9.5 3zm0 2a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9z";
    private static final String ICON_RECEIPT = "M5 2h14v20l-2.33-1.5L14.33 22 12 20.5 9.67 22 7.33 20.5 5 22V2zm2 2v14.3l.33-.21L9.67 19.6 12 18.1l2.33 1.5 2.34-1.5.33.21V4H7zm4 2h2v1.1c1.2.3 2 1.1 2 2.2h-2c0-.4-.4-.6-1-.6s-1 .3-1 .6c0 .4.3.5 1.3.8 1.4.4 2.7.9 2.7 2.5 0 1.1-.8 1.9-2 2.2V16h-2v-1.2c-1.2-.3-2-1.1-2-2.3h2c0 .4.4.7 1 .7s1-.3 1-.7c0-.4-.4-.6-1.4-.9C9.3 11.2 9 10.3 9 9.4c0-1.1.8-1.9 2-2.2V6z";
    private static final String ICON_IMAGE = "M4 4h16v16H4V4zm2 2v9.6l3.5-3.5 3 3 2-2L18 16.6V6H6zm9 1.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z";

    private final AppContext ctx;
    private final Runnable onLogout;
    private final Cart cart = new Cart();

    // coluna da esquerda
    private final TextField orderField = new TextField();
    private final TextField searchField = new TextField();
    private final TextField qtyField = new TextField("1");
    private final TextField unitField = new TextField("0,00");
    private final TextField lineTotalField = new TextField("0,00");
    private final Label volumesValue = new Label("0000");
    private final Label customerName = new Label("Consumidor");

    // sugestões da busca (popup abaixo do campo de produto)
    private final ListView<Product> suggestions = new ListView<>();
    private final Popup suggestionPopup = new Popup();

    // area central
    private final Label productTitle = new Label("AGUARDANDO PRODUTO");
    private final TableView<CartItem> table = new TableView<>();
    private final VBox emptyState = new VBox(6);
    private final Label subtotalValue = new Label("R$ 0,00");
    private final Label statusText = new Label("Validando caixa...");
    private final HBox statusBar = new HBox(8);
    private final Button reforcoBtn = new Button("Reforço (F1)");
    private final Button sangriaBtn = new Button("Sangria (F3)");
    private final Button openCashBtn = new Button("Abrir caixa (F6)");

    // cabeçalho e rodapé
    private final Button sendChip = new Button();
    private final Label offlineChip = new Label("Modo offline");
    private final Label dateLabel = new Label();
    private final Label clockLabel = new Label();
    private final Label footerCaixa = new Label();

    private Customer customer;
    private SaleOutcome lastOutcome;
    private boolean busy;
    private Timeline clock;
    private Timeline statusTimer;

    public PdvView(AppContext ctx, Runnable onLogout) {
        this.ctx = ctx;
        this.onLogout = onLogout;
        getStyleClass().add("rg-pdv");
        cart.setPromotions(ctx.catalog.promotions());
        lastOutcome = ctx.cache.lastReceipt().orElse(null);

        setTop(buildHeader());
        setLeft(buildSide());
        setCenter(buildMain());
        buildSuggestionPopup();

        refreshCart();
        refreshNet();
        ctx.sync.onCounts(c -> Platform.runLater(() -> updateCounts(c)));
        ctx.api.onReachabilityChanged(r -> Platform.runLater(this::refreshNet));
        updateCounts(ctx.sync.counts());
        refreshStatus();
        tickClock();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickClock()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        statusTimer = new Timeline(new KeyFrame(Duration.seconds(60), e -> refreshStatus()));
        statusTimer.setCycleCount(Timeline.INDEFINITE);
        statusTimer.play();
    }

    /** Instala os atalhos de teclado na cena. */
    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
    }

    public void dispose() {
        clock.stop();
        statusTimer.stop();
        suggestionPopup.hide();
        ctx.sync.onCounts(c -> { });
        ctx.api.onReachabilityChanged(r -> { });
    }

    public void focusSearch() {
        searchField.requestFocus();
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    // ---------- cabeçalho ----------

    private HBox buildHeader() {
        ImageView logo = new ImageView(new Image(PdvView.class.getResource("quack-logo.png").toExternalForm(),
                0, 64, true, true));
        logo.setFitHeight(32);
        logo.setPreserveRatio(true);
        StackPane logoBox = new StackPane(logo);
        logoBox.getStyleClass().add("rg-logo-box");

        Label title = new Label("Quack Sistemas");
        title.getStyleClass().add("rg-header-title");
        Label subtitle = new Label("Frente de Caixa • Soluções para seu negócio");
        subtitle.getStyleClass().add("rg-header-subtitle");
        VBox brand = new VBox(0, title, subtitle);
        brand.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        sendChip.getStyleClass().addAll("rg-chip", "rg-chip-danger");
        sendChip.setFocusTraversable(false);
        sendChip.setOnAction(e -> Toasts.info(window(), "As vendas pendentes são reenviadas sozinhas a cada 15 s. "
                + "Falhas definitivas ficam na pasta outbox/failed."));
        offlineChip.getStyleClass().addAll("rg-chip", "rg-chip-warn");

        HBox chips = new HBox(8, sendChip, offlineChip,
                chip("Sincronizar (F10)", this::syncNow),
                chip("Caixa (F6)", this::openCashPanel),
                chip("Vendas (F9)", this::openSessionSales),
                chip("Cancelar NFC-e (F7)", this::cancelNfce),
                chip("Tela cheia (F11)", this::toggleFullScreen),
                glassChip());
        chips.setAlignment(Pos.CENTER_RIGHT);

        dateLabel.getStyleClass().add("rg-header-date");
        clockLabel.getStyleClass().add("rg-header-clock");
        VBox when = new VBox(0, dateLabel, clockLabel);
        when.setAlignment(Pos.CENTER_RIGHT);
        when.setMinWidth(150);

        HBox header = new HBox(14, logoBox, brand, spacer, chips, when);
        header.getStyleClass().add("rg-pdv-header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /** Liga/desliga o tema vidro (translúcido) na hora e guarda a escolha. */
    private Button glassChip() {
        Button b = new Button("Vidro");
        b.getStyleClass().add("rg-chip");
        b.setFocusTraversable(false);
        b.setTooltip(new javafx.scene.control.Tooltip("Tema vidro: painéis translúcidos (clique para ligar/desligar)"));
        Runnable sync = () -> {
            b.getStyleClass().remove("rg-chip-glass-on");
            if (Fx.glass()) {
                b.getStyleClass().add("rg-chip-glass-on");
            }
        };
        b.setOnAction(e -> {
            boolean on = !Fx.glass();
            Fx.setGlass(on);
            ctx.config.setGlassTheme(on);
            sync.run();
            searchField.requestFocus();
        });
        sync.run();
        return b;
    }

    private Button chip(String text, Runnable action) {
        Button b = Fx.button(text, "rg-chip", action);
        b.setFocusTraversable(false);
        return b;
    }

    private void tickClock() {
        LocalDate d = LocalDate.now();
        String month = d.format(DateTimeFormatter.ofPattern("MMMM", PT_BR));
        month = month.isEmpty() ? month : Character.toUpperCase(month.charAt(0)) + month.substring(1);
        dateLabel.setText(d.getDayOfMonth() + " De " + month + " " + d.getYear());
        clockLabel.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    // ---------- coluna da esquerda ----------

    private VBox buildSide() {
        orderField.setPromptText("Nº do pedido");
        HBox.setHgrow(orderField, Priority.ALWAYS);
        Button orderBtn = Fx.button("Buscar", "rg-btn-small", () -> Toasts.info(window(),
                "Pedidos do vendedor ainda não estão disponíveis neste caixa. Use o caixa web."));
        orderBtn.setFocusTraversable(false);
        orderBtn.setMinWidth(Region.USE_PREF_SIZE);
        HBox orderRow = new HBox(6, orderField, orderBtn);
        orderRow.setAlignment(Pos.CENTER_LEFT);

        searchField.getStyleClass().add("rg-search-field");
        searchField.setPromptText("");
        SVGPath lupa = icon(ICON_SEARCH, "rg-icon-muted");
        lupa.setMouseTransparent(true);
        StackPane searchBox = new StackPane(searchField, lupa);
        StackPane.setAlignment(lupa, Pos.CENTER_LEFT);
        StackPane.setMargin(lupa, new Insets(0, 0, 0, 12));

        unitField.setEditable(false);
        unitField.setFocusTraversable(false);
        unitField.getStyleClass().add("rg-field-readonly");
        lineTotalField.setEditable(false);
        lineTotalField.setFocusTraversable(false);
        lineTotalField.getStyleClass().addAll("rg-field-readonly", "rg-field-strong");

        Button add = Fx.button("ADICIONAR ITEM (ENTER)", "rg-btn-success rg-btn-block", this::submitSearch);
        add.setFocusTraversable(false);

        Label volLabel = new Label("TOTAL VOLUMES");
        volLabel.getStyleClass().add("rg-mini-label");
        volumesValue.getStyleClass().add("rg-volumes");
        Region volSpacer = new Region();
        HBox.setHgrow(volSpacer, Priority.ALWAYS);
        HBox volumes = new HBox(volLabel, volSpacer, volumesValue);
        volumes.setAlignment(Pos.CENTER_LEFT);
        volumes.getStyleClass().add("rg-side-card");

        Label custLabel = new Label("CLIENTE (F5)");
        custLabel.getStyleClass().add("rg-mini-label");
        customerName.getStyleClass().add("rg-customer-name");
        customerName.setWrapText(true);
        VBox custCard = new VBox(2, custLabel, customerName);
        custCard.getStyleClass().addAll("rg-side-card", "rg-clickable");
        custCard.setOnMouseClicked(e -> pickCustomer());

        Label noImg = new Label("Sem imagem");
        noImg.getStyleClass().add("rg-mini-muted");
        VBox imgInner = new VBox(6, icon(ICON_IMAGE, "rg-icon-muted"), noImg);
        imgInner.setAlignment(Pos.CENTER);
        imgInner.getStyleClass().add("rg-image-box");
        VBox.setVgrow(imgInner, Priority.ALWAYS);
        VBox imgCard = new VBox(imgInner);
        imgCard.getStyleClass().add("rg-side-card");
        imgCard.setMinHeight(0);
        VBox.setVgrow(imgCard, Priority.ALWAYS);

        qtyField.textProperty().addListener((o, a, b) -> updatePreview());

        VBox side = new VBox(6,
                orderRow,
                sideLabel("PRODUTO:"), searchBox,
                sideLabel("QUANTIDADE:"), qtyField,
                sideLabel("PREÇO UNITÁRIO:"), unitField,
                sideLabel("PREÇO TOTAL:"), lineTotalField,
                add, volumes, custCard, imgCard);
        side.getStyleClass().add("rg-pdv-side");
        side.setPrefWidth(232);
        side.setMinWidth(190);
        VBox.setMargin(add, new Insets(4, 0, 2, 0));

        searchField.textProperty().addListener((o, a, b) -> updateSuggestions(b));
        searchField.focusedProperty().addListener((o, a, focused) -> {
            if (!focused) {
                PauseTransition p = new PauseTransition(Duration.millis(150));
                p.setOnFinished(e -> {
                    if (!searchField.isFocused()) {
                        suggestionPopup.hide();
                    }
                });
                p.play();
            }
        });
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                submitSearch();
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                moveSuggestion(1);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                moveSuggestion(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && suggestionPopup.isShowing()) {
                suggestionPopup.hide();
                e.consume();
            }
        });
        qtyField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (searchField.getText() != null && !searchField.getText().isBlank()) {
                    submitSearch();
                } else {
                    searchField.requestFocus();
                }
                e.consume();
            }
        });
        return side;
    }

    private static Label sideLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("rg-side-label");
        return l;
    }

    private static SVGPath icon(String path, String styleClass) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add(styleClass);
        return p;
    }

    private void buildSuggestionPopup() {
        suggestions.setFocusTraversable(false);
        suggestions.setPrefWidth(560);
        suggestions.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Product p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label name = new Label(p.name());
                name.getStyleClass().add("rg-suggest-name");
                Label meta = new Label("Cód. " + p.code() + (p.gtin().isBlank() ? "" : " • " + p.gtin())
                        + " • estoque " + Money.formatQty(p.stock()) + " " + p.unit());
                meta.getStyleClass().add("rg-mini-muted");
                Region sp = new Region();
                HBox.setHgrow(sp, Priority.ALWAYS);
                Label price = new Label("R$ " + Money.formatBr(p.price()));
                price.getStyleClass().add("rg-suggest-price");
                HBox row = new HBox(10, new VBox(0, name, meta), sp, price);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
                setText(null);
            }
        });
        suggestions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> updatePreview());
        suggestions.setOnMouseClicked(e -> {
            Product p = suggestions.getSelectionModel().getSelectedItem();
            if (p != null) {
                try {
                    addProduct(p, parseQty());
                } catch (DomainException ex) {
                    toastError(ex.getMessage());
                }
            }
        });
        StackPane host = new StackPane(suggestions);
        host.getStyleClass().add("rg-toast-host");
        host.getStylesheets().setAll(Fx.stylesheets());
        suggestionPopup.getContent().add(host);
        suggestionPopup.setAutoHide(true);
    }

    // ---------- area central ----------

    private VBox buildMain() {
        Company co = ctx.catalog.company();
        Label empresa = new Label("Empresa: " + (co == null ? "" : co.displayName()));
        Label cnpj = new Label("CNPJ: " + (co == null ? "" : formatCnpj(co.cnpj())));
        empresa.getStyleClass().add("rg-info-text");
        cnpj.getStyleClass().addAll("rg-info-text", "rg-info-strong");
        Region infoSp = new Region();
        HBox.setHgrow(infoSp, Priority.ALWAYS);
        HBox info = new HBox(empresa, infoSp, cnpj);
        info.getStyleClass().add("rg-info-row");
        info.setAlignment(Pos.CENTER_LEFT);

        Region dot = new Region();
        dot.getStyleClass().add("rg-status-dot");
        statusText.getStyleClass().add("rg-status-text");
        Region stSp = new Region();
        HBox.setHgrow(stSp, Priority.ALWAYS);
        reforcoBtn.getStyleClass().addAll("rg-btn-small", "rg-btn-outline-success");
        sangriaBtn.getStyleClass().addAll("rg-btn-small", "rg-btn-outline-danger");
        openCashBtn.getStyleClass().addAll("rg-btn-small", "rg-btn-outline-danger");
        for (Button b : new Button[] {reforcoBtn, sangriaBtn, openCashBtn}) {
            b.setFocusTraversable(false);
        }
        reforcoBtn.setOnAction(e -> movement("Reforco"));
        sangriaBtn.setOnAction(e -> movement("Sangria"));
        openCashBtn.setOnAction(e -> openCashPanel());
        statusBar.getChildren().addAll(dot, statusText, stSp, reforcoBtn, sangriaBtn, openCashBtn);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("rg-status-bar");

        Label nomeLbl = new Label("NOME PRODUTO");
        nomeLbl.getStyleClass().add("rg-mini-label");
        productTitle.getStyleClass().addAll("rg-product-title", "rg-product-waiting");
        productTitle.setMaxWidth(Double.MAX_VALUE);
        productTitle.setAlignment(Pos.CENTER);
        VBox productBox = new VBox(2, nomeLbl, productTitle);
        productBox.getStyleClass().add("rg-product-box");

        Label listLbl = new Label("Lista de itens:");
        listLbl.getStyleClass().add("rg-list-label");
        buildTable();
        Label e1 = new Label("Nenhum item no cupom");
        e1.getStyleClass().add("rg-empty-title");
        Label e2 = new Label("Busque um produto ou leia o código de barras para começar.");
        e2.getStyleClass().add("rg-mini-muted");
        SVGPath receipt = icon(ICON_RECEIPT, "rg-icon-empty");
        receipt.setScaleX(1.7);
        receipt.setScaleY(1.7);
        StackPane receiptBox = new StackPane(receipt);
        receiptBox.setMinHeight(44);
        emptyState.getChildren().addAll(receiptBox, e1, e2);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setMouseTransparent(true);
        StackPane listArea = new StackPane(table, emptyState);
        listArea.getStyleClass().add("rg-list-area");
        VBox.setVgrow(listArea, Priority.ALWAYS);
        VBox listBox = new VBox(4, listLbl, listArea);
        listBox.setPadding(new Insets(8, 8, 8, 8));
        VBox.setVgrow(listBox, Priority.ALWAYS);

        Label help = new Label("00 - Ajuda");
        help.getStyleClass().add("rg-help-row");
        help.setMaxWidth(Double.MAX_VALUE);
        help.setOnMouseClicked(e -> showHelp());

        Label subLbl = new Label("SUB TOTAL");
        subLbl.getStyleClass().add("rg-subtotal-label");
        subtotalValue.getStyleClass().add("rg-subtotal-value");
        StackPane subBox = new StackPane(subtotalValue);
        subBox.getStyleClass().add("rg-subtotal-box");
        subBox.setMinWidth(170);
        Region subSp = new Region();
        HBox.setHgrow(subSp, Priority.ALWAYS);
        HBox subtotal = new HBox(10, subSp, subLbl, subBox);
        subtotal.setAlignment(Pos.CENTER_RIGHT);
        subtotal.getStyleClass().add("rg-subtotal-row");

        Button cancel = Fx.button("✕  CANCELAR (F8)", "rg-btn-danger-fill rg-btn-action", this::cancelSale);
        Button reprint = Fx.button("Imprimir última venda", "rg-btn-action", this::reprint);
        Button pay = Fx.button("PAGAMENTO (F12)", "rg-btn-success rg-btn-action", this::openPayment);
        for (Button b : new Button[] {cancel, reprint, pay}) {
            b.setFocusTraversable(false);
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        HBox actions = new HBox(8, cancel, reprint, pay);
        actions.getStyleClass().add("rg-action-row");

        Label user = new Label("Usuário: " + ctx.session.user().name());
        Label est = new Label("Estabelecimento: " + (co == null ? "" : co.displayName()));
        Hyperlink sair = new Hyperlink("Sair");
        sair.setFocusTraversable(false);
        sair.setOnAction(e -> logout());
        for (Label l : new Label[] {user, est, footerCaixa}) {
            l.getStyleClass().add("rg-footer-text");
        }
        Region f1 = new Region();
        Region f2 = new Region();
        HBox.setHgrow(f1, Priority.ALWAYS);
        HBox.setHgrow(f2, Priority.ALWAYS);
        HBox footer = new HBox(8, user, f1, est, f2, footerCaixa, sair);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("rg-footer");

        VBox main = new VBox(0, info, statusBar, productBox, listBox, help, subtotal, actions, footer);
        main.getStyleClass().add("rg-pdv-main");
        BorderPane.setMargin(main, new Insets(0, 0, 0, 0));
        return main;
    }

    private void buildTable() {
        table.setPlaceholder(new Label(""));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getStyleClass().add("rg-cart-table");
        table.getColumns().add(col("Item", 50, (i, idx) -> String.format("%03d", idx + 1)));
        table.getColumns().add(col("Código", 110, (i, idx) -> i.code()));
        // Descrição + selos: "PREÇO ALTERADO • gerente" (com desfazer) ou "PROMO • nome", como na web.
        TableColumn<CartItem, String> desc = col("Descrição", 300, (i, idx) -> i.name());
        desc.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                CartItem it = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (it == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(it.name());
                HBox box = new HBox(6, name);
                box.setAlignment(Pos.CENTER_LEFT);
                if (it.priceOverridden()) {
                    Label badge = new Label("PREÇO ALTERADO" + (it.precoAutorizadoPor() == null
                            || it.precoAutorizadoPor().isBlank() ? "" : " • " + it.precoAutorizadoPor()));
                    Button undo = new Button("↺");
                    undo.getStyleClass().add("rg-badge-undo");
                    undo.setFocusTraversable(false);
                    undo.setTooltip(new javafx.scene.control.Tooltip("Voltar ao preço cadastrado (R$ "
                            + Money.formatBr(it.precoCadastro() != null ? it.precoCadastro() : it.unitPrice()) + ")"));
                    int index = getIndex();
                    undo.setOnAction(e -> restoreCatalogPrice(index));
                    HBox b = new HBox(4, badge, undo);
                    b.setAlignment(Pos.CENTER_LEFT);
                    b.getStyleClass().add("rg-badge-price");
                    box.getChildren().add(b);
                } else if (it.promocaoNome() != null) {
                    Label promo = new Label("PROMO • " + it.promocaoNome());
                    promo.getStyleClass().add("rg-badge-promo");
                    box.getChildren().add(promo);
                }
                setGraphic(box);
            }
        });
        table.getColumns().add(desc);
        table.getColumns().add(col("Qtd", 80, (i, idx) -> Money.formatQty(i.quantity()) + " " + i.unit()));
        // Vl. Unit + botão de etiqueta: alterar preço com senha de gerente.
        TableColumn<CartItem, String> unit = col("Vl. Unit", 110, (i, idx) -> Money.formatBr(i.unitPrice()));
        unit.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                CartItem it = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (it == null) {
                    setGraphic(null);
                    return;
                }
                Button tag = new Button();
                tag.setGraphic(Fx.icon(PriceOverrideDialog.ICON_TAG, "rg-icon-muted"));
                tag.getStyleClass().add("rg-btn-tag");
                tag.setFocusTraversable(false);
                tag.setTooltip(new javafx.scene.control.Tooltip("Alterar preço (exige a senha de um gerente)"));
                int index = getIndex();
                tag.setOnAction(e -> openPriceOverride(index));
                Label price = new Label(Money.formatBr(it.unitPrice()));
                if (it.priceOverridden()) {
                    price.getStyleClass().add("rg-price-overridden");
                }
                HBox box = new HBox(6, tag, price);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        table.getColumns().add(unit);
        table.getColumns().add(col("Desconto", 90, (i, idx) ->
                i.desconto().signum() > 0 ? "-" + Money.formatBr(i.desconto()) : "—"));
        table.getColumns().add(col("Vl. Total", 100, (i, idx) -> Money.formatBr(i.total())));
        TableColumn<CartItem, String> rm = new TableColumn<>("");
        rm.setPrefWidth(56);
        rm.setMaxWidth(70);
        rm.setSortable(false);
        rm.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                Button b = new Button("✕");
                b.getStyleClass().addAll("rg-btn-small", "rg-btn-remove");
                b.setFocusTraversable(false);
                b.setOnAction(e -> removeIndex(getIndex()));
                setGraphic(b);
            }
        });
        table.getColumns().add(rm);
    }

    private interface Cell {
        String get(CartItem item, int index);
    }

    private TableColumn<CartItem, String> col(String title, double width, Cell cell) {
        TableColumn<CartItem, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setCellValueFactory(f -> new ReadOnlyStringWrapper(
                cell.get(f.getValue(), table.getItems().indexOf(f.getValue()))));
        return c;
    }

    private static String formatCnpj(String raw) {
        String d = raw == null ? "" : raw.replaceAll("\\D", "");
        if (d.length() != 14) {
            return raw == null ? "" : raw;
        }
        return d.substring(0, 2) + "." + d.substring(2, 5) + "." + d.substring(5, 8) + "/" + d.substring(8, 12)
                + "-" + d.substring(12);
    }

    // ---------- busca e carrinho ----------

    private BigDecimal parseQty() {
        String t = qtyField.getText();
        if (t == null || t.isBlank()) {
            return BigDecimal.ONE;
        }
        return Money.parseBr(t);
    }

    private BigDecimal safeQty() {
        try {
            return parseQty();
        } catch (DomainException e) {
            return BigDecimal.ONE;
        }
    }

    /** Preço unitário/total do produto destacado na busca. */
    private void updatePreview() {
        Product p = suggestionPopup.isShowing() ? suggestions.getSelectionModel().getSelectedItem() : null;
        if (p == null) {
            unitField.setText("0,00");
            lineTotalField.setText("0,00");
            return;
        }
        unitField.setText(Money.formatBr(p.price()));
        lineTotalField.setText(Money.formatBr(Money.productMoney(safeQty(), p.price())));
    }

    private void updateSuggestions(String text) {
        List<Product> found = text == null || text.isBlank() ? List.of() : ctx.catalog.search(text);
        suggestions.getItems().setAll(found);
        if (found.isEmpty() || getScene() == null) {
            suggestionPopup.hide();
            updatePreview();
            return;
        }
        suggestions.setPrefHeight(Math.min(320, 8 + found.size() * 46.0));
        suggestions.getSelectionModel().selectFirst();
        if (!suggestionPopup.isShowing()) {
            Bounds b = searchField.localToScreen(searchField.getBoundsInLocal());
            if (b != null) {
                suggestionPopup.show(searchField, b.getMinX(), b.getMaxY() + 4);
            }
        }
        updatePreview();
    }

    private void moveSuggestion(int delta) {
        int n = suggestions.getItems().size();
        if (n == 0 || !suggestionPopup.isShowing()) {
            return;
        }
        int i = suggestions.getSelectionModel().getSelectedIndex() + delta;
        i = Math.max(0, Math.min(n - 1, i));
        suggestions.getSelectionModel().select(i);
        suggestions.scrollTo(i);
    }

    private void submitSearch() {
        String text = searchField.getText() == null ? "" : searchField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if ("00".equals(text)) {
            searchField.clear();
            showHelp();
            return;
        }
        try {
            BigDecimal qty = parseQty();
            CatalogService cat = ctx.catalog;
            Product exact = cat.findExact(text);
            if (exact != null) {
                addProduct(exact, qty);
                return;
            }
            BalancaBarcode.Result bal = BalancaBarcode.parse(text, cat.products());
            if (bal != null) {
                Product p = cat.findByCode(bal.productCode());
                if (p == null) {
                    toastError("Produto com código de balança " + bal.productCode() + " não encontrado");
                    return;
                }
                addProduct(p, bal.weightKg());
                return;
            }
            Product sel = suggestions.getSelectionModel().getSelectedItem();
            if (sel != null && suggestionPopup.isShowing()) {
                addProduct(sel, qty);
                return;
            }
            toastError("Produto não encontrado.");
        } catch (DomainException e) {
            toastError(e.getMessage());
        }
    }

    private void addProduct(Product p, BigDecimal qty) {
        try {
            String warning = cart.add(p, qty);
            if (warning != null) {
                Toasts.info(window(), warning);
            }
            productTitle.setText(p.name().toUpperCase(PT_BR));
            productTitle.getStyleClass().remove("rg-product-waiting");
            suggestionPopup.hide();
            searchField.clear();
            qtyField.setText("1");
            refreshCart();
            searchField.requestFocus();
        } catch (DomainException e) {
            toastError(e.getMessage());
        }
    }

    /** Alterar o preço do item com a senha de um gerente (PriceOverrideModal da web). */
    private void openPriceOverride(int idx) {
        if (idx < 0 || idx >= table.getItems().size()) {
            return;
        }
        CartItem item = cart.rawAt(idx);
        PriceOverrideDialog.show(window(), ctx, item.code(), item.name(), item.unitPrice()).ifPresent(a -> {
            cart.applyAuthorizedPrice(idx, a.novoPreco(), a.autorizacaoId(), a.supervisorNome());
            refreshCart();
            Toasts.success(window(), "Preço alterado com autorização de " + a.supervisorNome() + ".");
        });
        searchField.requestFocus();
    }

    /** Volta o item ao preço cadastrado (descarta a autorização do gerente). */
    private void restoreCatalogPrice(int idx) {
        if (idx >= 0 && idx < table.getItems().size()) {
            cart.restoreCatalogPrice(idx);
            refreshCart();
            searchField.requestFocus();
        }
    }

    private void removeIndex(int idx) {
        if (idx >= 0) {
            cart.removeAt(idx);
            refreshCart();
            searchField.requestFocus();
        }
    }

    private void refreshCart() {
        List<CartItem> items = cart.pricedItems();
        table.getItems().setAll(items);
        boolean empty = items.isEmpty();
        emptyState.setVisible(empty);
        table.setVisible(!empty);
        if (empty) {
            productTitle.setText("AGUARDANDO PRODUTO");
            if (!productTitle.getStyleClass().contains("rg-product-waiting")) {
                productTitle.getStyleClass().add("rg-product-waiting");
            }
        } else {
            table.scrollTo(items.size() - 1);
        }
        subtotalValue.setText("R$ " + Money.formatBr(cart.total()));
        BigDecimal vol = BigDecimal.ZERO;
        for (CartItem i : items) {
            vol = vol.add(i.quantity());
        }
        String v = Money.formatQty(vol);
        volumesValue.setText(v.length() >= 4 ? v : "0".repeat(4 - v.length()) + v);
        updatePreview();
    }

    private void setCustomer(Customer c) {
        customer = c;
        customerName.setText(c == null ? "Consumidor" : c.name());
    }

    // ---------- ações ----------

    private void toastError(String m) {
        Toasts.error(window(), m);
    }

    private void onKey(KeyEvent e) {
        if (busy || getScene() == null) {
            return;
        }
        switch (e.getCode()) {
            case F1 -> run(e, () -> movement("Reforco"));
            case F2 -> run(e, () -> {
                searchField.requestFocus();
                searchField.selectAll();
            });
            case F3 -> run(e, () -> movement("Sangria"));
            case F4 -> run(e, () -> {
                qtyField.requestFocus();
                qtyField.selectAll();
            });
            case F5 -> run(e, this::pickCustomer);
            case F6 -> run(e, this::openCashPanel);
            case F7 -> run(e, this::cancelNfce);
            case F8 -> run(e, this::cancelSale);
            case F9 -> run(e, this::openSessionSales);
            case F10 -> run(e, this::syncNow);
            case F11 -> run(e, this::toggleFullScreen);
            case F12 -> run(e, this::openPayment);
            case DELETE -> {
                if (getScene().getFocusOwner() == table) {
                    run(e, () -> removeIndex(table.getSelectionModel().getSelectedIndex()));
                }
            }
            default -> { }
        }
    }

    private void run(KeyEvent e, Runnable r) {
        e.consume();
        r.run();
    }

    private void toggleFullScreen() {
        if (window() instanceof Stage st) {
            st.setFullScreenExitHint("");
            st.setFullScreen(!st.isFullScreen());
        }
    }

    /** F7: cancelar NFC-e/NF-e com senha de gerente, ou emitir NF-e de devolução (como no caixa web). */
    private void cancelNfce() {
        if (NfceCancelDialog.show(window(), ctx, null)) {
            refreshStatus();
        }
        searchField.requestFocus();
    }

    private void openSessionSales() {
        SessionSalesDialog.show(window(), ctx);
        searchField.requestFocus();
    }

    private void showHelp() {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.initOwner(window());
        a.setTitle("Ajuda");
        a.setHeaderText("Atalhos do caixa");
        a.setContentText("""
                F1  Reforço            F2  Buscar produto
                F3  Sangria            F4  Quantidade
                F5  Cliente            F6  Caixa (abrir/fechar)
                F7  Cancelar NFC-e     F8  Cancelar venda
                F9  Vendas do caixa    F10 Sincronizar
                F11 Tela cheia         F12 Pagamento
                Del Remove o item selecionado
                00 + Enter  Esta ajuda""");
        a.getDialogPane().getStylesheets().setAll(Fx.stylesheets());
        a.getDialogPane().getStyleClass().add("rg-help-dialog");
        a.showAndWait();
        searchField.requestFocus();
    }

    private void pickCustomer() {
        CustomerPickerDialog.show(window(), ctx.catalog).ifPresent(ch -> setCustomer(ch.customer()));
        searchField.requestFocus();
    }

    private void cancelSale() {
        if (cart.isEmpty()) {
            Toasts.info(window(), "Não há venda em andamento.");
            return;
        }
        if (Fx.confirm(window(), "Cancelar venda", "Descartar o carrinho e os pagamentos em andamento?")) {
            cart.clear();
            setCustomer(null);
            refreshCart();
            Toasts.info(window(), "Venda cancelada.");
        }
        searchField.requestFocus();
    }

    private void openCashPanel() {
        CashPanelDialog.show(window(), ctx, this::logout);
        refreshStatus();
        searchField.requestFocus();
    }

    private void movement(String tipo) {
        CaixaStatusDto s = ctx.session.caixaStatus();
        if (s == null || !s.open()) {
            toastError("Abra o caixa antes de lançar " + ("Sangria".equals(tipo) ? "sangria" : "reforço") + ".");
            openCashPanel();
            return;
        }
        if (CashMovementDialog.show(window(), ctx, tipo)) {
            String what = "Sangria".equals(tipo) ? "Sangria registrada" : "Reforço registrado";
            if (ctx.caixa.lastWriteOffline()) {
                Toasts.info(window(), what + " localmente (modo offline).");
            } else {
                Toasts.success(window(), what + ".");
            }
            refreshStatus();
        }
        searchField.requestFocus();
    }

    private void openPayment() {
        if (cart.isEmpty()) {
            toastError("Adicione ao menos um item.");
            return;
        }
        setBusy(true);
        // Revalida o caixa antes de abrir o pagamento.
        Fx.async(ctx.caixa::status, r -> {
            setBusy(false);
            applyStatus(r);
            CaixaStatusDto s = r.status();
            if (s == null || !s.canSell()) {
                String reason = s == null || s.blockReason() == null || s.blockReason().isBlank()
                        ? "Abra o caixa antes de vender." : s.blockReason();
                toastError(reason);
                openCashPanel();
                return;
            }
            showPayment();
        }, t -> {
            setBusy(false);
            toastError(Fx.message(t));
        });
    }

    private void showPayment() {
        List<CartItem> items = cart.pricedItems();
        var res = PaymentDialog.show(window(), ctx, items, cart.total(), customer, null);
        if (res.isPresent()) {
            SaleOutcome o = res.get();
            lastOutcome = o;
            cart.clear();
            setCustomer(null);
            cart.setPromotions(ctx.catalog.promotions());
            refreshCart();
            updateCounts(ctx.sync.counts());
            Toasts.success(window(), (o.offline() ? "Venda salva offline: " : "Venda registrada: ")
                    + o.saleNumber());
            printReceipt(o);
            if (!o.offline()) {
                refreshStatus();
            }
        }
        searchField.requestFocus();
    }

    /**
     * Impressão logo após a venda (como na web): venda com NFC-e espera a nota ficar pronta (até ~30 s) e
     * imprime o DANFE; venda sem nota (ou offline) imprime o cupom não fiscal na hora.
     */
    private void printReceipt(SaleOutcome o) {
        if (!o.emitirFiscal() || o.offline()) {
            printNow(() -> ReceiptPrinter.buildCupom(o, ctx.catalog.company(), null), "o cupom");
            return;
        }
        Toasts.info(window(), "Venda salva. O comprovante será impresso automaticamente quando estiver pronto.");
        Fx.async(() -> waitFiscal(o.saleNumber()), doc -> {
            if (doc == null || !doc.hasXml()) {
                Toasts.info(window(), "A NFC-e da venda " + o.saleNumber()
                        + " ainda não ficou pronta. Use \"Imprimir última venda\" para imprimir depois.");
                return;
            }
            printNow(() -> ReceiptPrinter.buildDanfeFor(o, ctx.catalog.company(), doc), "o DANFE");
        }, t -> Toasts.error(window(), "Venda salva, mas o comprovante não foi impresso: " + Fx.message(t)));
    }

    /** Consulta a nota da venda a cada 1,5 s até o XML chegar (máx. 20 tentativas). Roda fora da FX thread. */
    private br.com.quack.caixa.api.FiscalApi.FiscalDoc waitFiscal(String saleNumber) {
        br.com.quack.caixa.api.FiscalApi.FiscalDoc doc = null;
        for (int i = 0; i < 20; i++) {
            doc = ctx.fiscalApi.bySaleNumber(saleNumber);
            if (doc != null && doc.hasXml()) {
                return doc;
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return doc;
            }
        }
        return doc;
    }

    /** Envia à impressora em segundo plano: a tela segue livre para a próxima venda. */
    private void printNow(java.util.function.Supplier<Node> build, String what) {
        Node receipt;
        try {
            receipt = build.get();
        } catch (RuntimeException ex) {
            Toasts.error(window(), "Venda salva, mas " + what + " não foi impresso: " + Fx.message(ex)
                    + ". Use \"Imprimir última venda\".");
            return;
        }
        ReceiptPrinter.printAsync(receipt, err -> {
            if (err != null && window() != null && window().isShowing()) {
                Toasts.error(window(), "Venda salva, mas " + what + " não foi impresso: " + err
                        + ". Use \"Imprimir última venda\".");
            }
        });
    }

    /** "Imprimir última venda": abre a pré-visualização (DANFE ou cupom); imprime pelo botão dela. */
    private void reprint() {
        if (lastOutcome == null) {
            Toasts.info(window(), "Nenhuma venda finalizada nesta estação.");
            return;
        }
        if (ReceiptPreviewDialog.show(window(), ctx, lastOutcome)) {
            Toasts.success(window(), "Comprovante da venda " + lastOutcome.saleNumber()
                    + " enviado para a impressora.");
        }
        searchField.requestFocus();
    }

    private void syncNow() {
        setBusy(true);
        Toasts.info(window(), "Sincronizando...");
        Fx.async(() -> {
            var load = ctx.catalog.load();
            ctx.sync.triggerNow();
            return load;
        }, load -> {
            setBusy(false);
            cart.setPromotions(ctx.catalog.promotions());
            refreshCart();
            Toasts.success(window(), (load.fromCache() ? "Sem conexão: usando o cache. " : "Atualizado: ")
                    + load.products() + " produtos, " + load.customers() + " clientes, "
                    + load.promotions() + " promoções.");
            refreshStatus();
        }, t -> {
            setBusy(false);
            toastError(Fx.message(t));
        });
    }

    private void logout() {
        if (!cart.isEmpty() && !Fx.confirm(window(), "Sair", "Há uma venda em andamento. Sair mesmo assim?")) {
            return;
        }
        onLogout.run();
    }

    private void setBusy(boolean b) {
        busy = b;
        setDisable(b);
    }

    // ---------- status ----------

    private void refreshStatus() {
        Fx.async(ctx.caixa::status, this::applyStatus, t -> { });
    }

    private void applyStatus(CaixaService.StatusResult r) {
        CaixaStatusDto s = r.status();
        boolean open = s != null && s.open() && s.canSell();
        statusBar.getStyleClass().removeAll("rg-status-ok", "rg-status-blocked");
        statusBar.getStyleClass().add(open ? "rg-status-ok" : "rg-status-blocked");
        if (open) {
            long m = s.currentSession() == null ? 0 : s.currentSession().elapsedMinutes();
            statusText.setText("Caixa aberto por " + elapsed(m));
        } else {
            statusText.setText(s == null || s.blockReason() == null || s.blockReason().isBlank()
                    ? "Caixa fechado — abra o caixa para vender" : s.blockReason());
        }
        show(reforcoBtn, open);
        show(sangriaBtn, open);
        show(openCashBtn, !open);
        String dev = ctx.config.deviceId() == null ? "PDV" : ctx.config.deviceId().substring(0, 4).toUpperCase();
        footerCaixa.setText("Prévia impressão: Não • Caixa: " + dev + (open ? " aberto" : " bloqueado"));
        refreshNet();
    }

    private static String elapsed(long minutes) {
        if (minutes < 1) {
            return "menos de 1 min";
        }
        long h = minutes / 60;
        long m = minutes % 60;
        return h == 0 ? m + " min" : h + "h " + String.format("%02d", m) + "min";
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }

    private void refreshNet() {
        boolean offline = !ctx.api.apiReachable() || ctx.session.offlineStatus();
        show(offlineChip, offline);
    }

    private void updateCounts(SyncWorker.Counts c) {
        if (c.failed() > 0) {
            sendChip.setText("⚠  " + c.failed() + (c.failed() == 1 ? " falha no envio" : " falhas no envio"));
            show(sendChip, true);
        } else if (c.pending() > 0) {
            sendChip.setText(c.pending() + (c.pending() == 1 ? " pendente de envio" : " pendentes de envio"));
            show(sendChip, true);
        } else {
            show(sendChip, false);
        }
    }
}
