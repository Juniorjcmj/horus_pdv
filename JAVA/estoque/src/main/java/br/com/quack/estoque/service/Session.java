package br.com.quack.estoque.service;

import br.com.quack.estoque.api.AuthApi;
import br.com.quack.estoque.domain.Perfil;

/** Estado da sessão do usuário logado. */
public final class Session {
    private volatile AuthApi.User user;
    private volatile Perfil perfil;

    public AuthApi.User user() {
        return user;
    }

    public Perfil perfil() {
        return perfil;
    }

    public void setUser(AuthApi.User user, Perfil perfil) {
        this.user = user;
        this.perfil = perfil;
    }

    public boolean loggedIn() {
        return user != null;
    }

    public void clear() {
        user = null;
        perfil = null;
    }
}
