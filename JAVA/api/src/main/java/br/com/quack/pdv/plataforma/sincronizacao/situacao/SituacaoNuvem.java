package br.com.quack.pdv.plataforma.sincronizacao.situacao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Situacao da sincronizacao vista pela nuvem (D11): estado da replica, fila de comandos, quarentena e ultima conferencia. */
@Service
@Lazy
@Profile("nuvem")
public class SituacaoNuvem {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public SituacaoNuvem(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SituacaoSync consultar(String companyId, OffsetDateTime agora) {
        List<Object[]> estado = jdbc.query(
                "SELECT AplicadoAte, UltimaSincronizacaoEm FROM dbo.SyncEstadoNuvem WHERE CompanyId = ?",
                (rs, i) -> new Object[] {rs.getObject(1), rs.getObject(2, OffsetDateTime.class)}, companyId);
        Long confirmada = estado.isEmpty() || estado.getFirst()[0] == null ? null : ((Number) estado.getFirst()[0]).longValue();
        OffsetDateTime ultima = estado.isEmpty() ? null : (OffsetDateTime) estado.getFirst()[1];
        List<String> servidor = jdbc.queryForList(
                "SELECT TOP 1 Status FROM dbo.SyncServidores WHERE CompanyId = ? AND Status <> N'revogado' ORDER BY CriadoEm DESC",
                String.class, companyId);
        Integer pendentes = contar("SELECT COUNT(*) FROM dbo.SyncComandos WHERE CompanyId = ? AND Status = N'pendente'", companyId);
        Integer parados = contar("SELECT COUNT(*) FROM dbo.SyncComandos WHERE CompanyId = ? AND Status = N'parado'", companyId);
        Integer quarentena = contar("SELECT COUNT(*) FROM dbo.SyncQuarentena WHERE CompanyId = ? AND ResolvidoEm IS NULL", companyId);
        Integer notas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM dbo.DocumentosFiscais
                 WHERE CompanyId = ? AND Modelo = 65 AND Status IN (1, 2, 8) AND CriadoEm < ?
                """, Integer.class, companyId, agora.minusMinutes(SituacaoSync.MINUTOS_NFCE_PENDENTE));
        return new SituacaoSync("nuvem", companyId, agora, null, confirmada, ultima, null, false, null, pendentes, parados,
                quarentena, notas == null ? 0 : notas, ultimaConferencia(companyId), null, null,
                servidor.isEmpty() ? null : servidor.getFirst(), null, null);
    }

    private Integer contar(String sql, String companyId) {
        return jdbc.queryForObject(sql, Integer.class, companyId);
    }

    private JsonNode ultimaConferencia(String companyId) {
        List<ObjectNode> l = jdbc.query("""
                SELECT TOP 1 ExecutadaEm, Origem, Resultado, Voltas, DetalheJson FROM dbo.SyncConferencias
                 WHERE CompanyId = ? ORDER BY Id DESC
                """, (rs, i) -> conferencia(rs.getObject(1, OffsetDateTime.class), rs.getString(2), rs.getString(3), rs.getInt(4),
                rs.getString(5)), companyId);
        return l.isEmpty() ? null : l.getFirst();
    }

    /** Conferencia como JSON, para a nuvem e a loja mostrarem do mesmo jeito. */
    static ObjectNode conferencia(OffsetDateTime em, String origem, String resultado, int voltas, String detalheJson) {
        ObjectNode n = JSON.createObjectNode().put("executadaEm", em == null ? null : em.toString()).put("origem", origem)
                .put("resultado", resultado).put("voltas", voltas);
        if (detalheJson != null) {
            try {
                n.set("detalhe", JSON.readTree(detalheJson));
            } catch (IOException e) {
                n.put("detalhe", detalheJson);
            }
        }
        return n;
    }
}
