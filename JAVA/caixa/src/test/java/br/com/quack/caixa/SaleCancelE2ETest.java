package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.PriceAuthApi;
import br.com.quack.caixa.api.SaleHistoryApi;
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
 * Cancelamento de venda pelo OPERADOR com a senha do gerente, contra a API real, conferindo a trilha de auditoria
 * (-Dquack.e2e.url=... -Dquack.e2e.operador=... -Dquack.e2e.operadorSenha=...).
 */
@EnabledIfSystemProperty(named = "quack.e2e.operador", matches = ".+")
class SaleCancelE2ETest {
    @TempDir
    static Path home;

    @Test
    void operadorCancelaComSenhaDoGerenteETudoFicaNaTrilha() {
        System.setProperty("quack.home", home.toString());
        String url = System.getProperty("quack.e2e.url");
        String senhaGerente = System.getProperty("quack.e2e.senha", "Teste@12345");
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(url);
        AuthApi.User op = ctx.authApi.login(System.getProperty("quack.e2e.operador"),
                System.getProperty("quack.e2e.operadorSenha"));
        ctx.session.setUser(op);
        ctx.cache.setCompanyId(op.companyId());
        ctx.catalog.load();
        if (!ctx.caixa.status().status().canSell()) {
            ctx.caixa.abrir("50,00");
        }
        BigDecimal estoqueAntes = ctx.catalog.findExact("1001").stock();

        Cart cart = new Cart();
        cart.add(ctx.catalog.findExact("1001"), new BigDecimal("2"));
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.DINHEIRO, cart.total(), new BigDecimal("50.00"), null);
        SaleOutcome venda = ctx.sales.confirm(cart.pricedItems(), plan, null, null, op.name(), op.id(),
                ctx.config.deviceId());
        String n = venda.saleNumber();
        log("venda #" + n + " R$ " + venda.payload().totalAmount() + " (2x café)");

        PriceAuthApi.Supervisor g = ctx.priceAuthApi.supervisors().get(0);
        ApiException errada = assertThrows(ApiException.class,
                () -> ctx.saleHistoryApi.cancel(n, g.id(), "senha-errada", "Cliente desistiu.", ctx.config.deviceId()));
        log("senha errada -> " + errada.status() + " " + errada.getMessage());
        assertEquals(403, errada.status());

        // No ambiente de teste a NFC-e fica parada na fila (sem certificado): a venda cancela mesmo assim.
        SaleHistoryApi.CancelResult r = ctx.saleHistoryApi.cancel(n, g.id(), senhaGerente, "Cliente desistiu.",
                ctx.config.deviceId());
        log("cancelada em " + r.canceladoEm() + " por " + r.supervisorNome() + " | itens estornados "
                + r.itensEstornados());
        assertEquals(g.name(), r.supervisorNome());
        assertEquals(1, r.itensEstornados());

        // A fila fiscal tira a nota da fila sem emitir (sem certificado: aviso para inutilizar o número).
        // No servidor da loja (-Dquack.e2e.modoLoja=true) esse ramo e do worker .NET da nuvem (D8 da 4b): nao se espera aqui.
        boolean modoLoja = Boolean.getBoolean("quack.e2e.modoLoja");
        String status = modoLoja ? "4|não emitida" : "";
        for (int i = 0; i < 20 && !status.startsWith("4"); i++) {
            status = sqlOut("SET NOCOUNT ON; SELECT CAST(d.Status AS VARCHAR(2)) + '|' + ISNULL(d.MotivoStatus, '') "
                    + "FROM DocumentosFiscais d JOIN Vendas v ON v.Id = d.VendaId WHERE v.SaleNumber = '" + n + "'");
            if (!status.startsWith("4")) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log("nota na fila depois do cancelamento -> " + status);
        assertTrue(status.startsWith("4|"), status);
        assertTrue(status.contains("não emitida"), status);

        ApiException dupla = assertThrows(ApiException.class,
                () -> ctx.saleHistoryApi.cancel(n, g.id(), senhaGerente, "De novo.", ctx.config.deviceId()));
        log("cancelar de novo -> " + dupla.status() + " " + SaleHistoryApi.cancelCode(dupla));
        assertEquals(409, dupla.status());
        assertEquals("JA_CANCELADA", SaleHistoryApi.cancelCode(dupla));

        ctx.catalog.load();
        assertEquals(0, estoqueAntes.compareTo(ctx.catalog.findExact("1001").stock()), "estoque voltou");

        // Trilha (só gerente/admin): entra como gerente para consultar.
        AppContext adm = new AppContext();
        adm.api.setBaseUrl(url);
        adm.authApi.login(System.getProperty("quack.e2e.email", "caixa@teste.com"), senhaGerente);
        JsonNode trilha = adm.api.send("GET", "/api/HistoricoVendas/cancelamentos/auditoria?venda=" + n, null,
                Duration.ofSeconds(20));
        for (JsonNode t : trilha) {
            log("trilha: " + t.path("resultado").asText() + " | " + t.path("motivoRecusa").asText("-") + " | gerente "
                    + t.path("supervisorNome").asText("-") + " | terminal " + t.path("terminal").asText("-") + " | ip "
                    + t.path("ip").asText("-") + " | turno " + t.path("caixaSessaoStatus").asText("-") + " | itens "
                    + t.path("snapshot").path("itens").size() + " | pagamentos "
                    + t.path("snapshot").path("pagamentos").size());
        }
        assertEquals(3, trilha.size(), "senha errada + cancelada + segunda tentativa");
        long negadas = 0;
        JsonNode ok = null;
        for (JsonNode t : trilha) {
            if ("negada".equals(t.path("resultado").asText())) {
                negadas++;
            } else {
                ok = t;
            }
        }
        assertEquals(2, negadas);
        assertNotNull(ok);
        assertEquals(op.name(), ok.path("operadorNome").asText());
        assertEquals(ctx.config.deviceId(), ok.path("terminal").asText());
        assertEquals("aberta", ok.path("caixaSessaoStatus").asText());
        assertEquals(1, ok.path("snapshot").path("itens").size());
        assertEquals(1, ok.path("snapshot").path("pagamentos").size());
        assertEquals("Cliente desistiu.", ok.path("justificativa").asText());
    }

    /** Consulta o SQL do container de teste (-Dquack.e2e.docker=pdv-teste-sql) e devolve a saída. */
    private static String sqlOut(String command) {
        String container = System.getProperty("quack.e2e.docker", "pdv-teste-sql");
        try {
            Process p = new ProcessBuilder("docker", "exec", container, "/opt/mssql-tools18/bin/sqlcmd", "-S",
                    "localhost", "-U", "sa", "-P", System.getProperty("quack.e2e.saPassword", "Teste#Caixa2026"),
                    "-C", "-I", "-b", "-h", "-1", "-W", "-d", "HorusPdv", "-Q", command).redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            assertEquals(0, p.waitFor(), out);
            return out;
        } catch (java.io.IOException | InterruptedException e) {
            throw new AssertionError("docker exec falhou: " + e.getMessage(), e);
        }
    }

    private static void log(String m) {
        System.out.println("[CANCEL-E2E] " + m);
    }
}
