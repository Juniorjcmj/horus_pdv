package br.com.quack.pdv.plataforma.sincronizacao.alertas;

import br.com.quack.pdv.config.QuackProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Alertas de condicao da loja (D11): a cada minuto confere o que nao nasce de um evento e registra (de novo) o alerta
 * enquanto a condicao durar. O limite de um e-mail por hora e do {@link EnviadorAlertas}. Condicoes: sincronizacao
 * pausada, atraso acima de 15 minutos com internet, NFC-e pendente ha mais de 30 minutos e falha no backup. Os alertas
 * de evento (quarentena, comando parado ou em conflito, conferencia com diferenca) sao registrados onde acontecem.
 */
@Component
@Profile("loja")
public class VerificadorAlertasLoja {

    private static final Logger LOG = LoggerFactory.getLogger(VerificadorAlertasLoja.class);
    public static final Duration LIMITE_ATRASO = Duration.ofMinutes(15);
    public static final Duration LIMITE_NFCE = Duration.ofMinutes(30);

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final QuackProperties props;
    private final Clock clock;

    public VerificadorAlertasLoja(ObjectProvider<JdbcTemplate> jdbc, QuackProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    public void agendado() {
        if (!props.getSync().isAlertasAtivo() || jdbc.getIfAvailable() == null) return;
        try {
            verificar(OffsetDateTime.now(clock));
        } catch (RuntimeException e) {
            LOG.error("Erro ao verificar os alertas da loja.", e);
        }
    }

    /** Registra os alertas das condicoes em vigor em {@code agora}; devolve os motivos registrados. */
    public synchronized List<String> verificar(OffsetDateTime agora) {
        JdbcTemplate j = jdbc.getObject();
        java.util.ArrayList<String> motivos = new java.util.ArrayList<>();
        List<Object[]> estado = j.query("""
                SELECT Ativada, CompanyId, VersaoDe, PausadaMotivo, TentativasSeguidas, UltimaSincronizacaoEm, AtivadaEm
                  FROM dbo.SyncEstado WHERE Id = 1
                """, (rs, i) -> new Object[] {rs.getBoolean(1), rs.getString(2), rs.getObject(3), rs.getString(4),
                        rs.getInt(5), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class)});
        if (estado.isEmpty() || !(Boolean) estado.getFirst()[0] || estado.getFirst()[1] == null) return motivos;
        Object[] e = estado.getFirst();
        String empresa = (String) e[1];

        String pausa = (String) e[3];
        if (pausa != null) {
            AlertasSync.registrar(j, agora, AlertasSync.PAUSADA, empresa, pausa);
            motivos.add(AlertasSync.PAUSADA);
        } else if (e[2] != null && (Integer) e[4] == 0) { // sem erro de rede: ha internet
            Long atual = j.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
            OffsetDateTime referencia = e[5] != null ? (OffsetDateTime) e[5] : (OffsetDateTime) e[6];
            if (atual != null && atual > ((Number) e[2]).longValue() && referencia != null
                    && Duration.between(referencia, agora).compareTo(LIMITE_ATRASO) > 0) {
                AlertasSync.registrar(j, agora, AlertasSync.ATRASO, empresa, "Ha mudancas na loja que nao chegaram a nuvem desde "
                        + referencia + " (versao confirmada " + e[2] + ", versao da loja " + atual + ").");
                motivos.add(AlertasSync.ATRASO);
            }
        }

        Integer notas = j.queryForObject("""
                SELECT COUNT(*) FROM dbo.DocumentosFiscais
                 WHERE CompanyId = ? AND Modelo = 65 AND Status IN (1, 2, 8) AND CriadoEm < ?
                """, Integer.class, empresa, agora.minus(LIMITE_NFCE));
        if (notas != null && notas > 0) {
            AlertasSync.registrar(j, agora, AlertasSync.NFCE_PENDENTE, empresa,
                    notas + " NFC-e pendente(s) ha mais de 30 minutos.");
            motivos.add(AlertasSync.NFCE_PENDENTE);
        }

        EstadoBackup.ler(props.getSync().getBackupEstado()).filter(b -> !b.sucesso()).ifPresent(b -> {
            AlertasSync.registrar(j, agora, AlertasSync.BACKUP, empresa, "Falha no backup: " + (b.erro() == null ? "sem detalhe" : b.erro()));
            motivos.add(AlertasSync.BACKUP);
        });
        return motivos;
    }
}
