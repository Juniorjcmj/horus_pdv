package br.com.quack.estoque;

import java.nio.file.Path;

/** Ponte pública para outros pacotes de teste: garante o usuário estoquista de teste. */
public final class E2eSupportAcesso {
    private E2eSupportAcesso() {
    }

    public static void garantirEstoquista(Path home) {
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        e.comoEstoquista(home.resolveSibling(home.getFileName() + "-est"));
    }
}
