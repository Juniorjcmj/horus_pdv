package br.com.quack.pdv.venda.fiado.app;

import br.com.quack.pdv.venda.registro.app.SaleMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Debito de fiado na transacao da venda (FiadoAB.RegistrarDebitoAsync). */
@Component
public class FiadoDebitos {

    /** FiadoMovimentoAD do debito gravado. */
    public record Movimento(String id, String clienteId, String clienteNome, BigDecimal valor,
                            BigDecimal saldoAnterior, BigDecimal saldoAtual) {}

    private record ClienteLinha(String nome, BigDecimal limite, BigDecimal saldo) {}

    private final JdbcTemplate jdbc;
    private final CreditoGateway credito;
    private final Clock clock;

    public FiadoDebitos(JdbcTemplate jdbc, CreditoGateway credito, Clock clock) {
        this.jdbc = jdbc;
        this.credito = credito;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Movimento registrarDebito(String companyId, String clienteId, BigDecimal valor, String vendaId,
                                     String operadorNome, boolean creditoGarantido) {
        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("Valor da compra a prazo deve ser maior que zero.");
        }
        ClienteLinha c = jdbc.query("""
                SELECT CustomerName, Document, LimiteCredito, SaldoDevedor
                FROM Clientes WITH (UPDLOCK, ROWLOCK)
                WHERE Id = ? AND CompanyId = ?;
                """, (rs, n) -> new ClienteLinha(rs.getString("CustomerName"), rs.getBigDecimal("LimiteCredito"),
                rs.getBigDecimal("SaldoDevedor")), clienteId, companyId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Cliente " + clienteId + " não encontrado."));
        if (!creditoGarantido) {
            credito.exigirNaoBloqueado(companyId, clienteId);
            SaleMoney.credit(c.limite(), c.saldo().add(credito.delegado(companyId, clienteId)), valor);
        }
        BigDecimal saldoAtual = c.saldo().add(valor);
        OffsetDateTime agora = OffsetDateTime.now(clock).atZoneSameInstant(ZoneId.of("America/Sao_Paulo"))
                .toOffsetDateTime();
        String movId = "fm-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE Clientes SET SaldoDevedor = ? WHERE Id = ? AND CompanyId = ?;", saldoAtual, clienteId,
                companyId);
        jdbc.update("""
                INSERT INTO FiadoMovimentos
                    (Id, CompanyId, ClienteId, Tipo, Valor, SaldoAnterior, SaldoAtual, VendaId, FormaPagamento, Observacao, OperadorNome, CriadoEm)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, NULL, ?, ?, ?);
                """, movId, companyId, clienteId, valor, c.saldo(), saldoAtual, vendaId, "Compra a prazo (fiado)",
                operadorNome == null || operadorNome.isBlank() ? "Operador" : operadorNome, agora);
        return new Movimento(movId, clienteId, c.nome(), valor, c.saldo(), saldoAtual);
    }
}
