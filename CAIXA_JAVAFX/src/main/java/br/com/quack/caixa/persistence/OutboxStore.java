package br.com.quack.caixa.persistence;

import br.com.quack.caixa.api.dto.CashEvent;
import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.config.AppPaths;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fila offline: vendas e operações de caixa feitas sem conexão (CASH_OPEN, CASH_CLOSE, CASH_MOVEMENT), no banco
 * criptografado ({@link LocalDb}, tabela outbox, espelhada em fila-copia.db). A ordem de gravação é a ordem de
 * reenvio (abertura antes das vendas, etc.). Cada item tem um nome único (instante + evento), usado pelo SyncWorker
 * para remover ou mover para as falhas.
 */
public final class OutboxStore {
    private static final String KIND_SALE = "sale";
    private static final String KIND_CASH = "cash";
    private static long lastStamp;

    /** Item da fila: venda (payload) OU operação de caixa (cash). fileName identifica o item. */
    public record Entry(String fileName, VendaPayload payload, CashEvent cash) {
        public Entry(String fileName, VendaPayload payload) {
            this(fileName, payload, null);
        }

        public boolean isSale() {
            return payload != null;
        }
    }

    /** Item recusado definitivamente pela API: venda (payload) ou operação de caixa (cash). */
    public record Failed(String fileName, VendaPayload payload, CashEvent cash, String error, String failedAt) {
        public Failed(String fileName, VendaPayload payload, String error, String failedAt) {
            this(fileName, payload, null, error, failedAt);
        }
    }

    private final Path root;

    public OutboxStore(AppPaths paths) {
        this.root = paths.root();
    }

    private LocalDb db() {
        return LocalDb.at(root);
    }

    /** Instante crescente: dois eventos no mesmo ms não trocam de ordem. */
    private static synchronized long nextStamp() {
        lastStamp = Math.max(lastStamp + 1, System.currentTimeMillis());
        return lastStamp;
    }

    /** Guarda uma operação de caixa feita sem conexão. */
    public synchronized String enqueueCash(CashEvent event) {
        String name = nextStamp() + "-" + event.eventType() + "-" + event.eventId() + ".json";
        add(name, KIND_CASH, event, "Não foi possível gravar a operação de caixa na fila local.");
        return name;
    }

    public synchronized String enqueue(VendaPayload payload) {
        String name = nextStamp() + "-" + payload.eventId() + ".json";
        add(name, KIND_SALE, payload, "Não foi possível gravar a venda na fila local.");
        return name;
    }

    private void add(String name, String kind, Object value, String error) {
        try {
            db().outboxAdd(name, kind, JsonFiles.MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(error, e);
        } catch (LocalDb.LocalDbException e) {
            throw new UncheckedIOException(error, new java.io.IOException(e.getMessage(), e));
        }
    }

    /** Há operação de caixa ainda não enviada? Enquanto houver, vendas e caixa seguem pela fila (ordem). */
    public synchronized boolean hasPendingCash() {
        return db().outboxHasPending(KIND_CASH);
    }

    /** Vendas ainda na fila (para totais do caixa offline e "Vendas do caixa"). */
    public synchronized List<VendaPayload> pendingSales() {
        return listPending().stream().filter(Entry::isSale).map(Entry::payload).toList();
    }

    public synchronized List<Entry> listPending() {
        List<Entry> out = new ArrayList<>();
        for (LocalDb.OutboxRow r : db().outbox("pending")) {
            try {
                out.add(KIND_CASH.equals(r.kind())
                        ? new Entry(r.name(), null, JsonFiles.MAPPER.readValue(r.body(), CashEvent.class))
                        : new Entry(r.name(), JsonFiles.MAPPER.readValue(r.body(), VendaPayload.class), null));
            } catch (JsonProcessingException e) {
                // item ilegível: vai para as falhas para não travar a fila
                moveToFailed(r.name(), "Item ilegível: " + e.getOriginalMessage());
            }
        }
        return out;
    }

    public synchronized void remove(String fileName) {
        db().outboxRemove(fileName);
    }

    public synchronized void moveToFailed(String fileName, String error) {
        db().outboxFail(fileName, error, Instant.now().toString());
    }

    public synchronized List<Failed> listFailed() {
        List<Failed> out = new ArrayList<>();
        for (LocalDb.OutboxRow r : db().outbox("failed")) {
            VendaPayload p = null;
            CashEvent cash = null;
            try {
                if (KIND_SALE.equals(r.kind())) {
                    p = JsonFiles.MAPPER.readValue(r.body(), VendaPayload.class);
                } else if (KIND_CASH.equals(r.kind())) {
                    cash = JsonFiles.MAPPER.readValue(r.body(), CashEvent.class);
                }
            } catch (JsonProcessingException ignored) {
                // conteúdo ilegível guardado só para consulta
            }
            out.add(new Failed(r.name(), p, cash, r.error() == null ? "" : r.error(),
                    r.failedAt() == null ? "" : r.failedAt()));
        }
        return out;
    }

    /** Devolve o item com falha à fila (na posição original) para o próximo envio. */
    public synchronized boolean retryFailed(String fileName) {
        return db().outboxRetry(fileName);
    }

    /** Tira o item com falha da fila sem enviar (fica guardado no banco local como descartado). */
    public synchronized void discardFailed(String fileName, String who) {
        db().outboxDiscard(fileName, " | Descartado por " + (who == null || who.isBlank() ? "operador" : who),
                Instant.now().toString());
    }

    public synchronized int pendingCount() {
        return db().outboxCount("pending");
    }

    public synchronized int failedCount() {
        return db().outboxCount("failed");
    }
}
