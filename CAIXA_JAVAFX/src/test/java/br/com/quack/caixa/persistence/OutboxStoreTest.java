package br.com.quack.caixa.persistence;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.config.AppPaths;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxStoreTest {
    @TempDir
    Path tmp;

    private VendaPayload sale(String eventId) {
        return new VendaPayload("cs-" + eventId, eventId, "SALE_CREATED", "2026-01-01T00:00:00Z", "OFF-1", "hash",
                true, "Consumidor", "-", "dinheiro", "10,00", "Ana", "op",
                List.of(new VendaPayload.Item("A", "Item", new BigDecimal("1"), new BigDecimal("10.00"),
                        new BigDecimal("0.00"), new BigDecimal("10.00"), null)),
                List.of(new VendaPayload.Payment("dinheiro", new BigDecimal("10.00"), new BigDecimal("10.00"),
                        new BigDecimal("0.00"))));
    }

    @Test
    void enfileiraListaEmOrdemERemove() throws Exception {
        OutboxStore store = new OutboxStore(new AppPaths(tmp));
        store.enqueue(sale("e1"));
        Thread.sleep(5);
        store.enqueue(sale("e2"));
        List<OutboxStore.Entry> list = store.listPending();
        assertEquals(2, list.size());
        assertEquals("e1", list.get(0).payload().eventId());
        assertEquals("e2", list.get(1).payload().eventId());
        assertEquals(2, store.pendingCount());

        store.remove(list.get(0).fileName());
        assertEquals(1, store.pendingCount());
    }

    @Test
    void movePraFalhasComMensagem() {
        OutboxStore store = new OutboxStore(new AppPaths(tmp));
        String name = store.enqueue(sale("e3"));
        store.moveToFailed(name, "Estoque insuficiente");
        assertEquals(0, store.pendingCount());
        assertEquals(1, store.failedCount());
        OutboxStore.Failed f = store.listFailed().get(0);
        assertEquals("Estoque insuficiente", f.error());
        assertEquals("e3", f.payload().eventId());
    }

    @Test
    void reenviaFalhaNaPosicaoOriginalEDescartaSemApagar() throws Exception {
        OutboxStore store = new OutboxStore(new AppPaths(tmp));
        String primeira = store.enqueue(sale("f1"));
        Thread.sleep(5);
        store.enqueue(sale("f2"));
        store.moveToFailed(primeira, "Produto não encontrado");
        assertEquals(1, store.failedCount());

        // Reenviar: volta para a fila na frente (seq original), sem o erro.
        assertTrue(store.retryFailed(primeira));
        assertEquals(0, store.failedCount());
        assertEquals("f1", store.listPending().get(0).payload().eventId());
        assertFalse(store.retryFailed(primeira), "só reenvia o que está em falha");

        // Descartar: sai da fila e dos avisos, mas o registro continua no banco local.
        store.moveToFailed(primeira, "Recusado de novo");
        store.discardFailed(primeira, "Ana");
        assertEquals(0, store.failedCount());
        assertEquals(1, store.pendingCount());
        var guardado = LocalDb.at(tmp).outbox("discarded");
        assertEquals(1, guardado.size());
        assertTrue(guardado.get(0).error().contains("Descartado por Ana"));
    }

    @Test
    void sobreviveReabertura() {
        new OutboxStore(new AppPaths(tmp)).enqueue(sale("e4"));
        assertEquals(1, new OutboxStore(new AppPaths(tmp)).pendingCount());
    }
}
