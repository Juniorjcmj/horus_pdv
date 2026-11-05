package br.com.quack.pdv.venda.registro.repo;

import br.com.quack.pdv.shared.Dinheiro;
import br.com.quack.pdv.venda.registro.domain.VendaHistoricoDto;
import br.com.quack.pdv.venda.registro.domain.VendaPagamentoDto;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Acesso a dados da venda: porta literal de {@code HistoricoVendasAB} (mesmo SQL e mesmas dicas de bloqueio). As
 * escritas rodam na transacao de quem chama.
 */
@Repository
public class VendaRepository {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** Evento ja processado, lido com bloqueio. */
    public record EventoProcessado(String payloadHash, String responsePayload) {}

    /** Pedido travado para a venda. */
    public record PedidoTravado(String id, String customerCpf, int status) {}

    /** Linha de item de pedido. */
    public record PedidoItem(String productCode, String productName, BigDecimal quantity, BigDecimal unitPrice,
                             BigDecimal itemTotal) {}

    /** Pedido travado pelo Id para a finalizacao com precos fixos. */
    public record PedidoPorId(int status, String vendaId) {}

    /** Cliente travado para o fiado. */
    public record ClienteFiado(String id, BigDecimal limiteCredito, BigDecimal saldoDevedor, String customerName) {}

    /** Produto lido (e travado) para a baixa. */
    public record ProdutoVenda(String id, String productName, BigDecimal estoque, BigDecimal custo,
                               BigDecimal precoVenda) {}

    private static final String SQL_LISTAR = """
            SELECT v.SaleNumber, ISNULL(v.Status, 'finalizada') AS Status,
                   v.CustomerName, v.CustomerCpf, v.PaymentType,
                   v.TotalAmount, v.OperatorName, v.SaleDate, v.ClientSaleId, v.OfflineReference,
                   v.CanceladoEm, v.CanceladoPorOperadorNome, v.CanceladoPorSupervisorNome, v.CanceladoJustificativa,
                   ISNULL(i.ProductCode, '') AS ProductCode,
                   ISNULL(i.ProductName, 'Item') AS ProductName,
                   ISNULL(i.Quantity, 1) AS Quantity,
                   ISNULL(i.UnitPrice, v.TotalAmount) AS UnitPrice,
                   ISNULL(i.ItemTotal, v.TotalAmount) AS ItemTotal,
                   ISNULL(i.Desconto, 0) AS Desconto,
                   i.PromocaoId,
                   d.Id AS FiscalDocId, d.Modelo AS FiscalModelo, d.NumeroNf AS FiscalNumeroNf,
                   d.Serie AS FiscalSerie, d.Status AS FiscalStatus, d.ChaveAcesso AS FiscalChaveAcesso,
                   pay.Breakdown AS PaymentBreakdown
            FROM Vendas v
            LEFT JOIN VendaItens i ON v.Id = i.VendaId
            OUTER APPLY (
                SELECT TOP 1 doc.Id, doc.Modelo, doc.NumeroNf, doc.Serie, doc.Status, doc.ChaveAcesso
                FROM DocumentosFiscais doc
                WHERE doc.CompanyId = v.CompanyId AND doc.VendaId = v.Id
                ORDER BY doc.CriadoEm DESC
            ) d
            OUTER APPLY (
                SELECT STUFF((
                    SELECT ';' + LOWER(pg.PaymentType) + '=' + CONVERT(VARCHAR(32), pg.Amount)
                    FROM VendaPagamentos pg
                    WHERE pg.CompanyId = v.CompanyId AND pg.VendaId = v.Id
                    ORDER BY pg.CreatedAt, pg.Id
                    FOR XML PATH(''), TYPE).value('.', 'NVARCHAR(MAX)'), 1, 1, '') AS Breakdown
            ) pay
            WHERE v.CompanyId = ?
            %FILTROS%
            ORDER BY v.SaleDate DESC;
            """;

