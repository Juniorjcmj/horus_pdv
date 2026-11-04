package br.com.quack.pdv.plataforma.sincronizacao.situacao;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.alertas.EstadoBackup;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteJsonNuvem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Situacao da sincronizacao vista pela loja (D11). O estado do envio, o atraso, a pausa, as notas paradas, a ultima
 * conferencia e o backup sao locais. Os comandos pendentes/parados e a quarentena moram na nuvem: a loja os le dela
 * (rota de situacao da nuvem com o token do servidor); sem internet ficam nulos e {@code nuvemAcessivel} e falso.
 */
@Service
@Profile("loja")
public class SituacaoLoja {

    private static final Logger LOG = LoggerFactory.getLogger(SituacaoLoja.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TIMEOUT_S = 5;

    private final JdbcTemplate jdbc;
    private final ClienteJsonNuvem nuvem;
    private final QuackProperties props;

    public SituacaoLoja(JdbcTemplate jdbc, ClienteJsonNuvem nuvem, QuackProperties props) {
        this.jdbc = jdbc;
        this.nuvem = nuvem;
        this.props = props;
    }

    public SituacaoSync consultar(OffsetDateTime agora) {
        List<Object[]> e = jdbc.query("""
                SELECT CompanyId, Ativada, VersaoDe, AplicadoAteNuvem, UltimaSincronizacaoEm, AtivadaEm, PausadaMotivo
                  FROM dbo.SyncEstado WHERE Id = 1
                """, (rs, i) -> new Object[] {rs.getString(1), rs.getBoolean(2), rs.getObject(3), rs.getObject(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getString(7)});
        Object[] s = e.getFirst();
        String empresa = (String) s[0];
        boolean ativada = (Boolean) s[1];
        Long versaoLocal = ativada ? jdbc.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class) : null;
        Long confirmada = s[2] == null ? null : ((Number) s[2]).longValue();
        OffsetDateTime ultima = (OffsetDateTime) s[4];
        Long atraso = null;
        if (ativada && versaoLocal != null && confirmada != null) {
            OffsetDateTime referencia = ultima != null ? ultima : (OffsetDateTime) s[5];
            atraso = versaoLocal > confirmada && referencia != null
                    ? Math.max(0, Duration.between(referencia, agora).toSeconds()) : 0L;
        }
        Integer notas = empresa == null ? 0 : jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.DocumentosFiscais
                 WHERE CompanyId = ? AND Modelo = 65 AND Status IN (1, 2, 8) AND CriadoEm < ?
                """, Integer.class, empresa, agora.minusMinutes(SituacaoSync.MINUTOS_NFCE_PENDENTE));

        Integer pendentes = null;
        Integer parados = null;
        Integer quarentena = null;
        Boolean acessivel = null;
        String servidor = null;
        if (ativada) {
            try {
                JsonNode d = nuvem.get("/api/loja-sync/situacao", TIMEOUT_S);
                pendentes = d.path("comandosPendentes").asInt();
                parados = d.path("comandosParados").asInt();
                quarentena = d.path("quarentenaAberta").asInt();
                servidor = d.path("servidorStatus").asText(null);
                acessivel = true;
            } catch (IOException ex) {
                LOG.debug("Situacao da nuvem indisponivel: {}", ex.getMessage());
                acessivel = false;
            }
        }
        return new SituacaoSync("loja", empresa, agora, versaoLocal, confirmada, ultima, atraso, s[6] != null,
                (String) s[6], pendentes, parados, quarentena, notas == null ? 0 : notas, ultimaConferencia(), backup(),
                acessivel, servidor);
    }

    private JsonNode ultimaConferencia() {
        List<ObjectNode> l = jdbc.query("""
                SELECT TOP 1 ExecutadaEm, Origem, Resultado, Voltas, DetalheJson FROM dbo.SyncConferencias ORDER BY Id DESC
                """, (rs, i) -> SituacaoNuvem.conferencia(rs.getObject(1, OffsetDateTime.class), rs.getString(2),
                rs.getString(3), rs.getInt(4), rs.getString(5)));
        return l.isEmpty() ? null : l.getFirst();
    }

    private JsonNode backup() {
        return EstadoBackup.ler(props.getSync().getBackupEstado()).map(b -> (JsonNode) JSON.valueToTree(b)).orElse(null);
    }
}
