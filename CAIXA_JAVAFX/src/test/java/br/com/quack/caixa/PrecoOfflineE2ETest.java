package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.persistence.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Venda offline com preço abaixo do cadastro e sem senha de gerente (cache do caixa adulterado). A API aceita a
 * venda (ela já aconteceu), mas grava o evento PrecoOfflineAbaixo no Log de Atividades. Só roda com
 * -Dquack.e2e.url=...; a conferência do log é feita no SQL (ver README).
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class PrecoOfflineE2ETest {
    private static final String DEAD = "http://127.0.0.1:1";

    @TempDir
    static Path home;

    @Test
    void vendaOfflineAbaixoDoPrecoFicaNoLog() {
        System.setProperty("quack.home", home.toString());
        String url = System.getProperty("quack.e2e.url");
        String email = System.getProperty("quack.e2e.email", "caixa@teste.com");
        String senha = System.getProperty("quack.e2e.senha", "Teste@12345");

        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(url);
        AuthApi.User user = ctx.authApi.login(email, senha);
        ctx.session.setUser(user);
        ctx.cache.setCompanyId(user.companyId());
        ctx.catalog.load();
        if (!ctx.caixa.status().status().canSell()) {
            ctx.caixa.abrir("50,00");
        }

        // Cache adulterado: café de R$ 18,90 por R$ 9,90.
        List<Product> produtos = new ArrayList<>();
        for (Product p : ctx.cache.products().orElseThrow()) {
            if (p.code().equals("1001")) {
                ObjectNode n = JsonFiles.MAPPER.valueToTree(p);
                n.put("price", new BigDecimal("9.90"));
                p = JsonFiles.MAPPER.convertValue(n, Product.class);
            }
            produtos.add(p);
        }
        ctx.cache.saveProducts(produtos);

        ctx.api.setBaseUrl(DEAD);
        assertTrue(ctx.catalog.load().fromCache());
        Cart cart = new Cart();
        cart.add(ctx.catalog.findExact("1001"), BigDecimal.ONE);
        assertEquals(0, new BigDecimal("9.90").compareTo(cart.total()));
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.DINHEIRO, cart.total(), new BigDecimal("10.00"), null);
        SaleOutcome venda = ctx.sales.confirm(cart.pricedItems(), plan, null, null, user.name(), user.id(),
                ctx.config.deviceId());
        assertTrue(venda.offline());

        ctx.api.setBaseUrl(url);
        int enviados = ctx.sync.runOnce();
        ctx.outbox.listFailed().forEach(f -> System.out.println("[PRECO-E2E] FALHA " + f.error()));
        assertEquals(1, enviados, "venda offline aceita pela API");
        assertEquals(0, ctx.outbox.failedCount());
        System.out.println("[PRECO-E2E] venda offline a R$ 9,90 sincronizada (offline ref " + venda.saleNumber() + ")");
    }
}