    private static final RowMapper<VendaHistoricoDto> MAPA_LINHA = (rs, n) -> new VendaHistoricoDto(
            texto(rs, "SaleNumber"),
            textoOu(rs, "Status", "finalizada"),
            texto(rs, "CustomerName"),
            texto(rs, "CustomerCpf"),
            texto(rs, "PaymentType"),
            Dinheiro.formatarSemSimbolo(decimal(rs, "TotalAmount")),
            texto(rs, "OperatorName"),
            texto(rs, "ProductCode"),
            texto(rs, "ProductName"),
            decimal(rs, "Quantity"),
            Dinheiro.formatarPrecoUnitario(decimal(rs, "UnitPrice")),
            decimal(rs, "Desconto"),
            rs.getString("PromocaoId"),
            Dinheiro.formatarSemSimbolo(decimal(rs, "ItemTotal")),
            dataOu(rs, "SaleDate", "-"),
            rs.getString("ClientSaleId"),
            rs.getString("OfflineReference"),
            rs.getString("FiscalDocId"),
            inteiro(rs, "FiscalModelo"),
            inteiro(rs, "FiscalNumeroNf"),
            inteiro(rs, "FiscalSerie"),
            inteiro(rs, "FiscalStatus"),
            rs.getString("FiscalChaveAcesso"),
            rs.getString("PaymentBreakdown"),
            dataOu(rs, "CanceladoEm", null),
            rs.getString("CanceladoPorOperadorNome"),
            rs.getString("CanceladoPorSupervisorNome"),
            rs.getString("CanceladoJustificativa"));

    private final JdbcTemplate jdbc;

    public VendaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- formatos

    /** HorusDateTime.Format: dd/MM/yyyy HH:mm:ss no fuso de Brasilia. */
    public static String formatarData(OffsetDateTime data) {
        return data.atZoneSameInstant(BRASILIA).format(DATA_HORA);
    }

    private static String texto(ResultSet rs, String col) throws SQLException {
        return textoOu(rs, col, "");
    }

    private static String textoOu(ResultSet rs, String col, String padrao) throws SQLException {
        String v = rs.getString(col);
        return v == null ? padrao : v;
    }

