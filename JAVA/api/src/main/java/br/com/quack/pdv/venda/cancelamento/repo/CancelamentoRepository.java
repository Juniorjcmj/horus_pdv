package br.com.quack.pdv.venda.cancelamento.repo;

import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.venda.cancelamento.domain.TrilhaLinha;
import br.com.quack.pdv.venda.cancelamento.domain.TrilhaRegistro;
import br.com.quack.pdv.venda.cancelamento.domain.VendaCanceladaResumo;
import br.com.quack.pdv.venda.cancelamento.domain.VendaSnapshot;
import br.com.quack.pdv.venda.registro.domain.VendaHistoricoDto;
import br.com.quack.pdv.venda.registro.repo.VendaRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados do cancelamento: porta de {@code VendaCancelamentoAB} (trilha) e dos trechos de
 * {@code HistoricoVendasAB} (venda cancelada, lista de cancelamentos). Mesmo SQL e mesmas dicas de bloqueio.
 */
@Repository
public class CancelamentoRepository {

    private static final ObjectMapper LEITOR = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final JdbcTemplate jdbc;

    public CancelamentoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- venda

    /** Trava a venda pelo numero (dois pedidos ao mesmo tempo nao estornam duas vezes). */
    public Optional<String> travarVendaPorNumero(String companyId, String saleNumber) {
        return jdbc.query("SELECT Id FROM Vendas WITH (UPDLOCK, ROWLOCK) WHERE CompanyId = ? AND SaleNumber = ?;",
                (rs, n) -> rs.getString(1), companyId, saleNumber).stream().findFirst();
    }

    /** CapturarAsync: le a venda (itens, pagamentos, notas e turno). Com vendaId busca por ele; senao pelo numero. */
    public Optional<VendaSnapshot> capturar(String companyId, String vendaId, String saleNumber) {
        record Cab(String id, String number, String status, BigDecimal total, String cliente, String cpf,
                   String sessao) {}
        String sql = """
                SELECT Id, SaleNumber, ISNULL(Status, 'finalizada') AS Status, TotalAmount, CustomerName,
                       CustomerCpf, CaixaSessaoId
                FROM Vendas
                WHERE CompanyId = ? AND %s;
                """.formatted(vendaId != null ? "Id = ?" : "SaleNumber = ?");
        Optional<Cab> cab = jdbc.query(sql, (rs, n) -> new Cab(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getBigDecimal(4), rs.getString(5), rs.getString(6), rs.getString(7)), companyId,
                vendaId != null ? vendaId : saleNumber).stream().findFirst();
        if (cab.isEmpty()) return Optional.empty();
        Cab c = cab.get();

        List<VendaSnapshot.Item> itens = jdbc.query("""
                SELECT ISNULL(ProductCode, ''), ISNULL(ProductName, ''), ISNULL(Quantity, 0), ISNULL(UnitPrice, 0),
                       ISNULL(ItemTotal, 0)
                FROM VendaItens WHERE VendaId = ? ORDER BY Id;
                """, (rs, n) -> new VendaSnapshot.Item(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), c.id());
        List<VendaSnapshot.Pagamento> pagamentos = jdbc.query("""
                SELECT ISNULL(PaymentType, ''), ISNULL(Amount, 0), CashGiven, ChangeAmount
                FROM VendaPagamentos WHERE CompanyId = ? AND VendaId = ? ORDER BY CreatedAt, Id;
                """, (rs, n) -> new VendaSnapshot.Pagamento(rs.getString(1), rs.getBigDecimal(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4)), companyId, c.id());
        List<VendaSnapshot.Nota> notas = jdbc.query("""
                SELECT Id, Modelo, CAST(NumeroNf AS NVARCHAR(20)), CAST(Serie AS NVARCHAR(10)), Status, ChaveAcesso
                FROM DocumentosFiscais WHERE CompanyId = ? AND VendaId = ? ORDER BY CriadoEm, Id;
                """, (rs, n) -> new VendaSnapshot.Nota(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getInt(5), rs.getString(6)), companyId, c.id());
        String sessaoStatus = null;
        if (c.sessao() != null && !c.sessao().isBlank()) {
            sessaoStatus = jdbc.query("SELECT CASE WHEN ClosedAt IS NULL THEN 'aberta' ELSE 'fechada' END "
                    + "FROM CaixaSessoes WHERE CompanyId = ? AND Id = ?;", (rs, n) -> rs.getString(1), companyId,
                    c.sessao()).stream().findFirst().orElse(null);
        }
        return Optional.of(new VendaSnapshot(c.id(), c.number(), c.status(), c.total(), c.cliente(), c.cpf(),
                c.sessao(), sessaoStatus, itens, pagamentos, notas));
    }

