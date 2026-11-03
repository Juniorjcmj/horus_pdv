package br.com.quack.pdv.estoque.movimentacao.app;

import br.com.quack.pdv.estoque.movimentacao.web.EstoqueMovimentacaoDto;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Consultas do livro de movimentacoes de estoque (porta de EstoqueMovimentacaoAB.Listar*). */
@Service
public class EstoqueMovimentacaoService {

    private static final DateTimeFormatter FORMATO_DATA_HORA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String SQL_POR_PRODUTO = """
            SELECT m.Id, m.ProdutoId, p.ProductDescription AS ProdutoNome, p.ProductCode AS ProdutoCodigo,
                   m.Tipo, m.Quantidade, m.EstoqueAnterior, m.EstoqueNovo, m.Motivo,
                   m.UsuarioId, m.UsuarioNome, m.CriadoEm
            FROM EstoqueMovimentacoes m
            INNER JOIN Produtos p ON p.Id = m.ProdutoId
            WHERE m.CompanyId = ? AND m.ProdutoId = ?
            ORDER BY m.CriadoEm DESC;
            """;

    private static final String SQL_GERAL_BASE = """
            SELECT TOP (?)
                   m.Id, m.ProdutoId, p.ProductDescription AS ProdutoNome, p.ProductCode AS ProdutoCodigo,
                   m.Tipo, m.Quantidade, m.EstoqueAnterior, m.EstoqueNovo, m.Motivo,
                   m.UsuarioId, m.UsuarioNome, m.CriadoEm
            FROM EstoqueMovimentacoes m
            INNER JOIN Produtos p ON p.Id = m.ProdutoId
            WHERE m.CompanyId = ?%s
            ORDER BY m.CriadoEm DESC;
            """;

    private final JdbcTemplate jdbc;

    public EstoqueMovimentacaoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EstoqueMovimentacaoDto> listarPorProduto(String companyId, String produtoId) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(SQL_POR_PRODUTO);
            ps.setNString(1, companyId);
            ps.setNString(2, produtoId);
            return ps;
        }, (rs, rowNum) -> lerModelo(rs));
    }

    public List<EstoqueMovimentacaoDto> listar(String companyId, String tipo, int limit) {
        boolean filtrarTipo = tipo != null && !tipo.isBlank();
        String sql = SQL_GERAL_BASE.formatted(filtrarTipo ? " AND m.Tipo = ?" : "");
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setInt(1, limit);
            ps.setNString(2, companyId);
            if (filtrarTipo) {
                ps.setNString(3, tipo);
            }
            return ps;
        }, (rs, rowNum) -> lerModelo(rs));
    }

    private static EstoqueMovimentacaoDto lerModelo(ResultSet rs) throws SQLException {
        Timestamp ts = rs.getTimestamp("CriadoEm");
        String criadoEm = ts == null ? "" : ts.toLocalDateTime().format(FORMATO_DATA_HORA);
        return new EstoqueMovimentacaoDto(
                naoNulo(rs.getString("Id")),
                naoNulo(rs.getString("ProdutoId")),
                naoNulo(rs.getString("ProdutoNome")),
                naoNulo(rs.getString("ProdutoCodigo")),
                naoNulo(rs.getString("Tipo")),
                rs.getBigDecimal("Quantidade"),
                rs.getBigDecimal("EstoqueAnterior"),
                rs.getBigDecimal("EstoqueNovo"),
                naoNulo(rs.getString("Motivo")),
                naoNulo(rs.getString("UsuarioId")),
                naoNulo(rs.getString("UsuarioNome")),
                criadoEm);
    }

    private static String naoNulo(String valor) {
        return valor == null ? "" : valor;
    }
}
