package br.com.quack.caixa.service;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.CaixaApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.api.dto.CashEvent;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.persistence.CacheStore;
import br.com.quack.caixa.persistence.OutboxStore;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Status, abertura, fechamento, sangria e reforço do caixa (porta de useCashRegisterActions.ts).
 * <p>
 * Tenta o servidor; se falhar POR CONEXÃO (sem rede, timeout, 408/429, 5xx) grava localmente e enfileira o
 * evento com o MESMO eventId para sincronizar depois. Recusa de regra de negócio (4xx) vai para o operador.
 * Enquanto houver operação de caixa na fila, tudo segue pela fila e o status vem do cache: assim a abertura
 * offline chega à nuvem antes das vendas e do fechamento, na ordem em que aconteceram.
 */
public class CaixaService {

    /** offline = status veio do cache local (pode ser null se nunca houve status). */
    public record StatusResult(CaixaStatusDto status, boolean offline) {
    }

    private final CaixaApi api;
    private final CacheStore cache;
    private final Session session;
    private final OutboxStore outbox;
    private final Supplier<Instant> clock;
    private volatile boolean lastWriteOffline;

    public CaixaService(CaixaApi api, CacheStore cache, Session session) {
        this(api, cache, session, null, Instant::now);
    }

    public CaixaService(CaixaApi api, CacheStore cache, Session session, OutboxStore outbox,
            Supplier<Instant> clock) {
        this.api = api;
        this.cache = cache;
        this.session = session;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** A última abertura/fechamento/movimento ficou guardada localmente (sem conexão)? */
    public boolean lastWriteOffline() {
        return lastWriteOffline;
    }

    // ---------- status ----------

    public StatusResult status() {
        if (hasPendingCash()) {
            return local();
        }
        try {
            CaixaStatusDto s = api.status();
            cache.saveCaixaStatus(s);
            session.setCaixaStatus(s, false);
            return new StatusResult(s, false);
        } catch (ApiException e) {
            if (!session.treatAsOffline(e)) {
                throw e;
            }
            return local();
        }
    }

    /** Status do cache com tempo decorrido e totais "ao vivo" (servidor + vendas na fila + movimentos). */
    private StatusResult local() {
        CaixaStatusDto live = LocalCash.live(cache.caixaStatus().orElse(null), clock.get(), pendingSales());
        session.setCaixaStatus(live, true);
        return new StatusResult(live, true);
    }

    // ---------- operações ----------

    public CaixaStatusDto abrir(String openingAmount) {
        String eventId = CaixaApi.newEventId("ev-cxopen");
        if (!hasPendingCash()) {
            try {
                return online(api.abrir(openingAmount, eventId, null, null));
            } catch (ApiException e) {
                rethrowUnlessConnectivity(e);
            }
        }
        Instant now = clock.get();
        AuthApi.User u = session.user();
        CaixaStatusDto opened = LocalCash.open(cache.caixaStatus().orElse(null), openingAmount,
                u == null ? "" : u.id(), u == null ? "" : u.name(), now);
        String sessionId = opened.currentSession().id();
        enqueue(CashEvent.open(eventId, null, sessionId, now.toString(), opened.currentSession().openingAmount()));
        return offline(opened);
    }

    public CaixaStatusDto fechar(String closingAmount, String note, String differenceReason) {
        String eventId = CaixaApi.newEventId("ev-cxclose");
        if (!hasPendingCash()) {
            try {
                return online(api.fechar(closingAmount, note, differenceReason, eventId, null, null));
            } catch (ApiException e) {
                rethrowUnlessConnectivity(e);
            }
        }
        Instant now = clock.get();
        CaixaStatusDto cached = cache.caixaStatus().orElse(null);
        AuthApi.User u = session.user();
        CaixaStatusDto closed = LocalCash.close(cached, closingAmount, note, differenceReason,
                u == null ? "" : u.name(), now, pendingSales());
        enqueue(CashEvent.close(eventId, null, closed.lastSession().id(), now.toString(), closingAmount, note,
                differenceReason));
        return offline(closed);
    }

    /** tipo: "Sangria" ou "Reforco". */
    public CaixaStatusDto movimento(String tipo, String valor, String motivo) {
        String eventId = CaixaApi.newEventId("ev-cxmov");
        if (!hasPendingCash()) {
            try {
                return online(api.movimento(tipo, valor, motivo, eventId));
            } catch (ApiException e) {
                rethrowUnlessConnectivity(e);
            }
        }
        Instant now = clock.get();
        AuthApi.User u = session.user();
        CaixaStatusDto base = LocalCash.movement(cache.caixaStatus().orElse(null), tipo, valor, motivo,
                u == null ? "" : u.name(), now);
        enqueue(CashEvent.movement(eventId, null, base.currentSession().id(), now.toString(), tipo, valor, motivo));
        return offline(base);
    }

    // ---------- utilitários ----------

    private CaixaStatusDto online(CaixaStatusDto s) {
        lastWriteOffline = false;
        if (s != null) {
            cache.saveCaixaStatus(s);
            session.setCaixaStatus(s, false);
        }
        return s;
    }

    /** Grava o status BASE no cache e devolve o "ao vivo" para a tela. */
    private CaixaStatusDto offline(CaixaStatusDto base) {
        lastWriteOffline = true;
        cache.saveCaixaStatus(base);
        CaixaStatusDto live = LocalCash.live(base, clock.get(), pendingSales());
        session.setCaixaStatus(live, true);
        return live;
    }

    private void enqueue(CashEvent e) {
        if (outbox == null) {
            throw new br.com.quack.caixa.domain.DomainException("A operação de caixa exige conexão com a API.");
        }
        outbox.enqueueCash(e);
    }

    private boolean hasPendingCash() {
        return outbox != null && outbox.hasPendingCash();
    }

    private List<br.com.quack.caixa.api.dto.VendaPayload> pendingSales() {
        return outbox == null ? List.of() : outbox.pendingSales();
    }

    /** Só falha de conexão vai para o modo offline (isConnectivityError da web). */
    private void rethrowUnlessConnectivity(ApiException e) {
        if (!session.treatAsOffline(e) || outbox == null) {
            throw e;
        }
    }
}
