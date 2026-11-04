package br.com.quack.pdv.plataforma.sincronizacao.comandos;

import br.com.quack.pdv.config.QuackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Rotina continua dos comandos da nuvem no perfil {@code loja}: uma thread propria (a espera longa de ate 25 s nao pode
 * ocupar o agendador do envio) que chama {@link AplicadorComandos#ciclo()} em lacos. A espera vem do long-poll da nuvem;
 * so falha de rede ou comando que falhou pedem pausa.
 */
@Component
@Profile("loja")
public class ExecutorComandos implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutorComandos.class);

    private final AplicadorComandos aplicador;
    private final QuackProperties props;
    private final long pausaMs;
    private volatile boolean rodando;
    private Thread thread;

    public ExecutorComandos(AplicadorComandos aplicador, QuackProperties props,
                            @Value("${quack.sync.intervalo-ms:2000}") long pausaMs) {
        this.aplicador = aplicador;
        this.props = props;
        this.pausaMs = Math.max(50, pausaMs);
    }

    @Override
    public void start() {
        rodando = true;
        thread = new Thread(this::laco, "comandos-nuvem");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        rodando = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() { return rodando; }

    private void laco() {
        while (rodando) {
            long pausa = 0;
            try {
                if (!props.getSync().isEnvioAtivo()) {
                    pausa = pausaMs;
                } else {
                    switch (aplicador.ciclo()) {
                        case INATIVO, SEM_COMUNICACAO, APLICOU_COM_FALHA -> pausa = pausaMs;
                        default -> { }
                    }
                }
            } catch (RuntimeException e) {
                LOG.error("Erro inesperado nos comandos da nuvem.", e);
                pausa = Math.max(pausaMs, 5_000);
            }
            if (pausa > 0) {
                try {
                    Thread.sleep(pausa);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
