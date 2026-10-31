package br.com.quack.caixa;

import br.com.quack.caixa.api.ApiClient;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.CaixaApi;
import br.com.quack.caixa.api.CatalogApi;
import br.com.quack.caixa.api.VendaApi;
import br.com.quack.caixa.config.AppConfig;
import br.com.quack.caixa.config.AppPaths;
import br.com.quack.caixa.persistence.CacheStore;
import br.com.quack.caixa.persistence.OutboxStore;
import br.com.quack.caixa.service.CaixaService;
import br.com.quack.caixa.service.CatalogService;
import br.com.quack.caixa.service.SaleService;
import br.com.quack.caixa.service.Session;
import br.com.quack.caixa.service.SyncWorker;

/** Liga todas as peças do caixa (equivalente a um container de injecao simples). */
public final class AppContext {
    public final AppPaths paths;
    public final AppConfig config;
    public final ApiClient api;
    public final Session session = new Session();
    public final CacheStore cache;
    public final OutboxStore outbox;
    public final AuthApi authApi;
    public final CaixaApi caixaApi;
    public final VendaApi vendaApi;
    public final br.com.quack.caixa.api.FiscalApi fiscalApi;
    public final br.com.quack.caixa.api.PriceAuthApi priceAuthApi;
    public final br.com.quack.caixa.api.SaleHistoryApi saleHistoryApi;
    public final CatalogService catalog;
    public final CaixaService caixa;
    public final SaleService sales;
    public final SyncWorker sync;
    public final br.com.quack.caixa.persistence.OfflineUserStore offlineUsers;
    /** Chamado quando o login silencioso é recusado (ex.: senha trocada no servidor). */
    private volatile Runnable onRevalidationFailed = () -> { };

    public AppContext() {
        this.paths = AppPaths.defaults();
        this.config = new AppConfig(paths);
        this.api = new ApiClient(config.apiBaseUrl());
        // Abre o banco criptografado já na partida (migra os JSON antigos e recupera a fila da cópia se preciso).
        br.com.quack.caixa.persistence.LocalDb.at(paths.root());
        this.cache = new CacheStore(paths);
        this.outbox = new OutboxStore(paths);
        this.offlineUsers = new br.com.quack.caixa.persistence.OfflineUserStore(paths.root());
        this.offlineUsers.setMaxOfflineDays(config.offlineMaxDays());
        this.authApi = new AuthApi(api);
        this.caixaApi = new CaixaApi(api);
        this.vendaApi = new VendaApi(api);
        this.fiscalApi = new br.com.quack.caixa.api.FiscalApi(api);
        this.priceAuthApi = new br.com.quack.caixa.api.PriceAuthApi(api);
        this.saleHistoryApi = new br.com.quack.caixa.api.SaleHistoryApi(api);
        this.catalog = new CatalogService(new CatalogApi(api), cache);
        this.caixa = new CaixaService(caixaApi, cache, session, outbox, java.time.Instant::now);
        this.sales = new SaleService(vendaApi, caixa::status, outbox, cache, catalog);
        this.sync = new SyncWorker(api, vendaApi, outbox, session::loggedIn, this::ping);
        this.sync.setCaixaApi(caixaApi);
        this.sync.setAuthGuard(this::ensureOnlineSession);
    }

    /** O servidor recusou a senha ou o usuário (401/403): o acesso offline desse usuário deixa de valer. */
    public static boolean rejectsCredentials(br.com.quack.caixa.api.ApiException e) {
        return e.status() == 401 || e.status() == 403;
    }

    public void onRevalidationFailed(Runnable r) {
        this.onRevalidationFailed = r;
    }

    /**
     * Testa se a API voltou. Com operação de caixa na fila (ou login offline) o status não pode vir do servidor
     * ainda, então só "toca" a API; senão atualiza o status do caixa normalmente.
     */
    private void ping() {
        try {
            if (session.offlineAuth() || outbox.hasPendingCash()) {
                caixaApi.status();
            } else {
                caixa.status();
            }
        } catch (RuntimeException ignored) {
            // continua offline (401 na sessão offline também prova que a API respondeu)
        }
    }

    /**
     * Login offline: quando a API volta, refaz o login com as credenciais em memória antes de sincronizar.
     * Devolve false enquanto não há sessão online (o SyncWorker tenta de novo no próximo ciclo).
     */
    public synchronized boolean ensureOnlineSession() {
        if (!session.offlineAuth()) {
            return true;
        }
        String email = session.reloginEmail();
        String password = session.reloginPassword();
        if (email == null || password == null) {
            return false;
        }
        try {
            AuthApi.User user = authApi.login(email, password);
            offlineUsers.saveAfterOnlineLogin(user, password);
            session.setUser(user);
            session.markOnline();
            return true;
        } catch (br.com.quack.caixa.api.ApiException e) {
            if (!e.isTransient()) {
                // Servidor recusou (senha trocada, usuário bloqueado...): precisa entrar de novo.
                if (rejectsCredentials(e)) {
                    offlineUsers.forget(email);
                }
                onRevalidationFailed.run();
            }
            return false;
        }
    }
}
