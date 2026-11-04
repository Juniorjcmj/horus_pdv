package br.com.quack.pdv.venda.cancelamento.repo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acesso a dados da correcao de venda (trechos de {@code HistoricoVendasAB.CorrigirVendaAsync}). */
@Repository
public class CorrecaoRepository {

    /** Venda travada para a correcao. */
    public record VendaTravada(String id, String status, BigDecimal total, String customerName, String customerCpf,
                               String operatorName, String caixaSessaoId) {}

    /** Item da venda original. */
    public record ItemOriginal(String id, String productCode, String productName, BigDecimal quantity,
                               BigDecimal unitPrice, BigDecimal desconto, String promocaoId, BigDecimal costPrice,
                               BigDecimal itemTotal) {}

    private final JdbcTemplate jdbc;

    public CorrecaoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VendaTravada> travarVenda(String companyId, String saleNumber) {
        return jdbc.query("""
                SELECT Id, ISNULL(Status, 'finalizada') AS Status, TotalAmount, PaymentType,
                       CustomerName, CustomerCpf, OperatorName, CaixaSessaoId
                FROM Vendas WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = ? AND SaleNumber = ?;
                """, (rs, n) -> new VendaTravada(rs.getString("Id"), rs.getString("Status"),
                rs.getBigDecimal("TotalAmount"), nz(rs.getString("CustomerName")), nz(rs.getString("CustomerCpf")),
                nz(rs.getString("OperatorName")), rs.getString("CaixaSessaoId")), companyId, saleNumber)
                .stream().findFirst();
    }

    public List<ItemOriginal> itens(String companyId, String vendaId) {
        return jdbc.query("""
                SELECT vi.Id, vi.ProductCode, vi.ProductName, vi.Quantity, vi.UnitPrice, vi.Desconto, vi.PromocaoId,
                       vi.CostPrice, vi.ItemTotal
                FROM VendaItens vi
                INNER JOIN Vendas v ON v.Id = vi.VendaId
                WHERE vi.VendaId = ? AND v.CompanyId = ?
                ORDER BY vi.Id;
                """, (rs, n) -> new ItemOriginal(nz(rs.getString("Id")), nz(rs.getString("ProductCode")),
                nz(rs.getString("ProductName")), rs.getBigDecimal("Quantity"), rs.getBigDecimal("UnitPrice"),
                orZero(rs.getBigDecimal("Desconto")), rs.getString("PromocaoId"),
                orZero(rs.getBigDecimal("CostPrice")), rs.getBigDecimal("ItemTotal")), vendaId, companyId);
    }

    public void marcarCorrigida(String companyId, String vendaId, OffsetDateTime quando, String responsavelId,
                                String responsavelNome, String justificativa) {
        jdbc.update("""
                UPDATE Vendas
                SET Status = 'corrigida',
                    CanceladoEm = ?,
                    CanceladoPorOperadorId = ?,
                    CanceladoPorOperadorNome = ?,
                    CanceladoPorSupervisorId = ?,
                    CanceladoPorSupervisorNome = ?,
                    CanceladoJustificativa = ?
                WHERE Id = ? AND CompanyId = ?;
                """, quando, responsavelId, responsavelNome, responsavelId, responsavelNome, justificativa, vendaId,
                companyId);
    }

    /**
     * Esquisitice copiada da .NET (D12): a correcao poe os documentos fiscais da venda original em Status = 3, que no
     * enum e "Autorizado" (nao "Cancelado" = 6), sem tocar nos que ja estao em 3.
     */
    public void marcarDocumentosDaOriginal(String companyId, String vendaId, OffsetDateTime quando,
                                           String responsavelId, String responsavelNome, String justificativa) {
        jdbc.update("""
                UPDATE DocumentosFiscais
                SET Status = 3,
                    CanceladoPorSupervisorId = ?,
                    CanceladoPorSupervisorNome = ?,
                    CanceladoPorOperador = ?,
                    CanceladoJustificativa = ?,
                    AtualizadoEm = ?
                WHERE VendaId = ? AND CompanyId = ? AND Status <> 3;
                """, responsavelId, responsavelNome, responsavelNome, justificativa, quando, vendaId, companyId);
    }

    public void vincular(String companyId, String originalId, String novaId) {
        jdbc.update("UPDATE Vendas SET CorrigidaParaVendaId = ? WHERE Id = ? AND CompanyId = ?;", novaId, originalId,
                companyId);
        jdbc.update("UPDATE Vendas SET CorrigidaDeVendaId = ? WHERE Id = ? AND CompanyId = ?;", originalId, novaId,
                companyId);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
