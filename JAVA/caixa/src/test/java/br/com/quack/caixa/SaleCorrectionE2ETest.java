package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.PriceAuthApi;
import br.com.quack.caixa.api.SaleHistoryApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.SaleOutcome;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Correção de venda autorizada pela senha do gerente, feita por um OPERADOR sem perfil financeiro, contra a
 * API real (-Dquack.e2e.url=... -Dquack.e2e.operador=... -Dquack.e2e.operadorSenha=...).
 */
@EnabledIfSystemProperty(named = "quack.e2e.operador", matches = ".+")
class SaleCorrectionE2ETest {
    @TempDir
    static Path home;

    @Test
    void operadorCorrigeComSenhaDoGerente() {
        System.setProperty("quack.home", home.toString());
        String senhaGerente = System.getProperty("quack.e2e.senha", "Teste@12345");
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
        AuthApi.User op = ctx.authApi.login(System.getProperty("quack.e2e.operador"),
                System.getProperty("quack.e2e.operadorSenha"));
        log("logado como " + op.name() + " (" + op.role() + ")");
        ctx.session.setUser(op);
        ctx.cache.setCompanyId(op.companyId());
        ctx.catalog.load();
        CaixaStatusDto st = ctx.caixa.status().status();
        if (!st.canSell()) {
            ctx.caixa.abrir("50,00");
        }

        // Venda: café (18,90) + refrigerante (5,00), em dinheiro com R$ 30,00 (troco 6,10).
        Cart cart = new Cart();
        cart.setPromotions(ctx.catalog.promotions());
        cart.add(ctx.catalog.findExact("1001"), BigDecimal.ONE);
        cart.add(ctx.catalog.findExact("2002"), BigDecimal.ONE);
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.DINHEIRO, cart.total(), new BigDecimal("30.00"), null);
        SaleOutcome venda = ctx.sales.confirm(cart.pricedItems(), plan, null, null, op.name(), op.id(),
                ctx.config.deviceId());
        log("venda #" + venda.saleNumber() + " R$ " + venda.payload().totalAmount() + " (recebido 30,00, troco 6,10)");

        ApiException semGerente = assertThrows(ApiException.class, () -> ctx.saleHistoryApi.correct(
                venda.saleNumber(), List.of("2002"), "Item lançado por engano.", null, null));
        log("sem gerente -> " + semGerente.status() + " " + semGerente.getMessage());
        assertEquals(403, semGerente.status());

        PriceAuthApi.Supervisor g = ctx.priceAuthApi.supervisors().get(0);
        ApiException errada = assertThrows(ApiException.class, () -> ctx.saleHistoryApi.correct(
                venda.saleNumber(), List.of("2002"), "Item lançado por engano.", g.id(), "senha-errada"));
        log("senha do gerente errada -> " + errada.status() + " " + errada.getMessage());
        assertEquals(403, errada.status());

        SaleHistoryApi.CorrectResult r = ctx.saleHistoryApi.correct(venda.saleNumber(), List.of("2002"),
                "Item lançado por engano.", g.id(), senhaGerente);
        log("com a senha de " + g.name() + " -> nova venda #" + r.novaVendaNumber() + " R$ " + r.novoTotal()
                + " | responsável: " + r.financeiroNome());
        assertEquals("18,90", r.novoTotal());
        assertEquals(g.name(), r.financeiroNome());

        // Troco da venda nova: recebido = valor, troco zero (antes: recebido proporcional 23,72 e troco 0).
        SaleHistoryApi.SaleDetail nova = ctx.saleHistoryApi.details(r.novaVendaNumber());
        SaleHistoryApi.Payment p = nova.payments().get(0);
        log("pagamento da nova venda (servidor): " + p.paymentType() + " valor " + p.amount() + " recebido "
                + p.cashGiven() + " troco " + p.changeAmount());
        assertEquals(0, p.amount().compareTo(p.cashGiven()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.changeAmount()));
        VendaPayload.Payment cupom = SaleHistoryApi.toOutcome(nova, false).payload().payments().get(0);
        assertEquals(0, new BigDecimal("18.90").compareTo(cupom.cashGiven()));
        assertEquals(0, BigDecimal.ZERO.compareTo(cupom.changeAmount()));
    }

    private static void log(String m) {
        System.out.println("[CORRECAO-E2E] " + m);
    }
}
