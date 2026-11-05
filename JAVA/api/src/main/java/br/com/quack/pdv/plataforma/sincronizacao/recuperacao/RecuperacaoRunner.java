package br.com.quack.pdv.plataforma.sincronizacao.recuperacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Acionamento por linha de comando da recuperacao, como a carga inicial:
 * <pre>
 * java -jar quack-pdv-api.jar --spring.profiles.active=loja --quack.sync.recuperacao.executar=true
 * </pre>
 * Pede a recuperacao, executa e encerra o processo (0 = concluida; 1 = pendente, pode repetir: retoma de onde parou; sem
 * internet o codigo e 1 e a conferencia continua parada).
 */
@Component
@Profile("loja")
@ConditionalOnProperty(name = "quack.sync.recuperacao.executar", havingValue = "true")
public class RecuperacaoRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RecuperacaoRunner.class);

    private final EstadoRecuperacao estado;
    private final RecuperacaoLoja recuperacao;
    private final ApplicationContext contexto;

    public RecuperacaoRunner(EstadoRecuperacao estado, RecuperacaoLoja recuperacao, ApplicationContext contexto) {
        this.estado = estado;
        this.recuperacao = recuperacao;
        this.contexto = contexto;
    }

    @Override
    public void run(ApplicationArguments args) {
        int codigo = 1;
        try {
            estado.solicitar("linha-de-comando");
            RecuperacaoLoja.Resultado r = recuperacao.executar();
            LOG.info("Recuperacao pela linha de comando: {}", r);
            codigo = r == RecuperacaoLoja.Resultado.CONCLUIDA || r == RecuperacaoLoja.Resultado.NADA_A_FAZER ? 0 : 1;
        } catch (RuntimeException e) {
            LOG.error("Recuperacao falhou: {}. Rode de novo para retomar.", e.getMessage(), e);
        }
        final int saida = codigo;
        System.exit(SpringApplication.exit(contexto, () -> saida));
    }
}
