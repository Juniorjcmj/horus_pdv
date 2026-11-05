package br.com.quack.pdv.plataforma.sincronizacao.recuperacao;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteJsonNuvem;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara a recuperacao no perfil {@code loja}: ao subir, se o {@code restaurar.ps1} deixou o marcador; a cada intervalo,
 * enquanto estiver pendente (sem internet repete ate conseguir); e, a cada {@code recuperacao-verificacao-ms}, confere se a
 * nuvem esta a frente da versao da loja, o que so acontece num banco restaurado (restauracao feita sem o marcador).
 */
@Component
@Profile("loja")
public class RecuperacaoExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RecuperacaoExecutor.class);
    private static final int TIMEOUT_S = 10;

    private final EstadoRecuperacao estado;
    private final RecuperacaoLoja recuperacao;
    private final ClienteJsonNuvem nuvem;
    private final JdbcTemplate jdbc;
    private final QuackProperties props;
    private final boolean porLinhaDeComando;
    private volatile long ultimaVerificacao;

    public RecuperacaoExecutor(EstadoRecuperacao estado, RecuperacaoLoja recuperacao, ClienteJsonNuvem nuvem, JdbcTemplate jdbc,
                               QuackProperties props, @Value("${quack.sync.recuperacao.executar:false}") boolean porLinhaDeComando) {
        this.estado = estado;
        this.recuperacao = recuperacao;
        this.nuvem = nuvem;
        this.jdbc = jdbc;
        this.props = props;
        this.porLinhaDeComando = porLinhaDeComando;
    }

    @Scheduled(initialDelayString = "${quack.sync.recuperacao-intervalo-ms:15000}",
            fixedDelayString = "${quack.sync.recuperacao-intervalo-ms:15000}")
    public void agendado() {
        if (!props.getSync().isRecuperacaoAtiva() || porLinhaDeComando) return;
        try {
            tentar();
        } catch (RuntimeException e) {
            LOG.error("Erro inesperado na rotina de recuperacao.", e);
        }
    }

    /** Uma passada: executa a recuperacao se houver pedido; senao, de tempos em tempos, procura sinal de restauracao. */
    public void tentar() {
        estado.consumirMarcador();
        if (estado.foto().emAndamento()) {
            recuperacao.executar();
            return;
        }
        verificarVersao();
    }

    private void verificarVersao() {
        long agora = System.currentTimeMillis();
        if (agora - ultimaVerificacao < props.getSync().getRecuperacaoVerificacaoMs()) return;
        ultimaVerificacao = agora;
        Boolean ativada = jdbc.queryForObject("SELECT Ativada FROM dbo.SyncEstado WHERE Id = 1", Boolean.class);
        if (!Boolean.TRUE.equals(ativada)) return;
        try {
            JsonNode d = nuvem.get("/api/loja-sync/situacao", TIMEOUT_S);
            long confirmada = d.path("versaoConfirmada").asLong(0);
            Long local = jdbc.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
            if (local != null && confirmada > local) {
                estado.solicitar("versao-regrediu: a nuvem confirmou ate " + confirmada + " e a loja esta em " + local);
                recuperacao.executar();
            }
        } catch (IOException e) {
            LOG.debug("Verificacao de versao adiada (sem nuvem): {}", e.getMessage());
        }
    }
}
