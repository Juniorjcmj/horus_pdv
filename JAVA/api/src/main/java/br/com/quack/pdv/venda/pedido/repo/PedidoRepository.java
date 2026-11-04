package br.com.quack.pdv.venda.pedido.repo;

import br.com.quack.pdv.venda.pedido.domain.Pedido;
import br.com.quack.pdv.venda.registro.repo.VendaRepository.PedidoItem;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Acesso a dados de pedidos: porta literal do PedidoAB (mesmo SQL e mesmas dicas de bloqueio). */
@Repository
public class PedidoRepository {

    private static final String COLUNAS = "Id, OrderNumber, CustomerName, CustomerCpf, SellerName, Status, CreatedAt";

    private static final RowMapper<Pedido> MAPA = (rs, n) -> new Pedido(rs.getString("Id"),
            rs.getString("OrderNumber"), rs.getString("CustomerName"), rs.getString("CustomerCpf"),
            rs.getString("SellerName"), rs.getInt("Status"), rs.getObject("CreatedAt", OffsetDateTime.class),
            List.of());

    private final JdbcTemplate jdbc;

    public PedidoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Produto do pedido: nome, custo e preco de venda (sem trava). */
    public record ProdutoPreco(String nome, BigDecimal custo, BigDecimal venda) {}

    public Optional<ProdutoPreco> produto(String companyId, String codigo) {
        return jdbc.query("SELECT ProductName, ProductUnitPrice, ProductSalePrice FROM Produtos "
                + "WHERE CompanyId = ? AND ProductCode = ?;", (rs, n) -> new ProdutoPreco(rs.getString(1),
                rs.getBigDecimal(2), rs.getBigDecimal(3)), companyId, codigo).stream().findFirst();
    }

    /** NextOrderNumberAsync: maximo numerico + 1 sob trava da tabela da empresa. */
    public String proximoNumero(String companyId) {
        String v = jdbc.queryForObject("SELECT CONVERT(NVARCHAR(20), ISNULL(MAX(TRY_CONVERT(INT, OrderNumber)), 0) + 1) "
                + "FROM Pedidos WITH (UPDLOCK, HOLDLOCK) WHERE CompanyId = ?;", String.class, companyId);
        return v == null ? "1" : v;
    }

    public void inserir(String id, String companyId, String numero, String cliente, String cpf, String vendedorId,
                        String vendedorNome, OffsetDateTime criadoEm) {
        jdbc.update("INSERT INTO Pedidos (Id, CompanyId, OrderNumber, CustomerName, CustomerCpf, SellerId, "
                + "SellerName, Status, CreatedAt, Note) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, N'');", id, companyId,
                numero, cliente, cpf, vendedorId, vendedorNome, criadoEm);
    }

    public void inserirItem(String id, String pedidoId, String codigo, String nome, BigDecimal quantidade,
                            BigDecimal preco, BigDecimal total) {
        jdbc.update("INSERT INTO PedidoItens (Id, PedidoId, ProductCode, ProductName, Quantity, UnitPrice, ItemTotal) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?);", id, pedidoId, codigo, nome, quantidade, preco, total);
    }

    public Optional<Pedido> porNumero(String companyId, String numero) {
        return jdbc.query("SELECT " + COLUNAS + " FROM Pedidos WHERE CompanyId = ? AND OrderNumber = ?;", MAPA,
                companyId, numero).stream().findFirst();
    }

    public List<PedidoItem> itens(String pedidoId) {
        return jdbc.query("SELECT ProductCode, ProductName, Quantity, UnitPrice, ItemTotal FROM PedidoItens "
                + "WHERE PedidoId = ? ORDER BY Id;", (rs, n) -> new PedidoItem(rs.getString(1), rs.getString(2),
                rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)), pedidoId);
    }

    public List<Pedido> abertos(String companyId) {
        return jdbc.query("SELECT " + COLUNAS + " FROM Pedidos WHERE CompanyId = ? AND Status = 0 "
                + "ORDER BY CreatedAt DESC;", MAPA, companyId);
    }

    public int cancelar(String companyId, String numero, OffsetDateTime quando) {
        return jdbc.update("UPDATE Pedidos SET Status = 2, CanceledAt = ? WHERE CompanyId = ? AND OrderNumber = ? "
                + "AND Status = 0;", quando, companyId, numero);
    }
}
