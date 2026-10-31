package br.com.quack.caixa.service;

import br.com.quack.caixa.api.ApiClient;
import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.VendaApi;
import br.com.quack.caixa.persistence.OutboxStore;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Reenvia a fila offline a cada 15 s (D12). */
public class SyncWorker {

    public record Counts(int pending, int failed) {
    }

    private final ApiClient api;
    private final VendaApi vendaApi;
    private final OutboxStore outbox;
    private final BooleanSupplier loggedIn;
    private final Runnable ping;
    private volatile java.util.function.Consumer<Counts> listener = c -> { };
    /** Envia abertura/fechamento/sangria/reforço feitos offline (null = só vendas). */
    private volatile br.com.quack.caixa.api.CaixaApi caixaApi;
    /** Antes de enviar: garante sessão online (login offline refaz o login quando a API volta). */
    private volatile BooleanSupplier authGuard = () -> true;
    private final ReentrantLock lock = new ReentrantLock();
    private ScheduledExecutorService scheduler;

    public SyncWorker(ApiClient api, VendaApi vendaApi, OutboxStore outbox, BooleanSupplier loggedIn,
            Runnable ping) {
        this.api = api;
        this.vendaApi = vendaApi;
        this.outbox = outbox;
        this.loggedIn = loggedIn;
        this.ping = ping;
    }

    public void setCaixaApi(br.com.quack.caixa.api.CaixaApi caixaApi) {
        this.caixaApi = caixaApi;
    }

    public void setAuthGuard(BooleanSupplier guard) {
        this.authGuard = guard;
    }

    public void onCounts(java.util.function.Consumer<Counts> l) {
        this.listener = l;
    }

    public Counts counts() {
        return new Counts(outbox.pendingCount(), outbox.failedCount());
    }

    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "quack-sync");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeRun, 2, 15, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** Dispara um ciclo imediato (login, F10). */
    public synchronized void triggerNow() {
        if (scheduler != null) {
            scheduler.execute(this::safeRun);
        }
    }

    private void safeRun() {
        try {
            runOnce();
        } catch (RuntimeException ignored) {
            // nunca derruba o agendador
        }
    }

    /** Um ciclo de sincronização. Devolve quantas vendas foram entregues. */
    public int runOnce() {
        if (!lock.tryLock()) {
            return 0;
        }
        int sent = 0;
        try {
            if (!loggedIn.getAsBoolean()) {
                return 0;
            }
            if (!api.apiReachable()) {
                try {
                    ping.run();
                } catch (RuntimeException ignored) {
                    // segue offline
                }
                if (!api.apiReachable()) {
                    return 0;
                }
            }
            if (!authGuard.getAsBoolean()) {
                return 0;
            }
            List<OutboxStore.Entry> pending = outbox.listPending();
            for (OutboxStore.Entry e : pending) {
                try {
                    if (e.cash() != null) {
                        if (caixaApi == null) {
                            break; // sem como enviar o caixa: não pula na frente das vendas seguintes
                        }
                        caixaApi.send(e.cash());
                    } else {
                        vendaApi.register(e.payload().asOfflineResend());
                    }
                    outbox.remove(e.fileName());
                    sent++;
                } catch (ApiException ex) {
                    if (ex.isTransient()) {
                        break;
                    }
                    if (ex.status() == 401) {
                        break;
                    }
                    outbox.moveToFailed(e.fileName(), ex.getMessage());
                }
            }
            return sent;
        } finally {
            lock.unlock();
            listener.accept(counts());
        }
    }
}
