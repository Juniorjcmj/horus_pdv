package br.com.quack.caixa.service;

import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.dto.CaixaStatusDto;
import br.com.quack.caixa.domain.Company;

/** Estado da sessão do operador (usuário, empresa, último status do caixa). */
public final class Session {
    private volatile AuthApi.User user;
    private volatile Company company;
    private volatile CaixaStatusDto caixaStatus;
    private volatile boolean offlineStatus;
    /** Entrou sem internet (login offline): ainda não há sessão no servidor. */
    private volatile boolean offlineAuth;
    /** Credenciais só em memória, para refazer o login sozinho quando a API voltar. */
    private volatile String reloginEmail;
    private volatile String reloginPassword;

    public AuthApi.User user() {
        return user;
    }

    public void setUser(AuthApi.User user) {
        this.user = user;
    }

    public boolean loggedIn() {
        return user != null;
    }

    public Company company() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public CaixaStatusDto caixaStatus() {
        return caixaStatus;
    }

    public void setCaixaStatus(CaixaStatusDto s, boolean offline) {
        this.caixaStatus = s;
        this.offlineStatus = offline;
    }

    public boolean offlineStatus() {
        return offlineStatus;
    }

    public boolean offlineAuth() {
        return offlineAuth;
    }

    /** Login offline: guarda e-mail/senha em memória (nunca em disco) para o login silencioso. */
    public void setOfflineAuth(String email, String password) {
        this.offlineAuth = true;
        this.reloginEmail = email;
        this.reloginPassword = password;
    }

    /** Login refeito com sucesso: a sessão passa a ser online e a senha sai da memória. */
    public void markOnline() {
        this.offlineAuth = false;
        this.reloginEmail = null;
        this.reloginPassword = null;
    }

    public String reloginEmail() {
        return reloginEmail;
    }

    public String reloginPassword() {
        return reloginPassword;
    }

    /** 401 numa sessão offline = servidor de volta mas ainda sem login: tratar como "ainda offline". */
    public boolean treatAsOffline(br.com.quack.caixa.api.ApiException e) {
        return e.isTransient() || (offlineAuth && e.status() == 401);
    }

    public void clear() {
        markOnline();
        user = null;
        company = null;
        caixaStatus = null;
        offlineStatus = false;
    }
}
