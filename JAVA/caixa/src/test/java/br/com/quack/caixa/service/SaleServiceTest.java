package br.com.quack.caixa.service;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.VendaApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.config.AppPaths;
import br.com.quack.caixa.domain.Cart;
import br.com.quack.caixa.domain.DomainException;
import br.com.quack.caixa.domain.PaymentPlan;
import br.com.quack.caixa.domain.PaymentType;
import br.com.quack.caixa.domain.Product;
import br.com.quack.caixa.domain.SaleOutcome;
import br.com.quack.caixa.persistence.CacheStore;
import br.com.quack.caixa.persistence.OutboxStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SaleServiceTest {
    @TempDir
    Path tmp;

    private OutboxStore outbox;
    private CacheStore cache;
    private Cart cart;
    private PaymentPlan plan;

    static class FakeVenda extends VendaApi {
        ApiException error;
        int calls;

        FakeVenda() {
            super(null);
        }

        @Override
        public Result register(VendaPayload payload) {
            calls++;
            if (error != null) {
                throw error;
            }
            return new Result("1001", false, null);
        }
    }

    @BeforeEach
    void setUp() {
        AppPaths paths = new AppPaths(tmp);
        outbox = new OutboxStore(paths);
        cache = new CacheStore(paths);
        cart = new Cart();
        cart.add(new Product("id1", "A1", "Cafe", "", new BigDecimal("10.00"), new BigDecimal("5"), "UN", null,
                null, false, null, 15), new BigDecimal("2"));
        plan = new PaymentPlan(cart.total());
        plan.finalizeWith(PaymentType.PIX, null, null, null);
    }

    private SaleService service(FakeVenda venda, boolean canSell, boolean offline) {
        CaixaStatusDto st = new CaixaStatusDto(canSell ? "aberto" : "fechado", canSell,
                canSell ? "" : "Caixa fechado", "", null, null);
        return new SaleService(venda, () -> new CaixaService.StatusResult(st, offline), outbox, cache,
                new CatalogService(null, cache));
    }

    private SaleOutcome confirm(SaleService s) {
        return s.confirm(cart.pricedItems(), plan, null, null, "Ana", "op1", "dev-1234");
    }

    @Test
    void sucessoNaoEnfileira() {
        FakeVenda venda = new FakeVenda();
        SaleOutcome o = confirm(service(venda, true, false));
        assertEquals("1001", o.saleNumber());
        assertFalse(o.offline());
        assertEquals(0, outbox.pendingCount());
        assertEquals(1, venda.calls);
        assertTrue(cache.lastReceipt().isPresent());
    }

    @Test
    void erro4xxNaoEnfileiraEMantemMensagem() {
        FakeVenda venda = new FakeVenda();
        venda.error = new ApiException(400, "Estoque insuficiente para Cafe");
        DomainException e = assertThrows(DomainException.class, () -> confirm(service(venda, true, false)));
        assertEquals("Estoque insuficiente para Cafe", e.getMessage());
        assertEquals(0, outbox.pendingCount());
    }

    @Test
    void erroDeRedeEnfileira() {
        FakeVenda venda = new FakeVenda();
        venda.error = new ApiException(0, "Sem conexão");
        SaleOutcome o = confirm(service(venda, true, false));
        assertTrue(o.offline());
        assertTrue(o.saleNumber().startsWith("OFF-"));
        assertEquals(1, outbox.pendingCount());
        assertTrue(outbox.listPending().get(0).payload().reenvioOffline());
    }

    @Test
    void statusOfflineEnfileiraSemChamarApi() {
        FakeVenda venda = new FakeVenda();
        SaleOutcome o = confirm(service(venda, true, true));
        assertTrue(o.offline());
        assertEquals(0, venda.calls);
        assertEquals(1, outbox.pendingCount());
    }

    @Test
    void caixaFechadoBloqueia() {
        FakeVenda venda = new FakeVenda();
        DomainException e = assertThrows(DomainException.class, () -> confirm(service(venda, false, false)));
        assertEquals("Caixa fechado", e.getMessage());
        assertEquals(0, venda.calls);
    }

    @Test
    void syncWorkerReenviaERemove() {
        FakeVenda venda = new FakeVenda();
        venda.error = new ApiException(0, "Sem conexão");
        confirm(service(venda, true, false));
        confirm(service(venda, true, false));
        assertEquals(2, outbox.pendingCount());

        venda.error = null;
        br.com.quack.caixa.api.ApiClient api = new br.com.quack.caixa.api.ApiClient("http://localhost:1");
        SyncWorker worker = new SyncWorker(api, venda, outbox, () -> true, () -> { });
        assertEquals(2, worker.runOnce());
        assertEquals(0, outbox.pendingCount());

        confirm(service(venda, true, true));
        venda.error = new ApiException(409, "Conflito de idempotência");
        assertEquals(0, worker.runOnce());
        assertEquals(0, outbox.pendingCount());
        assertEquals(1, outbox.failedCount());
        assertEquals(List.of(), List.of());
    }
}