    private static BigDecimal decimal(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Integer inteiro(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : ((Number) v).intValue();
    }

    private static String dataOu(ResultSet rs, String col, String padrao) throws SQLException {
        OffsetDateTime d = rs.getObject(col, OffsetDateTime.class);
        return d == null ? padrao : formatarData(d);
    }

    // ---------------------------------------------------------------- consultas

    public List<VendaHistoricoDto> listar(String companyId, String saleNumber, OffsetDateTime desde,
                                          String caixaSessaoId) {
        String numero = saleNumber == null || saleNumber.isBlank() ? null : saleNumber;
        String sessao = caixaSessaoId == null || caixaSessaoId.isBlank() ? null : caixaSessaoId.trim();
        // Filtros opcionais so entram no SQL quando informados (evita parametro nulo sem tipo no SQL Server).
        StringBuilder filtros = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        args.add(companyId);
        if (numero != null) {
            filtros.append(" AND v.SaleNumber = ?");
            args.add(numero);
        }
        if (desde != null) {
            filtros.append(" AND v.SaleDate >= ?");
            args.add(desde);
        }
        if (sessao != null) {
            filtros.append(" AND v.CaixaSessaoId = ?");
            args.add(sessao);
        }
        return jdbc.query(SQL_LISTAR.replace("%FILTROS%", filtros), MAPA_LINHA, args.toArray());
    }

    /** Venda ja gravada com este ClientSaleId (id gerado no caixa): {vendaId, saleNumber}, ou vazio. */
    public Optional<String[]> vendaPorClientSaleId(String companyId, String clientSaleId) {
        return jdbc.query("SELECT Id, SaleNumber FROM Vendas WHERE CompanyId = ? AND ClientSaleId = ?;",
                (rs, n) -> new String[] {rs.getString(1), rs.getString(2)}, companyId, clientSaleId)
                .stream().findFirst();
    }

    /** Saldo do cliente logo depois do primeiro movimento de fiado da venda: {clienteId, saldoAtual}, ou vazio. */
    public Optional<Object[]> fiadoDaVenda(String companyId, String vendaId) {
        return jdbc.query("""
                SELECT TOP 1 ClienteId, SaldoAtual FROM FiadoMovimentos
                WHERE CompanyId = ? AND VendaId = ? ORDER BY CriadoEm ASC, Id ASC;
                """, (rs, n) -> new Object[] {rs.getString(1), rs.getBigDecimal(2)}, companyId, vendaId)
                .stream().findFirst();
    }

    public String vendaIdPorNumero(String companyId, String saleNumber) {
        List<String> ids = jdbc.query("SELECT Id FROM Vendas WHERE CompanyId = ? AND SaleNumber = ?;",
                (rs, n) -> rs.getString(1), companyId, saleNumber);
        return ids.isEmpty() || ids.getFirst() == null ? "" : ids.getFirst();
    }

    /** Linhas de uma venda pelo Id (ObterPorIdAsync). */
    public List<VendaHistoricoDto> porVendaId(String companyId, String vendaId) {
        return jdbc.query("""
                SELECT v.SaleNumber, v.CustomerName, v.CustomerCpf, v.PaymentType,
                       v.TotalAmount, v.OperatorName, v.SaleDate,
                       i.ProductCode, i.ProductName, i.Quantity, i.UnitPrice, i.ItemTotal,
                       i.Desconto, i.PromocaoId
                FROM VendaItens i
                INNER JOIN Vendas v ON v.Id = i.VendaId
                WHERE v.CompanyId = ? AND v.Id = ?
                ORDER BY i.Id;
                """, (rs, n) -> new VendaHistoricoDto(
                texto(rs, "SaleNumber"), "finalizada", texto(rs, "CustomerName"), texto(rs, "CustomerCpf"),
                texto(rs, "PaymentType"), Dinheiro.formatarSemSimbolo(decimal(rs, "TotalAmount")),
                texto(rs, "OperatorName"), texto(rs, "ProductCode"), texto(rs, "ProductName"),
                decimal(rs, "Quantity"), Dinheiro.formatarPrecoUnitario(decimal(rs, "UnitPrice")),
                decimal(rs, "Desconto"), rs.getString("PromocaoId"),
                Dinheiro.formatarSemSimbolo(decimal(rs, "ItemTotal")), dataOu(rs, "SaleDate", "-"),
                null, null, null, null, null, null, null, null, null, null, null, null, null),
                companyId, vendaId);
    }

    public List<VendaPagamentoDto> pagamentos(String companyId, String vendaId) {
        return jdbc.query("""
                SELECT Id, CompanyId, VendaId, PaymentType, Amount, CashGiven, ChangeAmount, CreatedAt
                FROM VendaPagamentos
                WHERE CompanyId = ? AND VendaId = ?
                ORDER BY CreatedAt ASC, Id ASC;
                """, (rs, n) -> new VendaPagamentoDto(texto(rs, "Id"), texto(rs, "CompanyId"), texto(rs, "VendaId"),
                texto(rs, "PaymentType"), rs.getBigDecimal("Amount"), rs.getBigDecimal("CashGiven"),
                rs.getBigDecimal("ChangeAmount"), rs.getObject("CreatedAt", OffsetDateTime.class)),
                companyId, vendaId);
    }

    // ---------------------------------------------------------------- eventos

    public Optional<EventoProcessado> buscarEventoComBloqueio(String companyId, String eventId) {
        return jdbc.query("""
                SELECT PayloadHash, ResponsePayload
                FROM ProcessedEvents WITH (UPDLOCK, HOLDLOCK)
                WHERE CompanyId = ? AND EventId = ?;
                """, (rs, n) -> new EventoProcessado(rs.getString(1), rs.getString(2)), companyId, eventId)
                .stream().findFirst();
    }

    public void inserirEvento(String id, String companyId, String eventId, String eventType, String clientSaleId,
                              String hash, OffsetDateTime processadoEm, String responsePayload) {
        jdbc.update("""
                INSERT INTO ProcessedEvents
                    (Id, CompanyId, EventId, EventType, ClientSaleId, PayloadHash, ProcessedAt, ResponsePayload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """, id, companyId, eventId, eventType, clientSaleId, hash, processadoEm, responsePayload);
    }

    // ---------------------------------------------------------------- pedido

    public Optional<PedidoTravado> travarPedido(String companyId, String orderNumber) {
        return jdbc.query("""
                SELECT Id, CustomerCpf, Status FROM Pedidos WITH (UPDLOCK, HOLDLOCK)
                WHERE CompanyId = ? AND OrderNumber = ?
                """, (rs, n) -> new PedidoTravado(rs.getString(1), rs.getString(2), rs.getInt(3)),
                companyId, orderNumber).stream().findFirst();
    }

    public Optional<PedidoPorId> travarPedidoPorId(String companyId, String pedidoId) {
        return jdbc.query("SELECT Status, VendaId FROM Pedidos WITH (UPDLOCK, HOLDLOCK) WHERE CompanyId = ? AND Id = ?",
                (rs, n) -> new PedidoPorId(rs.getInt(1), rs.getString(2)), companyId, pedidoId).stream().findFirst();
    }

    public List<PedidoItem> itensDoPedido(String pedidoId) {
        return jdbc.query("""
                SELECT ProductCode, ProductName, Quantity, UnitPrice, ItemTotal
                FROM PedidoItens WHERE PedidoId = ? ORDER BY ProductCode
                """, (rs, n) -> new PedidoItem(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), pedidoId);
    }

    public int finalizarPedido(String companyId, String pedidoId, String vendaId) {
        return jdbc.update("UPDATE Pedidos SET Status=1,VendaId=?,FinalizedAt=SYSDATETIMEOFFSET() "
                + "WHERE CompanyId=? AND Id=? AND Status=0", vendaId, companyId, pedidoId);
    }

    // ---------------------------------------------------------------- fiado e produtos

    public Optional<ClienteFiado> travarClientePorDocumento(String companyId, String digitos) {
        return jdbc.query("""
                SELECT Id, LimiteCredito, SaldoDevedor, CustomerName
                FROM Clientes WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = ?
                  AND REPLACE(REPLACE(REPLACE(Document, '.', ''), '-', ''), '/', '') = ?;
                """, (rs, n) -> new ClienteFiado(rs.getString("Id"), rs.getBigDecimal("LimiteCredito"),
                rs.getBigDecimal("SaldoDevedor"), rs.getString("CustomerName")), companyId, digitos)
                .stream().findFirst();
    }

    /** Trava o produto (se existir) sem lancar erro: usado para travar em ordem de codigo (D13). */
    public void travarProduto(String companyId, String codigo) {
        jdbc.query("SELECT Id FROM Produtos WITH (UPDLOCK, ROWLOCK) WHERE CompanyId = ? AND ProductCode = ?;",
                (rs, n) -> rs.getString(1), companyId, codigo);
    }

    public Optional<ProdutoVenda> produtoParaVenda(String companyId, String codigo) {
        return jdbc.query("""
                SELECT Id, ProductName, ProductQnt, ProductUnitPrice, ProductSalePrice
                FROM Produtos WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = ? AND ProductCode = ?;
                """, (rs, n) -> new ProdutoVenda(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), companyId, codigo).stream().findFirst();
    }

    // ---------------------------------------------------------------- insercao

    public void inserirVenda(String id, String companyId, String saleNumber, String customerName, String customerCpf,
                             String paymentType, BigDecimal total, String operatorName, OffsetDateTime saleDate,
                             String clientSaleId, String offlineReference, OffsetDateTime syncedAt,
                             String caixaSessaoId) {
        jdbc.update("""
                INSERT INTO Vendas
                    (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, TotalAmount, OperatorName, SaleDate, ClientSaleId, OfflineReference, SyncedAt, CaixaSessaoId)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """, id, companyId, saleNumber, customerName, customerCpf, paymentType, total, operatorName,
                saleDate, clientSaleId, offlineReference, syncedAt, caixaSessaoId);
    }

    public void inserirPagamento(String id, String companyId, String vendaId, String tipo, BigDecimal amount,
                                 BigDecimal cashGiven, BigDecimal changeAmount, OffsetDateTime criadoEm) {
        jdbc.update("""
                INSERT INTO VendaPagamentos (Id, CompanyId, VendaId, PaymentType, Amount, CashGiven, ChangeAmount, CreatedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                """, id, companyId, vendaId, tipo, amount, cashGiven, changeAmount, criadoEm);
    }

    public void inserirItem(String id, String vendaId, String code, String name, BigDecimal quantity,
                            BigDecimal unitPrice, BigDecimal costPrice, BigDecimal desconto, BigDecimal itemTotal,
                            String promocaoId) {
        jdbc.update("""
                INSERT INTO VendaItens
                    (Id, VendaId, ProductCode, ProductName, Quantity, UnitPrice, Desconto, ItemTotal, PromocaoId, CostPrice)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """, id, vendaId, code, name, quantity, unitPrice, desconto, itemTotal,
                promocaoId == null || promocaoId.isBlank() ? null : promocaoId, costPrice);
    }

    public void inserirAuditoria(String companyId, String userName, String eventType, String description,
                                 String entityType, String entityId) {
        jdbc.update("INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) "
                + "VALUES (?, '', ?, ?, ?, ?, ?, NULL)", companyId, userName, eventType, entityType, entityId,
                description);
    }
}
