package br.com.quack.caixa.config;

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
            return new AppPaths(Path.of(appData, "QuackCaixa"));
        }
        return new AppPaths(Path.of(System.getProperty("user.home"), ".quack-caixa"));
    }

    public Path root() {
        return root;
    }

    public Path configFile() {
        return root.resolve("config.properties");
    }

    public Path cacheDir() {
        return root.resolve("cache");
    }

    public Path outboxPending() {
        return root.resolve("outbox").resolve("pending");
    }

    public Path outboxFailed() {
        return root.resolve("outbox").resolve("failed");
    }
}
