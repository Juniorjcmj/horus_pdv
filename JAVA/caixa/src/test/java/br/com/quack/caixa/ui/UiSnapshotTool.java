package br.com.quack.caixa.ui;

import br.com.quack.caixa.AppContext;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.CartItem;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javax.imageio.ImageIO;

/**
 * Ferramenta de desenvolvimento: renderiza as telas fora da área visível e salva PNGs, sem tocar no
 * teclado/mouse do usuário. Uso: java -cp "quack-caixa.jar;test-classes" br.com.quack.caixa.ui.UiSnapshotTool
 * <pastaSaida> <urlApi> <email> <senha>
 */
public final class UiSnapshotTool {
    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        System.setProperty("quack.home", new File(out, "home").getAbsolutePath());
        Platform.startup(Fx::loadFonts);
        if (args.length > 4 && "glass".equals(args[4])) {
            Fx.setGlass(true);
        }
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(args[1]);
        ctx.session.setUser(ctx.authApi.login(args[2], args[3]));
        ctx.catalog.load();
        CaixaStatusDto st = ctx.caixa.status().status();
        if (!st.canSell()) {
            // Caixa de teste esquecido aberto (outra sessão/terminal): fecha antes de abrir um novo.
            if (st.currentSession() != null) {
                try {
                    String esperado = st.currentSession().expectedCashAmount();
                    ctx.caixa.fechar(esperado == null || esperado.isBlank() ? "0,00" : esperado, "snapshot",
                            "fechamento automático da ferramenta de imagens");
                } catch (RuntimeException e) {
                    System.err.println("Não fechou o caixa anterior: " + e.getMessage());
                }
            }
            ctx.caixa.abrir("100,00");
            st = ctx.caixa.status().status();
        }
        CaixaStatusDto status = st;

        Cart cart = new Cart();
        cart.setPromotions(ctx.catalog.promotions());
        cart.add(ctx.catalog.findExact("7894900011517"), new BigDecimal("3"));
        cart.add(ctx.catalog.findExact("1001"), BigDecimal.ONE);
        List<CartItem> items = cart.pricedItems();

