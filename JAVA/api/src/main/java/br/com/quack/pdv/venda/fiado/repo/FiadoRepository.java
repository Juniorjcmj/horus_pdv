package br.com.quack.pdv.venda.fiado.repo;

import br.com.quack.pdv.venda.fiado.domain.FiadoDtos;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Acesso a dados do fiado: porta literal do FiadoAB (mesmo SQL e mesmas dicas de bloqueio). */
@Repository
public class FiadoRepository {

    /** Cliente travado para o recebimento. */
    public record ClienteSaldo(String nome, String documento, BigDecimal saldo) {}

    /** Linha do resumo: saldo e data da ultima compra a prazo. */
    public record SaldoUltimaCompra(BigDecimal saldo, OffsetDateTime ultimaCompra) {}

    /** Totais gerais do resumo. */
    public record Totais(BigDecimal totalAReceber, int quantidadeDevedores, BigDecimal maiorDebito) {}

    private final JdbcTemplate jdbc;

    public FiadoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ClienteSaldo> travarCliente(String companyId, String clienteId) {
        return jdbc.query("""
                SELECT CustomerName, Document, SaldoDevedor
                FROM Clientes WITH (UPDLOCK, ROWLOCK)
                WHERE Id = ? AND CompanyId = ?;
                """, (rs, n) -> new ClienteSaldo(rs.getString(1), rs.getString(2), rs.getBigDecimal(3)),
                clienteId, companyId).stream().findFirst();
    }

    public void atualizarSaldo(String companyId, String clienteId, BigDecimal saldo) {
        jdbc.update("UPDATE Clientes SET SaldoDevedor = ? WHERE Id = ? AND CompanyId = ?;", saldo, clienteId,
                companyId);
    }

    public void inserirCredito(String id, String companyId, String clienteId, BigDecimal valor, BigDecimal anterior,
                               BigDecimal atual, String forma, String observacao, String operador,
                               OffsetDateTime criadoEm) {
        jdbc.update("""
                INSERT INTO FiadoMovimentos
                    (Id, CompanyId, ClienteId, Tipo, Valor, SaldoAnterior, SaldoAtual, VendaId, FormaPagamento, Observacao, OperadorNome, CriadoEm)
                VALUES (?, ?, ?, 2, ?, ?, ?, NULL, ?, ?, ?, ?);
                """, id, companyId, clienteId, valor, anterior, atual, forma, observacao, operador, criadoEm);
    }

    public List<FiadoDtos.Devedor> devedores(String companyId, String busca, java.util.function.Function<
            OffsetDateTime, Integer> dias) {
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        String filtro = "";
        if (busca != null) {
            filtro = " AND (c.CustomerName LIKE '%' + ? + '%' OR c.Document LIKE '%' + ? + '%')";
            args.add(busca);
            args.add(busca);
        }
        return jdbc.query("""
                SELECT c.Id AS ClienteId, c.CustomerName, c.Document, c.Telephone, c.Cellphone,
                       c.LimiteCredito, c.SaldoDevedor,
                       (SELECT MAX(m.CriadoEm) FROM FiadoMovimentos m WHERE m.CompanyId = c.CompanyId AND m.ClienteId = c.Id AND m.Tipo = 1) AS UltimaCompra
                FROM Clientes c
                WHERE c.CompanyId = ?
                  AND c.SaldoDevedor > 0""" + filtro + """

                ORDER BY c.SaldoDevedor DESC;
                """, (rs, n) -> {
                    OffsetDateTime ultima = rs.getObject("UltimaCompra", OffsetDateTime.class);
                    return new FiadoDtos.Devedor(rs.getString("ClienteId"), rs.getString("CustomerName"),
                            rs.getString("Document"), rs.getString("Telephone"), rs.getString("Cellphone"),
                            rs.getBigDecimal("LimiteCredito"), rs.getBigDecimal("SaldoDevedor"), ultima,
                            ultima == null ? null : dias.apply(ultima));
                }, args.toArray());
    }

    public List<FiadoDtos.Movimento> extrato(String companyId, String clienteId, OffsetDateTime inicio,
                                             OffsetDateTime fim) {
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(clienteId);
        StringBuilder filtros = new StringBuilder();
        if (inicio != null) {
            filtros.append(" AND m.CriadoEm >= ?");
            args.add(inicio);
        }
        if (fim != null) {
            filtros.append(" AND m.CriadoEm <= ?");
            args.add(fim);
        }
        return jdbc.query("""
                SELECT m.Id, m.CompanyId, m.ClienteId, c.CustomerName AS ClienteNome, c.Document AS ClienteDocument,
                       m.Tipo, m.Valor, m.SaldoAnterior, m.SaldoAtual, m.VendaId, m.FormaPagamento, m.Observacao,
                       m.OperadorNome, m.CriadoEm
                FROM FiadoMovimentos m
                INNER JOIN Clientes c ON c.Id = m.ClienteId
                WHERE m.CompanyId = ?
                  AND m.ClienteId = ?""" + filtros + """

                ORDER BY m.CriadoEm DESC;
                """, (rs, n) -> new FiadoDtos.Movimento(rs.getString("Id"), rs.getString("CompanyId"),
                rs.getString("ClienteId"), rs.getString("ClienteNome"), rs.getString("ClienteDocument"),
                rs.getInt("Tipo"), rs.getBigDecimal("Valor"), rs.getBigDecimal("SaldoAnterior"),
                rs.getBigDecimal("SaldoAtual"), rs.getString("VendaId"), rs.getString("FormaPagamento"),
                rs.getString("Observacao"), rs.getString("OperadorNome"),
                rs.getObject("CriadoEm", OffsetDateTime.class)), args.toArray());
    }

    public Totais totais(String companyId) {
        return jdbc.queryForObject("""
                SELECT ISNULL(SUM(c.SaldoDevedor), 0), COUNT(CASE WHEN c.SaldoDevedor > 0 THEN 1 END),
                       ISNULL(MAX(c.SaldoDevedor), 0)
                FROM Clientes c WHERE c.CompanyId = ?;
                """, (rs, n) -> new Totais(rs.getBigDecimal(1), rs.getInt(2), rs.getBigDecimal(3)), companyId);
    }

    public List<SaldoUltimaCompra> saldosComUltimaCompra(String companyId) {
        return jdbc.query("""
                SELECT c.SaldoDevedor,
                       (SELECT MAX(m.CriadoEm) FROM FiadoMovimentos m WHERE m.CompanyId = c.CompanyId AND m.ClienteId = c.Id AND m.Tipo = 1) AS UltimaCompra
                FROM Clientes c
                WHERE c.CompanyId = ? AND c.SaldoDevedor > 0;
                """, (rs, n) -> new SaldoUltimaCompra(rs.getBigDecimal(1), rs.getObject(2, OffsetDateTime.class)),
                companyId);
    }
}
