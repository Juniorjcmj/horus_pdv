package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.SaleOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Turno inteiro sem internet contra a API real (só roda com -Dquack.e2e.url=...): login offline, abertura,
 * venda, sangria e fechamento offline; depois a API volta e tudo é enviado na ordem, sem duplicar.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class OfflineE2ETest {
    private static final String DEAD = "http://127.0.0.1:1";

    @TempDir
    static Path home;

    @Test
    void turnoInteiroOffline() {
        System.setProperty("quack.home", home.toString());
        String url = System.getProperty("quack.e2e.url");
        String email = System.getProperty("quack.e2e.email", "caixa@teste.com");
        String senha = System.getProperty("quack.e2e.senha", "Teste@12345");

        // 1) Primeiro login online neste terminal: prepara o acesso offline e o cache; caixa começa fechado.
        AppContext online = new AppContext();
        online.api.setBaseUrl(url);
        AuthApi.User user = online.authApi.login(email, senha);
        online.offlineUsers.saveAfterOnlineLogin(user, senha);
        online.session.setUser(user);
        online.cache.setCompanyId(user.companyId());
        online.catalog.load();
        CaixaStatusDto st = online.caixa.status().status();
        if (st.canSell()) {
            String esperado = st.currentSession().expectedCashAmount();
            online.caixa.fechar(esperado == null ? "0,00" : esperado, "fecha antes do teste", "");
        }
        log("preparado: acesso offline salvo, caixa fechado");

        // 2) "Reabre o programa" sem internet: login offline (mesmo fluxo do App).
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(DEAD);
        assertThrows(br.com.quack.caixa.api.ApiException.class, () -> ctx.authApi.login(email, senha));
        AuthApi.User off = ctx.offlineUsers.authenticate(email, senha);
        ctx.session.setOfflineAuth(email, senha);
        ctx.session.setUser(off);
        ctx.cache.setCompanyId(off.companyId());
        assertTrue(ctx.catalog.load().fromCache());
        log("login offline ok: " + off.name());

        // 3) Abre, vende, sangra e fecha sem conexão.
        CaixaStatusDto aberto = ctx.caixa.abrir("50,00");
        assertTrue(ctx.caixa.lastWriteOffline());
        assertTrue(aberto.canSell());
        String sessaoOffline = aberto.currentSession().id();
        log("caixa aberto offline: " + sessaoOffline);

        Cart cart = new Cart();
        cart.setPromotions(ctx.catalog.promotions());
        cart.add(ctx.catalog.findExact("1001"), BigDecimal.ONE);
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.DINHEIRO, cart.total(), new BigDecimal("20.00"), null);
        SaleOutcome venda = ctx.sales.confirm(cart.pricedItems(), plan, null, null, off.name(), off.id(),
                ctx.config.deviceId());
        assertTrue(venda.offline());
        log("venda offline: " + venda.saleNumber() + " (R$ 18,90 em dinheiro, troco R$ 1,10)");

        CaixaStatusDto comSangria = ctx.caixa.movimento("Sangria", "5,00", "Teste offline");
        assertTrue(ctx.caixa.lastWriteOffline());
        assertEquals("63,90", comSangria.currentSession().expectedCashAmount(), "50 + 18,90 - 5");

        CaixaStatusDto fechado = ctx.caixa.fechar("63,90", "Fechamento offline", "");
        assertFalse(fechado.canSell());
        assertEquals("0,00", fechado.lastSession().differenceAmount());
        assertEquals(4, ctx.outbox.pendingCount(), "abertura, venda, sangria e fechamento na fila");
        log("sangria e fechamento offline; fila com " + ctx.outbox.pendingCount() + " itens");

        // 4) A API volta: o SyncWorker refaz o login sozinho e envia tudo, na ordem.
        ctx.api.setBaseUrl(url);
        int enviados = ctx.sync.runOnce();
        log("sincronizados: " + enviados + " | pendentes " + ctx.outbox.pendingCount() + " | falhas "
                + ctx.outbox.failedCount());
        ctx.outbox.listFailed().forEach(f -> log("FALHA: " + f.fileName() + " -> " + f.error()));
        assertEquals(4, enviados);
        assertEquals(0, ctx.outbox.failedCount());
        assertFalse(ctx.session.offlineAuth(), "sessão passou a ser online");

        // 5) Conferência no servidor: o turno é o mesmo id, a venda está nele e o fechamento bateu.
        CaixaStatusDto srv = ctx.caixa.status().status();
        assertFalse(srv.canSell());
        CaixaStatusDto.Session last = srv.lastSession();
        log("servidor: último turno " + last.id() + " esperado " + last.expectedCashAmount() + " contado "
                + last.closingAmount() + " diferença " + last.differenceAmount());
        assertEquals(sessaoOffline, last.id());
        JsonNode vendas = ctx.api.send("GET", "/api/HistoricoVendas?caixaSessaoId=" + sessaoOffline, null,
                Duration.ofSeconds(20));
        log("vendas no turno offline (servidor): " + vendas.size() + " linha(s)");
        assertTrue(vendas.size() >= 1);

        // 6) Reenviar de novo não duplica nada (mesmos eventId).
        assertEquals(0, ctx.sync.runOnce());
    }

    private static void log(String m) {
        System.out.println("[OFFLINE-E2E] " + m);
    }
}
