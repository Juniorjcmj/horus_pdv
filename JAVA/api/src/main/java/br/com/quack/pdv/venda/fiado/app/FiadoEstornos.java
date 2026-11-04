package br.com.quack.pdv.venda.fiado.app;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estorno de fiado na transacao do cancelamento/correcao da venda (FiadoAB.ReverseDebitAsync e
 * HistoricoVendasAB.EstornarFiadoDaVendaAsync). As mensagens das recusas sao os codigos que a .NET lanca no SQL.
 */
@Component
public class FiadoEstornos {

    /** Mensagem lancada quando o cliente ja pagou parte do fiado (saldo menor que o debito). */
    public static final String REVISAO_DE_ESTORNO = "CANCELLATION_REQUIRES_REFUND_REVIEW";

    /** Debito original de fiado de uma venda. */
    public record DebitoDaVenda(String clienteId, BigDecimal valor) {}

    private final JdbcTemplate jdbc;

    public FiadoEstornos(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Primeiro debito (Tipo = 1) da venda, se houver. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DebitoDaVenda> debitoDaVenda(String companyId, String vendaId) {
        return jdbc.query("SELECT TOP 1 ClienteId, Valor FROM FiadoMovimentos "
                + "WHERE VendaId = ? AND CompanyId = ? AND Tipo = 1;",
                (rs, n) -> new DebitoDaVenda(rs.getString(1), rs.getBigDecimal(2)), vendaId, companyId)
                .stream().findFirst();
    }

    /** EstornarFiadoDaVendaAsync: devolve o valor estornado (zero sem debito). */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal estornarFiadoDaVenda(String companyId, String vendaId, String operadorNome) {
        Optional<DebitoDaVenda> debito = debitoDaVenda(companyId, vendaId);
        if (debito.isEmpty() || debito.get().clienteId() == null || debito.get().clienteId().isEmpty()
                || debito.get().valor().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        reverterDebito(companyId, debito.get().clienteId(), vendaId, debito.get().valor(), operadorNome);
        return debito.get().valor();
    }

    /** ReverseDebitAsync: o mesmo roteiro do batch T-SQL da .NET. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverterDebito(String companyId, String clienteId, String vendaId, BigDecimal valor,
                               String operadorNome) {
        List<BigDecimal> saldos = jdbc.query("SELECT SaldoDevedor FROM Clientes WITH (UPDLOCK, HOLDLOCK) "
                + "WHERE CompanyId = ? AND Id = ?;", (rs, n) -> rs.getBigDecimal(1), companyId, clienteId);
        BigDecimal saldo = saldos.isEmpty() ? null : saldos.getFirst();
        Integer original = jdbc.queryForObject("SELECT COUNT(*) FROM FiadoMovimentos WHERE CompanyId = ? "
                + "AND ClienteId = ? AND VendaId = ? AND Tipo = 1 AND Valor = ?;", Integer.class, companyId,
                clienteId, vendaId, valor);
        if (original == null || original == 0) {
            throw new IllegalStateException("ORIGINAL_DEBIT_REQUIRED");
        }
        Integer jaEstornado = jdbc.queryForObject("SELECT COUNT(*) FROM FiadoMovimentos WHERE CompanyId = ? "
                + "AND VendaId = ? AND Tipo = 2;", Integer.class, companyId, vendaId);
        if (jaEstornado != null && jaEstornado > 0) {
            return;
        }
        if (saldo == null || saldo.compareTo(valor) < 0) {
            throw new IllegalStateException(REVISAO_DE_ESTORNO);
        }
        jdbc.update("UPDATE Clientes SET SaldoDevedor = SaldoDevedor - ? WHERE CompanyId = ? AND Id = ?;", valor,
                companyId, clienteId);
        jdbc.update("""
                INSERT INTO FiadoMovimentos(Id, CompanyId, ClienteId, Tipo, Valor, SaldoAnterior, SaldoAtual, VendaId,
                                            FormaPagamento, Observacao, OperadorNome, CriadoEm)
                VALUES(?, ?, ?, 2, ?, ?, ?, ?, 'Estorno', 'Estorno vinculado ao débito original', ?, SYSDATETIMEOFFSET());
                """, "fm-" + UUID.randomUUID().toString().replace("-", ""), companyId, clienteId, valor, saldo,
                saldo.subtract(valor), vendaId, operadorNome);
    }
}
