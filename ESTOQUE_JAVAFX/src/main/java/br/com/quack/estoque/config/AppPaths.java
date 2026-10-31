package br.com.quack.estoque.config;

import java.nio.file.Path;

/** Pastas de dados locais. -Dquack.home sobrescreve (usado nos testes). */
public final class AppPaths {
    private final Path root;

    public AppPaths(Path root) {
        this.root = root;
    }

    public static AppPaths defaults() {
        String override = System.getProperty("quack.home");
        if (override != null && !override.isBlank()) {
            return new AppPaths(Path.of(override));
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return new AppPaths(Path.of(appData, "QuackEstoque"));
        }
        return new AppPaths(Path.of(System.getProperty("user.home"), ".quack-estoque"));
    }

    public Path root() {
        return root;
    }

    public Path configFile() {
        return root.resolve("config.properties");
    }

    /** Fila de etiquetas, modelo escolhido, calibração e último preço impresso por produto. */
    public Path etiquetasFile() {
        return root.resolve("etiquetas.json");
    }

    /** Rascunhos locais (entrada em conferência, contagens não enviadas). */
    public Path rascunhosDir() {
        return root.resolve("rascunhos");
    }
}
