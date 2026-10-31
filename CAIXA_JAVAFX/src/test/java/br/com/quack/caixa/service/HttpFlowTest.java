package br.com.quack.caixa.service;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.ApiClient;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.CaixaApi;
import br.com.quack.caixa.api.VendaApi;
import br.com.quack.caixa.config.AppPaths;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.persistence.CacheStore;
import br.com.quack.caixa.persistence.JsonFiles;
import br.com.quack.caixa.persistence.OutboxStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Fluxo HTTP real contra uma API falsa: login, venda online, 503, queda e reenvio da fila. */
class HttpFlowTest {
    private static final String TOKEN = "jwt-teste";

    @TempDir
    Path tmp;

    private HttpServer server;
    private int port;
    private final List<JsonNode> vendasRecebidas = new ArrayList<>();
    private volatile String modoVenda = "ok";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/Auth/login", ex -> {
            // Cookie Secure, como em produção: só volta pelo Bearer em http://.
            ex.getResponseHeaders().add("Set-Cookie", "horuspdv.auth=" + TOKEN + "; path=/; secure; httponly");
            reply(ex, 200, "{\"success\":true,\"message\":\"ok\",\"data\":{\"user\":{\"id\":\"op1\",\"name\":\"Ana\","
                    + "\"email\":\"a@a\",\"role\":\"operador\",\"companyId\":\"emp1\"}}}");
        });
        server.createContext("/api/Caixa/status", ex -> {
            if (!autenticado(ex)) return;
            reply(ex, 200, "{\"success\":true,\"message\":\"\",\"data\":{\"state\":\"aberto\",\"canSell\":true,"
                    + "\"blockReason\":\"\",\"history\":[]}}");
        });
        server.createContext("/api/HistoricoVendas", ex -> {
            if (!autenticado(ex)) return;
            JsonNode body = JsonFiles.MAPPER.readTree(ex.getRequestBody());
            synchronized (vendasRecebidas) {
                vendasRecebidas.add(body);
            }
            if ("503".equals(modoVenda)) {
                reply(ex, 503, "{\"success\":false,\"message\":\"Venda guardada\",\"data\":{\"saleNumber\":\"77\"}}");
            } else if ("500".equals(modoVenda)) {
                reply(ex, 500, "{\"success\":false,\"message\":\"erro\"}");
            } else {
                reply(ex, 201, "{\"success\":true,\"message\":\"ok\",\"data\":{\"saleNumber\":\"1001\","
                        + "\"emitirFiscal\":true}}");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private boolean autenticado(HttpExchange ex) throws IOException {
        if (("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        reply(ex, 401, "{\"success\":false,\"message\":\"Sessao expirada ou token invalido.\"}");
        return false;
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void loginVendaOnline503EReenvioDaFila() {
        ApiClient api = new ApiClient("http://127.0.0.1:" + port + "/");
        AppPaths paths = new AppPaths(tmp);
        OutboxStore outbox = new OutboxStore(paths);
        CacheStore cache = new CacheStore(paths);
        Session session = new Session();
        CaixaService caixa = new CaixaService(new CaixaApi(api), cache, session);
        VendaApi vendaApi = new VendaApi(api);
        SaleService sales = new SaleService(vendaApi, caixa::status, outbox, cache, new CatalogService(null, cache));

        AuthApi.User user = new AuthApi(api).login("a@a", "x");
        assertEquals("Ana", user.name());

        // 1. Venda online: campos JSON no contrato do caixa web.
        SaleOutcome online = vender(sales);
        assertEquals("1001", online.saleNumber());
        assertFalse(online.offline());
        JsonNode body = vendasRecebidas.get(0);
        assertEquals("SALE_CREATED", body.get("eventType").asText());
        assertEquals("20,00", body.get("totalAmount").asText());
        assertEquals("A1", body.at("/items/0/productCode").asText());
        assertEquals(0, body.at("/items/0/itemTotal").decimalValue().compareTo(new BigDecimal("20")));
        assertEquals("pix", body.at("/payments/0/paymentType").asText());
        assertEquals(64, body.get("payloadHash").asText().length());
        assertFalse(body.get("reenvioOffline").asBoolean());

        // 2. 503 com saleNumber = venda gravada no servidor (não vai para a fila).
        modoVenda = "503";
        assertEquals("77", vender(sales).saleNumber());
        assertEquals(0, outbox.pendingCount());

        // 3. 500: vai para a fila com a referência OFF-.
        modoVenda = "500";
        SaleOutcome off = vender(sales);
        assertTrue(off.offline());
        assertTrue(off.saleNumber().startsWith("OFF-"));
        assertEquals(1, outbox.pendingCount());
        String eventIdOriginal = vendasRecebidas.get(2).get("eventId").asText();

        // 4. API volta: o worker reenvia o mesmo evento e esvazia a fila.
        modoVenda = "ok";
        SyncWorker worker = new SyncWorker(api, vendaApi, outbox, () -> true, () -> caixa.status());
        assertEquals(1, worker.runOnce());
        assertEquals(0, outbox.pendingCount());
        JsonNode reenvio = vendasRecebidas.get(3);
        assertEquals(eventIdOriginal, reenvio.get("eventId").asText());
        assertTrue(reenvio.get("reenvioOffline").asBoolean());
    }

    private static SaleOutcome vender(SaleService sales) {
        Cart cart = new Cart();
        cart.add(new Product("id1", "A1", "Cafe", "789", new BigDecimal("10.00"), new BigDecimal("50"), "UN",
                null, null, false, null, 15), new BigDecimal("2"));
        PaymentPlan plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.PIX, null, null, null);
        return sales.confirm(cart.pricedItems(), plan, null, null, "Ana", "op1", "dev-1234");
    }
}
