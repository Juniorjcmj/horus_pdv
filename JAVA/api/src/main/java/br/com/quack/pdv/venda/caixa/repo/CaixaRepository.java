package br.com.quack.pdv.venda.caixa.repo;

import br.com.quack.pdv.venda.caixa.domain.CaixaMovimento;
import br.com.quack.pdv.venda.caixa.domain.CaixaSessao;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados do caixa: porta literal do {@code CaixaAB} da API .NET (mesmo SQL, mesmas dicas de bloqueio).
 * As escritas rodam na transacao de quem chama (o {@code CaixaOperacoes}); as leituras usam a conexao corrente.
 */
@Repository
public class CaixaRepository {

    /** Linha de ProcessedEvents lida para decidir se o evento e repeticao. */
    public record EventoProcessado(String payloadHash, String responsePayload) {}

    /** Situacao do turno a fechar, lida com bloqueio. */
    public enum SituacaoTurno { INEXISTENTE, ABERTO, FECHADO }

    private static final String COLUNAS_SESSAO = """
            Id, OpenedAt, ClosedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note,
                   ExpectedCashAmount, DifferenceAmount, DifferenceReason""";

    private static final String SQL_SESSOES_PARA_STATUS = """
            SELECT s.Id, s.OpenedAt, s.ClosedAt, s.OpeningAmount, s.ClosingAmount, s.OperatorId, s.OperatorName,
                   s.ClosedById, s.ClosedByName, s.Note, s.ExpectedCashAmount, s.DifferenceAmount, s.DifferenceReason
            FROM CaixaSessoes s
            WHERE s.CompanyId = ?
              AND (
                    s.ClosedAt IS NULL
                 OR s.Id IN (SELECT TOP 12 r.Id FROM CaixaSessoes r
                             WHERE r.CompanyId = ? ORDER BY r.OpenedAt DESC)
                 OR s.Id = (SELECT TOP 1 o.Id FROM CaixaSessoes o
                            WHERE o.CompanyId = ? AND o.OperatorId = ? ORDER BY o.OpenedAt DESC)
                  )
            ORDER BY s.OpenedAt DESC;
            """;

    private static final String SQL_TOTAIS_POR_SESSAO = """
            SELECT Combined.PaymentType, SUM(Combined.Amount) AS Total
            FROM (
                SELECT vp.PaymentType, vp.Amount
                FROM VendaPagamentos vp
                INNER JOIN Vendas v ON v.Id = vp.VendaId AND v.CompanyId = vp.CompanyId
                WHERE vp.CompanyId = ? AND v.CaixaSessaoId = ?
                  AND ISNULL(v.Status, 'finalizada') <> 'cancelada'

                UNION ALL

                SELECT v.PaymentType, v.TotalAmount AS Amount
                FROM Vendas v
                WHERE v.CompanyId = ? AND v.CaixaSessaoId = ?
                  AND ISNULL(v.Status, 'finalizada') <> 'cancelada'
                  AND NOT EXISTS (SELECT 1 FROM VendaPagamentos vp WHERE vp.VendaId = v.Id)
            ) Combined
            GROUP BY Combined.PaymentType;
            """;

    private static final String SQL_EVENTO = """
            SELECT PayloadHash, ResponsePayload
            FROM ProcessedEvents
            WHERE CompanyId = ? AND EventId = ?;
            """;

    private static final String SQL_EVENTO_COM_BLOQUEIO = """
            SELECT PayloadHash, ResponsePayload
            FROM ProcessedEvents WITH (UPDLOCK, ROWLOCK)
            WHERE CompanyId = ? AND EventId = ?;
            """;

