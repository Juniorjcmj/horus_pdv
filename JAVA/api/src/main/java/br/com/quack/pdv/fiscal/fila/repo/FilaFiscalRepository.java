package br.com.quack.pdv.fiscal.fila.repo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Consultas e gravacoes da fila fiscal minima (trechos de {@code DocumentoFiscalAB}: ObterVendasSemDocumentoFiscal,
 * ObterPendentes, ObterCancelamentoDaVenda, EncerrarSemEmissao, MarcarErro). So banco de dados: nenhuma chamada a
 * provedor fiscal ou a rede.
 */
@Repository
public class FilaFiscalRepository {

    /** Venda que decidiu emitir NFC-e mas ficou sem documento. */
    public record VendaSemDocumento(String companyId, String vendaId) {}

    /** Documento pendente da fila (DocumentoFiscalPendente, so o que a fila minima usa). */
    public record Pendente(String id, String companyId, String vendaId, int modelo, int serie, int numeroNf,
                           String xmlAssinado) {}

    /** Dados do cancelamento da venda da nota. */
    public record CancelamentoDaVenda(String saleNumber, String justificativa, String supervisorId,
                                      String supervisorNome, String operadorId, String operadorNome) {}

    private final JdbcTemplate jdbc;

    public FilaFiscalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VendaSemDocumento> vendasSemDocumento(int limite) {
        return jdbc.query("""
                SELECT TOP (?) v.CompanyId, v.Id
                  FROM Vendas v
                 WHERE v.EmitirNfce = 1
                   AND COALESCE(v.Status, 'finalizada') <> 'cancelada'
                   AND NOT EXISTS (SELECT 1 FROM DocumentosFiscais d
                                    WHERE d.CompanyId = v.CompanyId AND d.VendaId = v.Id AND d.Modelo = 65)
                 ORDER BY v.Id;
                """, (rs, n) -> new VendaSemDocumento(rs.getString(1), rs.getString(2)), limite);
    }

    /** Pendentes (status 1, 2 ou 8) vencidos em {@code agora}; nota de venda cancelada primeiro. */
    public List<Pendente> pendentes(int lote, OffsetDateTime agora) {
        return jdbc.query("""
                SELECT TOP (?) d.Id, d.CompanyId, d.VendaId, d.Modelo, d.Serie, d.NumeroNf, d.XmlAssinado
                FROM DocumentosFiscais d
                LEFT JOIN Vendas v ON v.Id = d.VendaId AND v.CompanyId = d.CompanyId
                WHERE d.Status IN (1, 2, 8)
                  AND (d.ProximaTentativaEm IS NULL OR d.ProximaTentativaEm <= ?)
                ORDER BY
                  CASE WHEN v.Status = 'cancelada' THEN 0 ELSE 1 END,
                  CASE WHEN d.Status = 8 THEN 0 ELSE 1 END,
                  d.CriadoEm;
                """, (rs, n) -> new Pendente(rs.getString("Id"), rs.getString("CompanyId"), rs.getString("VendaId"),
                rs.getInt("Modelo"), rs.getInt("Serie"), rs.getInt("NumeroNf"), rs.getString("XmlAssinado")),
                lote, agora);
    }

    public Optional<CancelamentoDaVenda> cancelamentoDaVenda(String companyId, String vendaId) {
        return jdbc.query("""
                SELECT SaleNumber, CanceladoJustificativa, CanceladoPorSupervisorId, CanceladoPorSupervisorNome,
                       CanceladoPorOperadorId, CanceladoPorOperadorNome
                FROM Vendas
                WHERE CompanyId = ? AND Id = ? AND Status = 'cancelada';
                """, (rs, n) -> new CancelamentoDaVenda(rs.getString(1),
                rs.getString(2) == null ? "Venda cancelada no caixa." : rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6)), companyId, vendaId).stream().findFirst();
    }

    /**
     * Tira da fila uma nota que nunca foi assinada (venda cancelada antes da emissao). So altera se ela ainda estiver
     * na fila e sem XML. Devolve se alterou.
     */
    public boolean encerrarSemEmissao(String companyId, String id, int status, String motivo, OffsetDateTime agora) {
        return jdbc.update("""
                UPDATE DocumentosFiscais
                   SET Status = ?, MotivoStatus = ?, UltimoErro = ?, ProximaTentativaEm = NULL, AtualizadoEm = ?
                 WHERE Id = ? AND CompanyId = ? AND Status IN (1, 2, 8)
                   AND (XmlAssinado IS NULL OR XmlAssinado = '');
                """, status, truncar(motivo, 500), truncar(motivo, 500), agora, id, companyId) == 1;
    }

    public void marcarErro(String id, String motivo, OffsetDateTime proximaTentativa, OffsetDateTime agora) {
        jdbc.update("""
                UPDATE DocumentosFiscais
                   SET Tentativas = Tentativas + 1, UltimoErro = ?, ProximaTentativaEm = ?, AtualizadoEm = ?
                 WHERE Id = ?;
                """, truncar(motivo, 1000), proximaTentativa, agora, id);
    }

    public void auditar(String companyId, String userId, String userName, String evento, String descricao,
                        String entityId) {
        jdbc.update("INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) "
                + "VALUES (?, ?, ?, ?, 'DocumentosFiscais', ?, ?, 'fila fiscal')", companyId, truncar(userId, 40),
                truncar(userName, 180), evento, truncar(entityId, 80), truncar(descricao, 500));
    }

    private static String truncar(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }
}
