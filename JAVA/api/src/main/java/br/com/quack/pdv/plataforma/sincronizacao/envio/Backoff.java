package br.com.quack.pdv.plataforma.sincronizacao.envio;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Espera exponencial de 2 s a 5 min com jitter (D4): metade fixa mais ate metade aleatoria, sem limite de tentativas. */
public final class Backoff {

    public static final Duration BASE = Duration.ofSeconds(2);
    public static final Duration TETO = Duration.ofMinutes(5);

    private Backoff() {}

    /** Espera maxima (sem jitter) da tentativa numero {@code tentativa} (1 = primeira falha). */
    public static Duration teto(int tentativa) {
        int expoente = Math.min(Math.max(tentativa, 1) - 1, 20);
        long ms = BASE.toMillis() << expoente;
        return Duration.ofMillis(Math.min(ms, TETO.toMillis()));
    }

    /** Espera com jitter; {@code aleatorio} devolve [0,1). Resultado entre 50% e 100% do teto da tentativa. */
    public static Duration espera(int tentativa, DoubleSupplier aleatorio) {
        long max = teto(tentativa).toMillis();
        return Duration.ofMillis((long) (max * (0.5 + 0.5 * aleatorio.getAsDouble())));
    }
}