    public void marcarCancelada(String companyId, String vendaId, OffsetDateTime quando, String operadorId,
                                String operadorNome, String supervisorId, String supervisorNome,
                                String justificativa) {
        jdbc.update("""
                UPDATE Vendas
                SET Status = 'cancelada',
                    CanceladoEm = ?,
                    CanceladoPorOperadorId = ?,
                    CanceladoPorOperadorNome = ?,
                    CanceladoPorSupervisorId = ?,
                    CanceladoPorSupervisorNome = ?,
                    CanceladoJustificativa = ?
                WHERE Id = ? AND CompanyId = ?;
                """, quando, operadorId, operadorNome, supervisorId, supervisorNome, justificativa, vendaId,
                companyId);
    }

    // ---------------------------------------------------------------- trilha

    public void inserirTrilha(TrilhaRegistro r) {
        VendaSnapshot s = r.snapshot();
        jdbc.update("""
                INSERT INTO VendaCancelamentos
                    (Id, CompanyId, Resultado, Via, MotivoRecusa, VendaId, SaleNumber, OperadorId, OperadorNome,
                     SupervisorId, SupervisorNome, Justificativa, Terminal, Ip, CaixaSessaoId, CaixaSessaoStatus,
                     TotalVenda, ClienteNome, ClienteCpf, ItensEstornados, FiadoEstornado, Snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """, "vc-" + UUID.randomUUID().toString().replace("-", ""), r.companyId(), r.resultado(), r.via(),
                db(truncar(r.motivoRecusa(), 500)), db(s == null ? null : s.vendaId()),
                db(s != null ? s.saleNumber() : truncar(r.saleNumber(), 40)), r.operadorId(),
                orEmpty(truncar(r.operadorNome(), 180)), db(truncar(r.supervisorId(), 40)),
                db(truncar(r.supervisorNome(), 180)), db(truncar(r.justificativa(), 500)),
                db(truncar(r.terminal(), 80)), db(truncar(r.ip(), 64)), db(s == null ? null : s.caixaSessaoId()),
                db(s == null ? null : s.caixaSessaoStatus()), s == null ? null : s.totalVenda(),
                db(s == null ? null : s.clienteNome()), db(s == null ? null : s.clienteCpf()),
                r.itensEstornados(), r.fiadoEstornado(), db(s == null ? null : s.toJson()));
    }

    /** AuditLog da .NET com usuario e IP (descricao cortada em 500). */
    public void inserirAuditLog(String companyId, String userId, String userName, String eventType,
                                String description, String entityType, String entityId, String ip) {
        jdbc.update("INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", companyId, orEmpty(truncar(userId, 40)),
                orEmpty(truncar(userName, 180)), eventType, entityType, entityId == null ? "" : truncar(entityId, 80),
                orEmpty(truncar(description, 500)), ip == null || ip.isBlank() ? null : truncar(ip, 64));
    }

    public Optional<String> nomeDoUsuario(String companyId, String usuarioId) {
        return jdbc.query("SELECT Name FROM Usuarios WHERE CompanyId = ? AND Id = ?;", (rs, n) -> rs.getString(1),
                companyId, usuarioId).stream().findFirst();
    }