        onFx(() -> {
            // Login.
            LoginView lv = new LoginView("http://localhost:5260", "", true, null, (u, e, p, r) -> { }, x -> { },
                    "https://pdv.quacksistemas.com.br");
            Stage ls = stage(lv, 1000, 560);
            sleepFx(500);
            save(ls.getScene(), new File(out, "login.png"));
            ls.close();

            // Tela principal vazia e com itens.
            PdvView pdv = new PdvView(ctx, () -> { });
            Stage s = stage(pdv, 1280, 720);
            sleepFx(800);
            save(s.getScene(), new File(out, "pdv-vazio.png"));
            call(pdv, "addProduct", ctx.catalog.findExact("7894900011517"), new BigDecimal("3"));
            call(pdv, "addProduct", ctx.catalog.findExact("1001"), BigDecimal.ONE);
            sleepFx(200);
            save(s.getScene(), new File(out, "pdv-itens.png"));
            // Preço alterado com senha de gerente (linha com o selo) e o modal de autorização.
            var cartF = PdvView.class.getDeclaredField("cart");
            cartF.setAccessible(true);
            ((Cart) cartF.get(pdv)).applyAuthorizedPrice(1, new BigDecimal("15.00"), "aut-x", "Gerente Bia");
            call(pdv, "refreshCart");
            sleepFx(200);
            save(s.getScene(), new File(out, "pdv-preco.png"));
            Platform.runLater(() -> {
                sleepFx(1500);
                for (javafx.stage.Window w : List.copyOf(javafx.stage.Window.getWindows())) {
                    if (w instanceof Stage s2 && w != s && w.getScene() != null
                            && w.getScene().lookup(".rg-modal") != null) {
                        save(w.getScene(), new File(out, "preco-modal.png"));
                        s2.close();
                    }
                }
            });
            PriceOverrideDialog.show(s, ctx, "1001", "Cafe Pilao 500g", new BigDecimal("18.90"));

            // Cancelamento de NFC-e: venda 21 (prazo vencido -> sugere devolução) e comprovantes.
            Platform.runLater(() -> {
                sleepFx(3000);
                for (javafx.stage.Window w : List.copyOf(javafx.stage.Window.getWindows())) {
                    if (w instanceof Stage s2 && w != s && w.getScene() != null
                            && w.getScene().lookup(".rg-nfc-body") != null) {
                        save(w.getScene(), new File(out, "nfce-cancelar.png"));
                        s2.close();
                    }
                }
            });
            NfceCancelDialog.show(s, ctx, System.getProperty("snap.nfce", "21"));

            // Correção de venda: etapas "itens" (com um item marcado) e "justificativa", via reflexão.
            Object corr = construct(SaleCorrectionDialog.class, new Class<?>[] {AppContext.class, String.class}, ctx,
                    System.getProperty("snap.corr", "29"));
            call(corr, "build");
            var detF = SaleCorrectionDialog.class.getDeclaredField("detail");
            detF.setAccessible(true);
            detF.set(corr, ctx.saleHistoryApi.details(System.getProperty("snap.corr", "29")));
            var selF = SaleCorrectionDialog.class.getDeclaredField("selected");
            selF.setAccessible(true);
            ((java.util.Set<String>) selF.get(corr)).add("2002");
            call(corr, "render");
            Stage cs1 = stage(card((Region) field(corr, "root")), -1, -1);
            sleepFx(300);
            save(cs1.getScene(), new File(out, "correcao-itens.png"));
            var stepF = SaleCorrectionDialog.class.getDeclaredField("step");
            stepF.setAccessible(true);
            Object justify = SaleCorrectionDialog.class.getDeclaredClasses()[0].getEnumConstants()[1];
            stepF.set(corr, justify);
            call(corr, "render");
            sleepFx(300);
            cs1.sizeToScene();
            sleepFx(200);
            save(cs1.getScene(), new File(out, "correcao-justificativa.png"));
            cs1.close();
            var fdoc = ctx.fiscalApi.search(System.getProperty("snap.nfce", "21"));
            if (fdoc != null) {
                Stage c1 = stage(paper(br.com.quack.caixa.service.ReceiptPrinter.buildCancelamento(fdoc,
                        new br.com.quack.caixa.api.FiscalApi.CancelResult(fdoc.id(), "333260000777001", null,
                                "Operador Teste"), "Desistência da compra pelo cliente.")), -1, -1);
                sleepFx(200);
                save(c1.getScene(), new File(out, "comprovante-cancelamento.png"));
                c1.close();
                Stage c2 = stage(paper(br.com.quack.caixa.service.ReceiptPrinter.buildDevolucao(fdoc,
                        new br.com.quack.caixa.api.FiscalApi.DevolucaoResult(fdoc.id(), 15, 1,
                                "33261011222333000181550010000000151000000150", "333260000888015", null,
                                "Operador Teste", "Entrada Própria"), "Estorno de venda com prazo de NFC-e expirado.")),
                        -1, -1);
                sleepFx(200);
                save(c2.getScene(), new File(out, "comprovante-devolucao.png"));
                c2.close();
            }
            s.close();

            // Pagamento.
            Object pay = construct(PaymentDialog.class, new Class<?>[] {AppContext.class, List.class, BigDecimal.class,
                    br.com.quack.caixa.domain.Customer.class, String.class}, ctx, items, cart.total(), null, null);
            call(pay, "build");
            call(pay, "resetValues");
            Region payRoot = (Region) field(pay, "root");
            Stage ps = stage(card(payRoot), -1, -1);
            sleepFx(200);
            save(ps.getScene(), new File(out, "pagamento.png"));
            ps.close();

            // Caixa (turno aberto).
            Object cash = construct(CashPanelDialog.class, new Class<?>[] {AppContext.class}, ctx);
            call(cash, "build");
            call(cash, "render", status, false);
            Region cashRoot = (Region) field(cash, "root");
            Stage cs = stage(card(cashRoot), -1, -1);
            sleepFx(200);
            save(cs.getScene(), new File(out, "caixa.png"));
            cs.close();

            // Vendas do caixa atual.
            Object sales = construct(SessionSalesDialog.class, new Class<?>[] {AppContext.class}, ctx);
            call(sales, "build");
            Region salesRoot = (Region) field(sales, "root");
            Stage ss = stage(card(salesRoot), -1, -1);
            call(sales, "load");
            sleepFx(2500);
            save(ss.getScene(), new File(out, "vendas.png"));
            ss.close();

            // Pré-visualização do cupom: faz uma venda de verdade e abre o modal (que bloqueia até fechar).
            br.com.quack.caixa.domain.PaymentPlan plan = new br.com.quack.caixa.domain.PaymentPlan(cart.total());
            plan.finalizeWith(br.com.quack.caixa.domain.PaymentType.DINHEIRO, cart.total(), new BigDecimal("50.00"),
                    null);
            var sale = ctx.sales.confirm(items, plan, null, null, ctx.session.user().name(), ctx.session.user().id(),
                    ctx.config.deviceId());
            // Cupom não fiscal (com o QR Code dos dados da venda) e DANFE NFC-e do XML de exemplo.
            Stage cup = stage(paper(br.com.quack.caixa.service.ReceiptPrinter.buildCupom(sale, ctx.catalog.company(),
                    null)), -1, -1);
            sleepFx(200);
            save(cup.getScene(), new File(out, "cupom.png"));
            cup.close();
            String xml;
            try (var in = UiSnapshotTool.class.getResourceAsStream("/br/com/quack/caixa/fiscal/nfce-autorizada.xml")) {
                xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            Stage dan = stage(paper(br.com.quack.caixa.service.ReceiptPrinter.buildDanfe(
                    br.com.quack.caixa.domain.fiscal.DanfeXml.parse(xml))), -1, -1);
            sleepFx(200);
            save(dan.getScene(), new File(out, "danfe.png"));
            dan.close();

            // Cupom de fechamento de caixa com sangria/reforço e diferença.
            var movs = List.of(
                    new CaixaStatusDto.Movement("m1", "Sangria", "30,00", "Depósito no banco", "2026-10-31T13:10:00Z",
                            "Operador Teste"),
                    new CaixaStatusDto.Movement("m2", "Reforco", "20,00", "Troco extra", "2026-10-31T14:05:00Z",
                            "Operador Teste"));
            var bd = List.of(new CaixaStatusDto.Breakdown("dinheiro", "57,80"),
                    new CaixaStatusDto.Breakdown("pix", "45,00"), new CaixaStatusDto.Breakdown("debito", "18,90"));
            var fech = new CaixaStatusDto.Session("cx-1793420927000", "fechado", "2026-10-31T12:00:00Z",
                    "2026-10-31T18:30:00Z", "100,00", "145,00", "op1", "Operador Teste", 390, "147,80", "-2,80",
                    "Troco dado a mais", movs, bd, "Operador Teste", "Turno tranquilo");
            Stage fc = stage(paper(br.com.quack.caixa.service.ReceiptPrinter.buildCashClosing(fech,
                    ctx.catalog.company())), -1, -1);
            sleepFx(200);
            save(fc.getScene(), new File(out, "fechamento.png"));
            fc.close();

            // Modal real: a NFC-e do ambiente de teste nunca autoriza, então ele fica "preparando".
            Platform.runLater(() -> {
                sleepFx(2500);
                for (javafx.stage.Window w : List.copyOf(javafx.stage.Window.getWindows())) {
                    if (w instanceof Stage s2 && w.getScene() != null
                            && w.getScene().lookup(".rg-preview-modal") != null) {
                        w.setX(-10000);
                        save(w.getScene(), new File(out, "previa.png"));
                        s2.close();
                    }
                }
            });
            ReceiptPreviewDialog.show(null, ctx, sale);
        });
        Platform.exit();
        System.exit(0);
    }

