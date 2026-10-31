package br.com.quack.estoque;

import br.com.quack.estoque.api.ApiClient;
import br.com.quack.estoque.api.AuthApi;
import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.api.EntradaApi;
import br.com.quack.estoque.api.EstoqueRelatorioApi;
import br.com.quack.estoque.api.InventarioApi;
import br.com.quack.estoque.api.OrdemCompraApi;
import br.com.quack.estoque.api.ProdutoApi;
import br.com.quack.estoque.api.PromocaoApi;
import br.com.quack.estoque.config.AppConfig;
import br.com.quack.estoque.config.AppPaths;
import br.com.quack.estoque.domain.Catalogo;
import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Perfil;
import br.com.quack.estoque.service.Session;

/** Agrupa os serviços do aplicativo (um por execução). */
public final class AppContext {
    /** Recarrega o catálogo a cada 5 minutos ou com F5 (design D8). */
    public static final long CATALOGO_MAX_IDADE_MS = 5 * 60 * 1000L;

    public final AppPaths paths;
    public final AppConfig config;
    public final ApiClient api;
    public final AuthApi authApi;
    public final ProdutoApi produtos;
    public final CadastroApi cadastros;
    public final EntradaApi entradas;
    public final InventarioApi inventarios;
    public final EstoqueRelatorioApi relatorios;
    public final OrdemCompraApi compras;
    public final PromocaoApi promocoes;
    public final Session session = new Session();
    public final Catalogo catalogo = new Catalogo();
    public final br.com.quack.estoque.labels.FilaEtiquetas etiquetas;
    public final br.com.quack.estoque.service.Rascunhos rascunhos;

    public AppContext() {
        this.paths = AppPaths.defaults();
        this.config = new AppConfig(paths);
        this.api = new ApiClient(config.apiBaseUrl());
        this.authApi = new AuthApi(api);
        this.produtos = new ProdutoApi(api);
        this.cadastros = new CadastroApi(api);
        this.entradas = new EntradaApi(api);
        this.inventarios = new InventarioApi(api);
        this.relatorios = new EstoqueRelatorioApi(api);
        this.compras = new OrdemCompraApi(api);
        this.promocoes = new PromocaoApi(api);
        this.etiquetas = new br.com.quack.estoque.labels.FilaEtiquetas(paths.etiquetasFile());
        this.rascunhos = new br.com.quack.estoque.service.Rascunhos(paths.rascunhosDir());
    }

    /**
     * Entra com e-mail/senha e confere o perfil: caixa, atendente e demais perfis são recusados (o app é para
     * estoque e gerência). Em caso de recusa a sessão do servidor é encerrada.
     */
    public AuthApi.User entrar(String email, String senha) {
        AuthApi.User user = authApi.login(email, senha);
        Perfil perfil = Perfil.de(user.role()).orElse(null);
        if (perfil == null) {
            authApi.logout();
            throw new DomainException("Este aplicativo é para estoque e gerência. O perfil \"" + user.role()
                    + "\" não tem acesso; use o Quack Caixa.");
        }
        session.setUser(user, perfil);
        return user;
    }

    /** Terminal gravado nas trilhas (identifica esta máquina). */
    public String terminal() {
        return "estoque-" + config.deviceId().substring(0, 8);
    }

    /** Carrega o catálogo se estiver vazio ou velho (ou sempre, com forcar). */
    public void garantirCatalogo(boolean forcar) {
        if (forcar || catalogo.desatualizado(CATALOGO_MAX_IDADE_MS)) {
            catalogo.carregar(produtos.listar());
        }
    }
}