    private static final String SQL_INSERIR_EVENTO = """
            INSERT INTO ProcessedEvents
                (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, ProcessedAt, ResponsePayload)
            VALUES
                (?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final String SQL_INSERIR_SESSAO = """
            INSERT INTO CaixaSessoes
                (Id, CompanyId, OpenedAt, OpeningAmount, ClosingAmount, OperatorId, OperatorName, ClosedById, ClosedByName, Note)
            VALUES
                (?, ?, ?, ?, 0, ?, ?, N'', N'', N'');
            """;

    private static final String SQL_FECHAR_SESSAO = """
            UPDATE CaixaSessoes
               SET ClosedAt = ?,
                   ClosingAmount = ?,
                   ClosedById = ?,
                   ClosedByName = ?,
                   Note = ?,
                   ExpectedCashAmount = ?,
                   DifferenceAmount = ?,
                   DifferenceReason = ?
             WHERE Id = ? AND CompanyId = ?;
            """;

    private static final String SQL_INSERIR_MOVIMENTO = """
            INSERT INTO CaixaMovimentos (Id, CompanyId, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final String SQL_INSERIR_AUDITORIA = """
            INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """;

    private static final RowMapper<CaixaSessao> MAPA_SESSAO = (rs, n) -> new CaixaSessao(
            texto(rs, "Id"),
            rs.getObject("OpenedAt", OffsetDateTime.class),
            rs.getObject("ClosedAt", OffsetDateTime.class),
            rs.getBigDecimal("OpeningAmount"),
            rs.getBigDecimal("ClosingAmount"),
            texto(rs, "OperatorId"),
            texto(rs, "OperatorName"),
            texto(rs, "ClosedById"),
            texto(rs, "ClosedByName"),
            texto(rs, "Note"),
            rs.getBigDecimal("ExpectedCashAmount"),
            rs.getBigDecimal("DifferenceAmount"),
            rs.getString("DifferenceReason"));

    private static final RowMapper<CaixaMovimento> MAPA_MOVIMENTO = (rs, n) -> new CaixaMovimento(
            texto(rs, "Id"),
            texto(rs, "CaixaSessaoId"),
            rs.getInt("Tipo"),
            rs.getBigDecimal("Valor"),
            texto(rs, "Motivo"),
            rs.getObject("CreatedAt", OffsetDateTime.class),
            texto(rs, "OperatorId"),
            texto(rs, "OperatorName"));

    private static final RowMapper<EventoProcessado> MAPA_EVENTO =
            (rs, n) -> new EventoProcessado(rs.getString(1), rs.getString(2));

    private final JdbcTemplate jdbc;

    public CaixaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- leituras

    /** As 12 mais recentes, todas as abertas e a mais recente do operador (ordem de OpenedAt decrescente). */
    public List<CaixaSessao> listarSessoesParaStatus(String companyId, String operatorId) {
        return jdbc.query(SQL_SESSOES_PARA_STATUS, MAPA_SESSAO, companyId, companyId, companyId, operatorId);
    }

    public Optional<CaixaSessao> obterSessaoPorId(String companyId, String sessionId) {
        String sql = "SELECT " + COLUNAS_SESSAO + " FROM CaixaSessoes WHERE CompanyId = ? AND Id = ?;";
        return jdbc.query(sql, MAPA_SESSAO, companyId, sessionId).stream().findFirst();
    }

    public Optional<CaixaSessao> obterSessaoAberta(String companyId, String operatorId) {
        String sql = "SELECT TOP 1 " + COLUNAS_SESSAO + """

                FROM CaixaSessoes
                WHERE CompanyId = ? AND OperatorId = ? AND ClosedAt IS NULL
                ORDER BY OpenedAt DESC;
                """;
        return jdbc.query(sql, MAPA_SESSAO, companyId, operatorId).stream().findFirst();
    }

    /** Total por forma de pagamento do turno, na ordem em que o SQL devolve (a chave ignora maiusculas, como no .NET). */
    public List<TotalPagamento> totaisPorFormaPagamento(String companyId, String caixaSessaoId) {
        List<TotalPagamento> totais = new ArrayList<>();
        jdbc.query(SQL_TOTAIS_POR_SESSAO, (RowMapper<Void>) (rs, n) -> {
            String tipo = texto(rs, "PaymentType");
            BigDecimal total = rs.getBigDecimal("Total");
            TotalPagamento novo = new TotalPagamento(tipo, total == null ? BigDecimal.ZERO : total);
            // Dictionary(OrdinalIgnoreCase): a repeticao troca o valor e mantem a grafia da primeira chave.
            for (int i = 0; i < totais.size(); i++) {
                if (totais.get(i).tipo().equalsIgnoreCase(tipo)) {
                    totais.set(i, new TotalPagamento(totais.get(i).tipo(), novo.total()));
                    return null;
                }
            }
            totais.add(novo);
            return null;
        }, companyId, caixaSessaoId, companyId, caixaSessaoId);
        return totais;
    }

    public List<CaixaMovimento> listarMovimentos(String companyId, String caixaSessaoId) {
        String sql = """
                SELECT Id, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName
                FROM CaixaMovimentos
                WHERE CompanyId = ? AND CaixaSessaoId = ?
                ORDER BY CreatedAt ASC;
                """;
        return jdbc.query(sql, MAPA_MOVIMENTO, companyId, caixaSessaoId);
    }

    /** Sangrias e reforcos em [de, ate); filtra por operador quando informado. */
    public List<CaixaMovimento> listarMovimentosPorPeriodo(String companyId, OffsetDateTime de, OffsetDateTime ate,
                                                           String operatorId) {
        String sql = """
                SELECT Id, CaixaSessaoId, Tipo, Valor, Motivo, CreatedAt, OperatorId, OperatorName
                FROM CaixaMovimentos
                WHERE CompanyId = ?
                  AND CreatedAt >= ? AND CreatedAt < ?
                  AND (? IS NULL OR OperatorId = ?)
                ORDER BY CreatedAt ASC;
                """;
        return jdbc.query(con -> {
            var ps = con.prepareStatement(sql);
            ps.setNString(1, companyId);
            ps.setObject(2, de);
            ps.setObject(3, ate);
            if (operatorId == null) {
                ps.setNull(4, java.sql.Types.NVARCHAR);
                ps.setNull(5, java.sql.Types.NVARCHAR);
            } else {
                ps.setNString(4, operatorId);
                ps.setNString(5, operatorId);
            }
            return ps;
        }, MAPA_MOVIMENTO);
    }

    public Optional<EventoProcessado> buscarEvento(String companyId, String eventId) {
        return jdbc.query(SQL_EVENTO, MAPA_EVENTO, companyId, eventId).stream().findFirst();
    }

    // ---------------------------------------------------------------- escritas (na transacao do chamador)

    public Optional<EventoProcessado> buscarEventoComBloqueio(String companyId, String eventId) {
        return jdbc.query(SQL_EVENTO_COM_BLOQUEIO, MAPA_EVENTO, companyId, eventId).stream().findFirst();
    }

    public boolean sessaoExisteComBloqueio(String companyId, String id) {
        String sql = """
                SELECT TOP 1 Id FROM CaixaSessoes WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = ? AND Id = ?;
                """;
        return !jdbc.queryForList(sql, String.class, companyId, id).isEmpty();
    }

    public boolean operadorTemCaixaAbertoComBloqueio(String companyId, String operatorId) {
        String sql = """
                SELECT TOP 1 Id
                FROM CaixaSessoes WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = ? AND OperatorId = ? AND ClosedAt IS NULL;
                """;
        return !jdbc.queryForList(sql, String.class, companyId, operatorId).isEmpty();
    }

    public void inserirSessao(String id, String companyId, OffsetDateTime openedAt, BigDecimal openingAmount,
                              String operatorId, String operatorName) {
        jdbc.update(SQL_INSERIR_SESSAO, id, companyId, openedAt, openingAmount, operatorId, operatorName);
    }

    public SituacaoTurno situacaoDoTurnoComBloqueio(String id, String companyId) {
        String sql = """
                SELECT ClosedAt
                FROM CaixaSessoes WITH (UPDLOCK, ROWLOCK)
                WHERE Id = ? AND CompanyId = ?;
                """;
        List<Boolean> fechado = jdbc.query(sql, (rs, n) -> rs.getObject(1, OffsetDateTime.class) != null, id, companyId);
        if (fechado.isEmpty()) return SituacaoTurno.INEXISTENTE;
        return fechado.get(0) ? SituacaoTurno.FECHADO : SituacaoTurno.ABERTO;
    }

    public void fecharSessao(String id, String companyId, OffsetDateTime closedAt, BigDecimal closingAmount,
                             String closedById, String closedByName, String note, BigDecimal expectedCashAmount,
                             BigDecimal differenceAmount, String differenceReason) {
        jdbc.update(SQL_FECHAR_SESSAO, closedAt, closingAmount, closedById, closedByName, note, expectedCashAmount,
                differenceAmount, differenceReason, id, companyId);
    }

    public boolean movimentoExiste(String companyId, String id) {
        return !jdbc.queryForList("SELECT Id FROM CaixaMovimentos WHERE CompanyId = ? AND Id = ?;", String.class,
                companyId, id).isEmpty();
    }

    public void inserirMovimento(String id, String companyId, String caixaSessaoId, int tipo, BigDecimal valor,
                                 String motivo, OffsetDateTime createdAt, String operatorId, String operatorName) {
        jdbc.update(SQL_INSERIR_MOVIMENTO, id, companyId, caixaSessaoId, tipo, valor, motivo, createdAt, operatorId,
                operatorName);
    }

    public void inserirAuditoria(String companyId, String userId, String userName, String eventType,
                                 String entityType, String entityId, String description, String ip) {
        jdbc.update(SQL_INSERIR_AUDITORIA, companyId, userId, userName, eventType, entityType, entityId, description, ip);
    }

    /** Insere o evento com o placeholder {@code {}} no ResponsePayload (atualizado depois do commit). */
    public void inserirEvento(String id, String companyId, String eventId, String eventType, String payloadHash,
                              OffsetDateTime processedAt) {
        jdbc.update(SQL_INSERIR_EVENTO, id, companyId, eventId, eventType, null, payloadHash, processedAt, "{}");
    }

    public void atualizarRespostaDoEvento(String id, String responsePayload) {
        jdbc.update("UPDATE ProcessedEvents SET ResponsePayload = ? WHERE Id = ?;", responsePayload, id);
    }

    public record TotalPagamento(String tipo, BigDecimal total) {}

    private static String texto(ResultSet rs, String coluna) throws SQLException {
        String valor = rs.getString(coluna);
        return valor == null ? "" : valor;
    }
}