    private static Parent paper(javafx.scene.Node receipt) {
        StackPane p = new StackPane(receipt);
        p.setStyle("-fx-background-color: #e5e7eb; -fx-padding: 16;");
        return p;
    }

    private static Parent card(Region content) {
        content.getStyleClass().add("rg-modal");
        StackPane wrap = new StackPane(content);
        wrap.getStyleClass().add("rg-modal-wrap");
        wrap.setStyle("-fx-background-color: #6b7280;");
        return wrap;
    }

    private static Stage stage(Parent root, double w, double h) {
        Scene sc = w > 0 ? new Scene(root, w, h) : new Scene(root);
        sc.getStylesheets().setAll(Fx.stylesheets());
        Stage s = new Stage(StageStyle.UNDECORATED);
        s.setScene(sc);
        s.setX(-10000);
        s.setY(-10000);
        s.show();
        return s;
    }

    private static void save(Scene scene, File file) {
        WritableImage img = scene.snapshot(null);
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        try {
            ImageIO.write(bi, "png", file);
            System.out.println("salvo " + file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Espera processando eventos da FX thread (para Fx.async e layout). */
    private static void sleepFx(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Object key = new Object();
            Platform.runLater(() -> Platform.exitNestedEventLoop(key, null));
            Platform.enterNestedEventLoop(key);
        }
    }

    private static void onFx(ThrowingRunnable r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Exception[] err = new Exception[1];
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Exception e) {
                err[0] = e;
            } finally {
                done.countDown();
            }
        });
        done.await();
        if (err[0] != null) {
            throw err[0];
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static Object construct(Class<?> c, Class<?>[] types, Object... args) throws Exception {
        Constructor<?> k = c.getDeclaredConstructor(types);
        k.setAccessible(true);
        return k.newInstance(args);
    }

    private static Object field(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void call(Object o, String name, Object... args) throws Exception {
        for (Method m : o.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                m.invoke(o, args);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
