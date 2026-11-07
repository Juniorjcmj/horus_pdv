package br.com.quack.pdv.plataforma.sincronizacao.alertas;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.email.app.EmailService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Envio dos alertas por e-mail aos administradores da empresa (D11), pelo {@link EmailService} que a API ja tem (SMTP da
 * empresa, ou o global {@code quack.email.*}). No maximo um e-mail por motivo a cada hora: o alerta so sai se houve
 * ocorrencia depois do ultimo envio e esse envio tem mais de uma hora. Sem SMTP configurado, ou sem internet, o alerta
 * fica guardado (so no log e em {@code SyncAlertas}) e e tentado de novo no proximo ciclo.
 */
@Component
@Profile({"loja", "nuvem"})
public class EnviadorAlertas {

    private static final Logger LOG = LoggerFactory.getLogger(EnviadorAlertas.class);
    static final long INTERVALO_MINIMO_HORAS = 1;

    private record Alerta(String motivo, String companyId, String detalhe, int contagem) {}

    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<EmailService> email;
    private final QuackProperties props;
    private final Clock clock;
    /** ReentrantLock em vez de synchronized: com virtual threads (Java 21) o synchronized prende a thread do sistema durante o I/O. */
    private final ReentrantLock trava = new ReentrantLock();

    public EnviadorAlertas(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<EmailService> email, QuackProperties props,
                           Clock clock) {
        this.jdbc = jdbc;
        this.email = email;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void agendado() {
        if (!props.getSync().isAlertasAtivo() || jdbc.getIfAvailable() == null) return;
        try {
            enviarPendentes(OffsetDateTime.now(clock));
        } catch (RuntimeException e) {
            LOG.error("Erro ao enviar os alertas da sincronizacao.", e);
        }
    }

    /** Envia o que estiver pendente e liberado pelo limite de uma hora; devolve quantos alertas foram enviados. */
    public int enviarPendentes(OffsetDateTime agora) {
        trava.lock();
        try {
            return enviarPendentesSemTrava(agora);
        } finally {
            trava.unlock();
        }
    }

    private int enviarPendentesSemTrava(OffsetDateTime agora) {
        JdbcTemplate j = jdbc.getObject();
        List<Alerta> pendentes = j.query("""
                SELECT Motivo, CompanyId, Detalhe, Contagem FROM dbo.SyncAlertas
                 WHERE CompanyId <> N''
                   AND (UltimoEnvio IS NULL OR (UltimaEm > UltimoEnvio AND UltimoEnvio <= ?))
                 ORDER BY UltimaEm
                """, (rs, i) -> new Alerta(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)),
                agora.minusHours(INTERVALO_MINIMO_HORAS));
        int enviados = 0;
        EmailService servico = email.getObject();
        for (Alerta a : pendentes) {
            if (!servico.habilitado(a.companyId())) continue; // sem SMTP: so log
            List<String> admins = j.queryForList(
                    "SELECT Email FROM dbo.Usuarios WHERE CompanyId = ? AND Role = N'administrador' AND Status = N'ativo'",
                    String.class, a.companyId());
            boolean saiu = false;
            for (String destino : admins) {
                try {
                    servico.enviarAlerta(destino, "[Quack PDV] Alerta de sincronizacao: " + a.motivo(), texto(a), a.companyId());
                    saiu = true;
                } catch (RuntimeException e) {
                    LOG.warn("Alerta {} nao enviado a {}: {}", a.motivo(), destino, e.getMessage());
                }
            }
            if (saiu) {
                j.update("UPDATE dbo.SyncAlertas SET UltimoEnvio = ? WHERE Motivo = ? AND CompanyId = ?",
                        agora, a.motivo(), a.companyId());
                enviados++;
            }
        }
        return enviados;
    }

    private static String texto(Alerta a) {
        return "Motivo: " + a.motivo() + "\nEmpresa: " + a.companyId() + "\nOcorrencias: " + a.contagem()
                + "\n\n" + (a.detalhe() == null ? "" : a.detalhe())
                + "\n\nNo maximo um e-mail por motivo a cada hora. Detalhes na rota GET /api/loja-sync/situacao.";
    }
}