    /** VendaCancelamentoAB.ListarAsync: trilha completa, mais recentes primeiro (ate 2000 linhas). */
    public List<TrilhaLinha> listarTrilha(String companyId, OffsetDateTime de, OffsetDateTime ate, String venda) {
        return jdbc.query("""
                SELECT TOP 2000 Id, OcorridoEm, Resultado, Via, MotivoRecusa, SaleNumber, OperadorId, OperadorNome,
                       SupervisorId, SupervisorNome, Justificativa, Terminal, Ip, CaixaSessaoId, CaixaSessaoStatus,
                       TotalVenda, ClienteNome, ClienteCpf, ItensEstornados, FiadoEstornado, Snapshot
                FROM VendaCancelamentos
                WHERE CompanyId = ?
                  AND (CAST(? AS DATETIMEOFFSET) IS NULL OR OcorridoEm >= CAST(? AS DATETIMEOFFSET))
                  AND (CAST(? AS DATETIMEOFFSET) IS NULL OR OcorridoEm < CAST(? AS DATETIMEOFFSET))
                  AND (? IS NULL OR SaleNumber = ?)
                ORDER BY OcorridoEm DESC;
                """, (rs, n) -> new TrilhaLinha(rs.getString(1), rs.getObject(2, OffsetDateTime.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getString(15), rs.getBigDecimal(16), rs.getString(17),
                rs.getString(18), rs.getObject(19) == null ? null : rs.getInt(19),
                rs.getBigDecimal(20), lerSnapshot(rs.getString(21))),
                companyId, de, de, ate, ate, venda, venda);
    }

    private static JsonNode lerSnapshot(String texto) {
        if (texto == null) return null;
        try {
            return LEITOR.readTree(texto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot da trilha de cancelamentos invalido.", e);
        }
    }

    /** ListarCancelamentosAsync: vendas canceladas com itens, por data de cancelamento decrescente. */
    public List<VendaCanceladaResumo> listarCancelamentos(String companyId, OffsetDateTime de, OffsetDateTime ate) {
        record Linha(String vendaId, String saleNumber, String customerName, String customerCpf, String paymentType,
                     BigDecimal totalAmount, String operatorName, OffsetDateTime saleDate, OffsetDateTime canceladoEm,
                     String operador, String supervisor, String justificativa, String productCode,
                     String productName, BigDecimal quantity, BigDecimal unitPrice, BigDecimal itemTotal) {}
        List<Linha> linhas = jdbc.query("""
                SELECT v.Id AS VendaId, v.SaleNumber, ISNULL(v.Status, 'finalizada') AS Status,
                       v.CustomerName, v.CustomerCpf, v.PaymentType, v.TotalAmount, v.OperatorName,
                       v.SaleDate, v.CanceladoEm, v.CanceladoPorOperadorNome, v.CanceladoPorSupervisorNome,
                       v.CanceladoJustificativa,
                       ISNULL(i.ProductCode, '') AS ProductCode,
                       ISNULL(i.ProductName, 'Item') AS ProductName,
                       ISNULL(i.Quantity, 1) AS Quantity,
                       ISNULL(i.UnitPrice, v.TotalAmount) AS UnitPrice,
                       ISNULL(i.ItemTotal, v.TotalAmount) AS ItemTotal
                FROM Vendas v
                LEFT JOIN VendaItens i ON v.Id = i.VendaId
                WHERE v.CompanyId = ?
                  AND v.Status = 'cancelada'
                  AND (CAST(? AS DATETIMEOFFSET) IS NULL OR v.CanceladoEm >= CAST(? AS DATETIMEOFFSET)
                       OR (v.CanceladoEm IS NULL AND v.SaleDate >= CAST(? AS DATETIMEOFFSET)))
                  AND (CAST(? AS DATETIMEOFFSET) IS NULL OR v.CanceladoEm <= CAST(? AS DATETIMEOFFSET)
                       OR (v.CanceladoEm IS NULL AND v.SaleDate <= CAST(? AS DATETIMEOFFSET)))
                ORDER BY COALESCE(v.CanceladoEm, v.SaleDate) DESC, v.Id, i.Id;
                """, (rs, n) -> new Linha(rs.getString("VendaId"), nz(rs.getString("SaleNumber")),
                nz(rs.getString("CustomerName")), nz(rs.getString("CustomerCpf")), nz(rs.getString("PaymentType")),
                zero(rs.getBigDecimal("TotalAmount")), nz(rs.getString("OperatorName")),
                rs.getObject("SaleDate", OffsetDateTime.class), rs.getObject("CanceladoEm", OffsetDateTime.class),
                rs.getString("CanceladoPorOperadorNome"), rs.getString("CanceladoPorSupervisorNome"),
                rs.getString("CanceladoJustificativa"), nz(rs.getString("ProductCode")),
                nz(rs.getString("ProductName")), zero(rs.getBigDecimal("Quantity")),
                zero(rs.getBigDecimal("UnitPrice")), zero(rs.getBigDecimal("ItemTotal"))),
                companyId, de, de, de, ate, ate, ate);

        Map<String, List<Linha>> porVenda = new LinkedHashMap<>();
        for (Linha l : linhas) {
            porVenda.computeIfAbsent(l.saleNumber(), k -> new ArrayList<>()).add(l);
        }
        List<VendaCanceladaResumo> resumos = new ArrayList<>();
        for (List<Linha> grupo : porVenda.values()) {
            Linha p = grupo.getFirst();
            List<VendaHistoricoDto> itens = new ArrayList<>();
            BigDecimal quantidadeTotal = BigDecimal.ZERO;
            for (Linha l : grupo) {
                if (l.productCode().isEmpty()) continue;
                quantidadeTotal = quantidadeTotal.add(l.quantity());
                itens.add(new VendaHistoricoDto(p.saleNumber(), "finalizada", "", "", "", "", "", l.productCode(),
                        l.productName(), l.quantity(), Dinheiro.formatarPrecoUnitario(l.unitPrice()),
                        BigDecimal.ZERO, null, Dinheiro.formatarSemSimbolo(l.itemTotal()), "", null, null, null,
                        null, null, null, null, null, null, null, null, null, null));
            }
            String resumo = String.join("; ", itens.stream()
                    .map(x -> x.quantity().toPlainString() + "x " + x.productName()).toList());
            resumos.add(new VendaCanceladaResumo(p.vendaId(), p.saleNumber(), p.customerName(), p.customerCpf(),
                    p.paymentType(), Dinheiro.formatarSemSimbolo(p.totalAmount()), p.operatorName(),
                    p.saleDate() == null ? "-" : VendaRepository.formatarData(p.saleDate()),
                    p.canceladoEm() == null ? "-" : VendaRepository.formatarData(p.canceladoEm()),
                    p.operador() == null ? "-" : p.operador(), p.supervisor() == null ? "-" : p.supervisor(),
                    p.justificativa() == null ? "-" : p.justificativa(), itens.size(), quantidadeTotal, resumo,
                    itens));
        }
        return resumos;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static Object db(String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    private static String truncar(String valor, int max) {
        return valor == null ? null : valor.length() <= max ? valor : valor.substring(0, max);
    }
}
