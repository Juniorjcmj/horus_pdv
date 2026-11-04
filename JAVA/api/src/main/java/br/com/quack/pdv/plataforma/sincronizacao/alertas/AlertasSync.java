package br.com.quack.pdv.plataforma.sincronizacao.alertas;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Registro de alertas da sincronizacao (D11). Cada alerta e uma linha de {@code SyncAlertas} por (motivo, empresa):
 * registrar de novo so atualiza o detalhe, a hora da ultima ocorrencia e a contagem. O envio por e-mail e do
 * {@link EnviadorAlertas}, que respeita o limite de um por motivo por hora; aqui so se grava e se escreve no log.
 */
public final class AlertasSync {

    private static final Logger LOG = LoggerFactory.getLogger(AlertasSync.class);

    public static final String PAUSADA = "sincronizacao-pausada";
    public static final String ATRASO = "sincronizacao-atrasada";
    public static final String COMANDO_PARADO = "comando-parado";
    public static final String COMANDO_CONFLITO = "comando-conflito";
    public static final String QUARENTENA = "quarentena";
    public static final String CONFERENCIA = "conferencia-divergente";
    public static final String NFCE_PENDENTE = "nfce-pendente";
    public static final String BACKUP = "backup-falhou";

    private AlertasSync() {}

    public static void registrar(JdbcTemplate j, OffsetDateTime agora, String motivo, String companyId, String detalhe) {
        String empresa = companyId == null ? "" : companyId;
        LOG.error("ALERTA {} (empresa {}): {}", motivo, empresa, detalhe);
        String d = detalhe == null ? null : detalhe.length() <= 1000 ? detalhe : detalhe.substring(0, 1000);
        j.update("""
                MERGE dbo.SyncAlertas AS a USING (SELECT ? AS Motivo, ? AS CompanyId) AS s
                  ON a.Motivo = s.Motivo AND a.CompanyId = s.CompanyId
                WHEN MATCHED THEN UPDATE SET Detalhe = ?, UltimaEm = ?, Contagem = a.Contagem + 1
                WHEN NOT MATCHED THEN INSERT (Motivo, CompanyId, Detalhe, UltimaEm) VALUES (s.Motivo, s.CompanyId, ?, ?);
                """, motivo, empresa, d, agora, d, agora);
    }
}
