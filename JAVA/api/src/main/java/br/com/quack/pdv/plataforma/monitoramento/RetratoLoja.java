package br.com.quack.pdv.plataforma.monitoramento;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.alertas.EstadoBackup;
import br.com.quack.pdv.plataforma.sincronizacao.situacao.SituacaoLoja;
import br.com.quack.pdv.plataforma.sincronizacao.situacao.SituacaoSync;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Retrato do estado da loja (M6), lido de {@link SituacaoLoja} e compartilhado entre as metricas locais
 * ({@link MetricasLoja}) e o relato a nuvem ({@link RelatorEstadoLoja}) para nao consultar duas vezes. O retrato e
 * imutavel; {@link #obter()} reaproveita o ultimo enquanto ele tiver menos de {@link #VALIDADE}.
 */
@Component
@Profile("loja")
public class RetratoLoja {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RetratoLoja.class);

    /** Quanto tempo um retrato serve antes de uma nova leitura. */
    static final Duration VALIDADE = Duration.ofSeconds(25);

    /** Valores prontos para metricas e para o relato; campos nulos = a loja nao sabe. */
    public record Retrato(Instant lidoEm, boolean sincronizacaoAtivada, Long atrasoSegundos, boolean pausada,
                          OffsetDateTime ultimoBackupEm, Boolean backupSucesso, String backupErro, int nfcePendentes,
                          String versaoPrograma) {}

    private final SituacaoLoja situacao;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final QuackProperties props;
    private final Clock clock;
    private final String versao;
    private final ReentrantLock trava = new ReentrantLock();
    private volatile Retrato ultimo;

    public RetratoLoja(SituacaoLoja situacao, ObjectProvider<JdbcTemplate> jdbc, QuackProperties props, Clock clock,
                       ObjectProvider<BuildProperties> build) {
        this.situacao = situacao;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        BuildProperties b = build.getIfAvailable();
        this.versao = b == null ? null : b.getVersion();
    }

    /** Ultimo retrato lido, sem tocar no banco (nulo antes da primeira leitura). */
    public Retrato ultimo() {
        return ultimo;
    }

    /** Retrato recente: o ultimo, se ainda valido, ou uma leitura nova. Nunca lanca; sem leitura possivel devolve o ultimo. */
    public Retrato obter() {
        Retrato atual = ultimo;
        if (atual != null && Duration.between(atual.lidoEm(), clock.instant()).compareTo(VALIDADE) < 0) return atual;
        return atualizar();
    }

    /** Le a situacao agora e troca o retrato; em erro mantem o anterior. */
    public Retrato atualizar() {
        trava.lock();
        try {
            Instant agora = clock.instant();
            SituacaoSync s = situacao.consultar(agora.atOffset(java.time.ZoneOffset.UTC));
            Optional<EstadoBackup.Estado> backup = EstadoBackup.ler(props.getSync().getBackupEstado());
            boolean ativada = jdbc.getObject() != null && Boolean.TRUE.equals(lerAtivada());
            Retrato novo = new Retrato(agora, ativada, s.atrasoSegundos(), s.pausada(),
                    backup.map(b -> data(b.ultimoBackupEm())).orElse(null), backup.map(EstadoBackup.Estado::sucesso).orElse(null),
                    backup.map(EstadoBackup.Estado::erro).orElse(null), s.nfcePendentesHaMaisDe10Min(), versao);
            ultimo = novo;
            return novo;
        } catch (RuntimeException e) {
            LOG.debug("Retrato da loja nao atualizado: {}", e.getMessage());
            return ultimo;
        } finally {
            trava.unlock();
        }
    }

    private Boolean lerAtivada() {
        List<Boolean> l = jdbc.getObject().query("SELECT Ativada FROM dbo.SyncEstado WHERE Id = 1", (rs, i) -> rs.getBoolean(1));
        return l.isEmpty() ? null : l.getFirst();
    }

    static OffsetDateTime data(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
