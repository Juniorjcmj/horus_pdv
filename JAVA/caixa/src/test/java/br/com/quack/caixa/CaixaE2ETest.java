package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.BalancaBarcode;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.Customer;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.service.CatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ponta a ponta contra uma API real (só roda com -Dquack.e2e.url=http://localhost:5260).
 * Espera a base de teste: produtos 1001 (café, 18,90), 0015 (queijo kg, 45,00), 2002 (refri 5,00 com
 * leve 3 pague 2) e a cliente "Maria Fiado" com limite.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class CaixaE2ETest {
    @TempDir
    static Path home;

    @Test
    void jornadaCompletaDoCaixa() {
        System.setProperty("quack.home", home.toString());
        String url = System.getProperty("quack.e2e.url");
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(url);

        // Login
        AuthApi.User user = ctx.authApi.login(System.getProperty("quack.e2e.email", "caixa@teste.com"),
                System.getProperty("quack.e2e.senha", "Teste@12345"));
        ctx.session.setUser(user);
        log("login ok: " + user.name() + " (" + user.role() + ")");

        // Catálogo
        CatalogService.LoadResult load = ctx.catalog.load();
        log("catalogo: " + load);
        assertFalse(load.fromCache());
        assertTrue(load.products() >= 3);
        assertTrue(load.promotions() >= 1);

        // Caixa
        CaixaStatusDto st = ctx.caixa.status().status();
        if (!st.canSell()) {
            st = ctx.caixa.abrir("100,00");
            log("caixa aberto com R$ 100,00");
        }
        assertTrue(st.canSell(), "caixa deveria permitir venda: " + st.blockReason());

        // Venda 1: 3 refri (leve 3 pague 2) + café, dinheiro com troco.
        Cart cart = cart(ctx);
        cart.add(ctx.catalog.findExact("7894900011517"), new BigDecimal("3"));
        cart.add(ctx.catalog.findExact("1001"), BigDecimal.ONE);
        assertEquals(0, new BigDecimal("28.90").compareTo(cart.total()), "total com promoção");
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.DINHEIRO, cart.total(), new BigDecimal("50.00"), null);
        assertEquals(0, new BigDecimal("21.10").compareTo(plan.totalChange()));
        SaleOutcome v1 = confirm(ctx, cart, plan, null);
        assertFalse(v1.offline());
        log("venda 1 online: nº " + v1.saleNumber() + " total 28,90 troco 21,10 (promo "
                + cart.pricedItems().get(0).promocaoNome() + ")");

        // Venda 2: etiqueta de balança do queijo, paga no fiado da Maria.
        String etiqueta = etiquetaBalanca("200015004520");
        Product queijo = ctx.catalog.findByCode("0015");
        BalancaBarcode.Result lida = BalancaBarcode.parse(etiqueta, ctx.catalog.products());
        assertNotNull(lida);
        Cart cart2 = cart(ctx);
        cart2.add(queijo, lida.weightKg());
        Customer maria = ctx.catalog.searchCustomers("Maria").get(0);
        BigDecimal dividaAntes = maria.debt();
        PaymentPlan plan2 = new PaymentPlan(cart2.total());
        plan2.finalizeWith(PaymentType.FIADO, cart2.total(), null, maria);
        SaleOutcome v2 = confirm(ctx, cart2, plan2, maria);
        assertFalse(v2.offline());
        Customer mariaDepois = ctx.catalog.searchCustomers("Maria").get(0);
        assertEquals(0, dividaAntes.add(cart2.total()).compareTo(mariaDepois.debt()));
        log("venda 2 online (balança " + etiqueta + " -> " + lida.weightKg() + " kg, fiado R$ " + cart2.total()
                + "): nº " + v2.saleNumber() + "; dívida da Maria " + dividaAntes + " -> " + mariaDepois.debt());

        // Sangria
        ctx.caixa.movimento("Sangria", "10,00", "Teste de sangria");
        log("sangria de R$ 10,00 registrada");

        // Queda da API: a venda vai para a fila em disco.
        ctx.api.setBaseUrl("http://127.0.0.1:1");
        Cart cart3 = cart(ctx);
        cart3.add(ctx.catalog.findExact("1001"), new BigDecimal("2"));
        PaymentPlan plan3 = new PaymentPlan(cart3.total());
        plan3.finalizeWith(PaymentType.PIX, null, null, null);
        SaleOutcome v3 = confirm(ctx, cart3, plan3, null);
        assertTrue(v3.offline());
        assertEquals(1, ctx.outbox.pendingCount());
        log("API fora: venda 3 na fila como " + v3.saleNumber());

        // API volta: o SyncWorker reenvia.
        ctx.api.setBaseUrl(url);
        assertEquals(1, ctx.sync.runOnce());
        assertEquals(0, ctx.outbox.pendingCount());
        log("API de volta: fila reenviada, pendentes = 0");

        // Conferência no servidor: estoque do refri e do café.
        JsonNode produtos = ctx.api.send("GET", "/api/Produto", null, Duration.ofSeconds(20));
        for (JsonNode p : produtos) {
            log("estoque no servidor: " + p.get("productName").asText() + " = " + p.get("productQnt").asText());
        }
        JsonNode vendas = ctx.api.send("GET", "/api/HistoricoVendas", null, Duration.ofSeconds(20));
        log("linhas no histórico do servidor: " + vendas.size());

        // Fechamento
        CaixaStatusDto fechado = ctx.caixa.fechar("118,90", "Fechamento do teste", "Conferência");
        CaixaStatusDto.Session s = fechado.lastSession();
        log("caixa fechado: esperado " + (s == null ? "?" : s.expectedCashAmount()) + ", contado "
                + (s == null ? "?" : s.closingAmount()) + ", diferença " + (s == null ? "?" : s.differenceAmount()));
        assertFalse(fechado.canSell());
    }

    private static Cart cart(AppContext ctx) {
        Cart c = new Cart();
        c.setPromotions(ctx.catalog.promotions());
        return c;
    }

    private static SaleOutcome confirm(AppContext ctx, Cart cart, PaymentPlan plan, Customer customer) {
        return ctx.sales.confirm(cart.pricedItems(), plan, customer, null, ctx.session.user().name(),
                ctx.session.user().id(), ctx.config.deviceId());
    }

    /** Completa os 12 primeiros dígitos com o DV EAN-13. */
    private static String etiquetaBalanca(String doze) {
        for (int dv = 0; dv <= 9; dv++) {
            if (BalancaBarcode.isValidEan13(doze + dv)) {
                return doze + dv;
            }
        }
        throw new IllegalStateException();
    }

    private static void log(String msg) {
        System.out.println("[E2E] " + msg);
    }
}
