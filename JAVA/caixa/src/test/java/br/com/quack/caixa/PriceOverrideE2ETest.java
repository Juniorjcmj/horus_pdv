package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.PriceAuthApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.SaleOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Alteração de preço com senha de gerente contra a API real (-Dquack.e2e.url=...). */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class PriceOverrideE2ETest {
    @TempDir
    static Path home;

    @Test
    void precoComSenhaDeGerente() {
        System.setProperty("quack.home", home.toString());
        String senha = System.getProperty("quack.e2e.senha", "Teste@12345");
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
        AuthApi.User user = ctx.authApi.login(System.getProperty("quack.e2e.email", "caixa@teste.com"), senha);
        ctx.session.setUser(user);
        ctx.cache.setCompanyId(user.companyId());
        ctx.catalog.load();
        CaixaStatusDto st = ctx.caixa.status().status();
        if (!st.canSell()) {
            ctx.caixa.abrir("100,00");
        }

        List<PriceAuthApi.Supervisor> gerentes = ctx.priceAuthApi.supervisors();
        log("gerentes: " + gerentes);
        assertFalse(gerentes.isEmpty());
        PriceAuthApi.Supervisor g = gerentes.stream().filter(s -> s.id().equals(user.id())).findFirst()
                .orElse(gerentes.get(0));

        // Senha errada: o servidor recusa.
        ApiException errada = assertThrows(ApiException.class,
                () -> ctx.priceAuthApi.authorize(g.id(), "senha-errada", "1001", new BigDecimal("15.00"), null));
        log("senha errada -> " + errada.status() + " " + errada.getMessage());

        // Sem autorização, preço menor é barrado na venda.
        Cart semAut = cart(ctx);
        semAut.applyAuthorizedPrice(0, new BigDecimal("15.00"), null, null);
        DomainException barrada = assertThrows(DomainException.class, () -> vender(ctx, semAut, user));
        log("sem autorização -> " + barrada.getMessage());
        assertTrue(barrada.getMessage().contains("sem autorização de gerente"));

        // Com a senha do gerente: autorização de uso único e venda pelo preço novo.
        PriceAuthApi.Authorization aut = ctx.priceAuthApi.authorize(g.id(), senha, "1001", new BigDecimal("15.00"),
                "Preço da prateleira diferente do sistema.");
        log("autorizado por " + aut.supervisorNome() + ": " + aut.precoTabela() + " -> " + aut.precoNovo()
                + " (expira " + aut.expiraEm() + ")");
        Cart comAut = cart(ctx);
        comAut.applyAuthorizedPrice(0, aut.precoNovo(), aut.autorizacaoId(), aut.supervisorNome());
        SaleOutcome venda = vender(ctx, comAut, user);
        assertFalse(venda.offline());
        JsonNode linhas = ctx.api.send("GET", "/api/HistoricoVendas/" + venda.saleNumber(), null,
                Duration.ofSeconds(20));
        log("venda " + venda.saleNumber() + " registrada; detalhe do servidor: "
                + linhas.toString().substring(0, Math.min(160, linhas.toString().length())));

        // A mesma autorização não vale de novo (uso único).
        Cart reuso = cart(ctx);
        reuso.applyAuthorizedPrice(0, aut.precoNovo(), aut.autorizacaoId(), aut.supervisorNome());
        DomainException reusada = assertThrows(DomainException.class, () -> vender(ctx, reuso, user));
        log("reuso da autorização -> " + reusada.getMessage());
    }

    private static Cart cart(AppContext ctx) {
        Cart c = new Cart();
        c.setPromotions(ctx.catalog.promotions());
        c.add(ctx.catalog.findExact("1001"), BigDecimal.ONE);
        return c;
    }

    private static SaleOutcome vender(AppContext ctx, Cart cart, AuthApi.User user) {
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.PIX, null, null, null);
        return ctx.sales.confirm(cart.pricedItems(), plan, null, null, user.name(), user.id(), ctx.config.deviceId());
    }

    private static void log(String m) {
        System.out.println("[PRECO-E2E] " + m);
    }
}
