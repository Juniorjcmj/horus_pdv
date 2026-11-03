package br.com.quack.pdv.produto.lote.app;

import br.com.quack.pdv.produto.lote.web.CategoriaValidadeModel;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Porta de LoteAB: consultas de linhas/modo/categorias, registro de entrada de lote, sincronizacao de
 * Produtos.DataValidade e baixa FEFO (ConsumirNosLotes e ConsumirAjusteSeguro em transacao propria).
 */
@Service
public class LoteOperacoes {

    private static final Logger LOG = LoggerFactory.getLogger(LoteOperacoes.class);
    public static final ZoneId FUSO_BRASILIA = ZoneId.of("America/Sao_Paulo");

    public static final String MODO_DESLIGADO = "desligado";
    public static final String MODO_SOMBRA = "sombra";
    public static final String MODO_ATIVO = "ativo";

    private static final String LINHAS_SQL = """
            SELECT l.Id, l.ProdutoId, l.NumeroLote, l.DataValidade, l.QtdInicial, ISNULL(l.QtdAtual, 0) AS QtdAtual,
                   l.Origem, l.ValidadePadrao, l.CriadoEm,
                   p.ProductCode, p.ProductName, p.ProductQnt, p.ProductUnitPrice, p.ProductSalePrice,
                   p.DiasAlertaValidade AS DiasAlertaProduto,
                   COALESCE(c.Nome, N'') AS CategoriaNome,
                   c.Id AS CategoriaId,
                   c.CategoriaPaiId AS CategoriaPaiId,
                   COALESCE(c.DiasAlertaValidade, cp.DiasAlertaValidade) AS DiasAlertaCategoria
            FROM ProdutoLotes l
            JOIN Produtos p ON p.Id = l.ProdutoId AND p.CompanyId = l.CompanyId
            LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
            LEFT JOIN Categorias cp ON cp.Id = c.CategoriaPaiId
            WHERE l.CompanyId = ?
              AND (? IS NULL OR l.ProdutoId = ?)
            ORDER BY l.ProdutoId, l.DataValidade DESC, l.CriadoEm DESC;
            """;

    private final JdbcTemplate jdbc;
    private final TransactionOperations txNova;
    private final Clock clock;

    @Autowired
    public LoteOperacoes(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this(jdbc, criarRequiresNew(transactionManager), clock);
    }

    public LoteOperacoes(JdbcTemplate jdbc, TransactionOperations txNova, Clock clock) {
        this.jdbc = jdbc;
        this.txNova = txNova;
        this.clock = clock;
    }

    private static TransactionTemplate criarRequiresNew(PlatformTransactionManager tm) {
        TransactionTemplate tt = new TransactionTemplate(tm);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tt;
    }

    public static boolean modoValido(String modo) {
        return MODO_DESLIGADO.equals(modo) || MODO_SOMBRA.equals(modo) || MODO_ATIVO.equals(modo);
    }

    public LocalDate hojeBrasilia() {
        return LocalDate.now(clock.withZone(FUSO_BRASILIA));
    }

    public List<LoteLinha> listarLinhas(String companyId, String produtoId) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(LINHAS_SQL);
            ps.setNString(1, companyId);
            if (produtoId == null) {
                ps.setNull(2, Types.NVARCHAR);
                ps.setNull(3, Types.NVARCHAR);
            } else {
                ps.setNString(2, produtoId);
                ps.setNString(3, produtoId);
            }
            return ps;
        }, (rs, rowNum) -> lerLinha(rs));
    }

    public int contarProdutosSemLote(String companyId) {
        String sql = """
                SELECT COUNT(*)
                FROM Produtos p
                WHERE p.CompanyId = ?
                  AND p.ControlaValidade = 1
                  AND p.ProductQnt > 0
                  AND NOT EXISTS (SELECT 1 FROM ProdutoLotes l WHERE l.CompanyId = p.CompanyId AND l.ProdutoId = p.Id);
                """;
        Integer n = jdbc.queryForObject(sql, Integer.class, companyId);
        return n == null ? 0 : n;
    }

    public Integer obterPrazoPadraoDias(String companyId, String produtoId) {
        String sql = """
                SELECT COALESCE(c.PrazoPadraoValidadeDias, cp.PrazoPadraoValidadeDias)
                FROM Produtos p
                LEFT JOIN Categorias c ON c.Id = p.CategoriaId AND c.CompanyId = p.CompanyId
                LEFT JOIN Categorias cp ON cp.Id = c.CategoriaPaiId
                WHERE p.Id = ? AND p.CompanyId = ?;
                """;
        List<Integer> res = jdbc.query(sql, (rs, i) -> {
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }, produtoId, companyId);
        return res.isEmpty() ? null : res.getFirst();
    }

    public String inserir(
            String companyId,
            String produtoId,
            LocalDate dataValidade,
            BigDecimal quantidade,
            String numeroLote,
            String origem,
            boolean validadePadrao,
            String criadoPorNome) {
        String sql = """
                INSERT INTO ProdutoLotes (Id, CompanyId, ProdutoId, NumeroLote, DataValidade, QtdInicial, QtdAtual, Origem, ValidadePadrao, CriadoPorNome)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;
        String sufixo = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String id = "lt-" + clock.millis() + "-" + sufixo;
        String loteLimpo = numeroLote == null || numeroLote.isBlank() ? null : numeroLote.trim();
        String autorLimpo = criadoPorNome == null || criadoPorNome.isBlank() ? null : criadoPorNome.trim();

        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setNString(1, id);
            ps.setNString(2, companyId);
            ps.setNString(3, produtoId);
            if (loteLimpo == null) ps.setNull(4, Types.NVARCHAR); else ps.setNString(4, loteLimpo);
            ps.setDate(5, Date.valueOf(dataValidade));
            ps.setBigDecimal(6, quantidade);
            ps.setBigDecimal(7, quantidade);
            ps.setNString(8, origem);
            ps.setBoolean(9, validadePadrao);
            if (autorLimpo == null) ps.setNull(10, Types.NVARCHAR); else ps.setNString(10, autorLimpo);
            return ps;
        });
        return id;
    }

    public boolean registrarEntrada(
            String companyId,
            String produtoId,
            LocalDate dataValidade,
            BigDecimal quantidade,
            String numeroLote,
            String origem,
            String criadoPorNome) {
        if (quantidade == null || quantidade.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        LocalDate data;
        boolean validadePadrao = false;
        if (dataValidade != null) {
            data = dataValidade;
        } else {
            Integer prazo = obterPrazoPadraoDias(companyId, produtoId);
            if (prazo == null || prazo <= 0) {
                return false;
            }
            data = hojeBrasilia().plusDays(prazo);
            validadePadrao = true;
        }
        inserir(companyId, produtoId, data, quantidade, numeroLote, origem, validadePadrao, criadoPorNome);
        sincronizarValidadeProduto(companyId, produtoId);
        return true;
    }

    /** Versao segura (D4): roda em transacao propria REQUIRES_NEW e nunca lanca excecao para nao desfazer o saldo. */
    public void registrarEntradaSegura(
            String companyId,
            String produtoId,
            LocalDate dataValidade,
            BigDecimal quantidade,
            String numeroLote,
            String origem,
            String criadoPorNome) {
        try {
            txNova.executeWithoutResult(s ->
                    registrarEntrada(companyId, produtoId, dataValidade, quantidade, numeroLote, origem, criadoPorNome));
        } catch (Exception ex) {
            LOG.error("Falha ao registrar lote na entrada de estoque do produto {}.", produtoId, ex);
        }
    }

    public void sincronizarValidadeProduto(String companyId, String produtoId) {
        List<LoteLinha> linhas = listarLinhas(companyId, produtoId);
        if (linhas.isEmpty()) {
            return;
        }
        String modo = obterModo(companyId);
        Map<String, BigDecimal> saldos = LoteEstimador.saldos(linhas, MODO_ATIVO.equals(modo));
        LocalDate menorValidade = linhas.stream()
                .filter(l -> saldos.getOrDefault(l.id(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0)
                .map(LoteLinha::dataValidade)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (menorValidade == null) {
            return;
        }
        jdbc.update("""
                UPDATE Produtos
                   SET DataValidade = ?,
                       ControlaValidade = 1
                 WHERE Id = ? AND CompanyId = ?;
                """, Date.valueOf(menorValidade), produtoId, companyId);
    }

    public String obterModo(String companyId) {
        List<String> res = jdbc.query(
                "SELECT Modo FROM LoteConfig WHERE CompanyId = ?;",
                (rs, i) -> rs.getString(1),
                companyId);
        if (res.isEmpty() || res.getFirst() == null) {
            return MODO_SOMBRA;
        }
        String modo = res.getFirst().trim().toLowerCase(Locale.ROOT);
        return modoValido(modo) ? modo : MODO_SOMBRA;
    }

    public void definirModo(String companyId, String modo) {
        int atualizados = jdbc.update(
                "UPDATE LoteConfig SET Modo = ?, AtualizadoEm = SYSDATETIMEOFFSET() WHERE CompanyId = ?;",
                modo, companyId);
        if (atualizados == 0) {
            jdbc.update("INSERT INTO LoteConfig (CompanyId, Modo) VALUES (?, ?);", companyId, modo);
        }
    }

    public List<CategoriaValidadeModel> listarCategoriasValidade(String companyId) {
        String sql = """
                SELECT Id, Nome, CategoriaPaiId, PrazoPadraoValidadeDias, DiasAlertaValidade
                FROM Categorias
                WHERE CompanyId = ? AND Ativa = 1
                ORDER BY Ordem, Nome;
                """;
        return jdbc.query(sql, (rs, i) -> {
            String pai = rs.getString("CategoriaPaiId");
            int prazo = rs.getInt("PrazoPadraoValidadeDias");
            Integer prazoObj = rs.wasNull() ? null : prazo;
            int alerta = rs.getInt("DiasAlertaValidade");
            Integer alertaObj = rs.wasNull() ? null : alerta;
            return new CategoriaValidadeModel(
                    lerTexto(rs, "Id"),
                    lerTexto(rs, "Nome"),
                    pai == null ? null : pai.trim(),
                    prazoObj,
                    alertaObj);
        }, companyId);
    }

    public boolean salvarCategoriaValidade(String companyId, String categoriaId, Integer prazoPadraoDias, Integer diasAlerta) {
        String sql = """
                UPDATE Categorias
                   SET PrazoPadraoValidadeDias = ?,
                       DiasAlertaValidade = ?
                 WHERE Id = ? AND CompanyId = ?;
                """;
        int linhas = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            if (prazoPadraoDias == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, prazoPadraoDias);
            if (diasAlerta == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, diasAlerta);
            ps.setNString(3, categoriaId);
            ps.setNString(4, companyId);
            return ps;
        });
        return linhas > 0;
    }

    /** Baixa FEFO de uma saida manual de estoque (D4): roda em REQUIRES_NEW e nunca lanca excecao. */
    public void consumirAjusteSeguro(String companyId, String produtoId, BigDecimal quantidade) {
        try {
            if (quantidade == null || quantidade.compareTo(BigDecimal.ZERO) <= 0
                    || MODO_DESLIGADO.equals(obterModo(companyId))) {
                return;
            }
            String refId = "aj-" + UUID.randomUUID().toString().replace("-", "");
            Boolean consumiu = txNova.execute(s ->
                    consumirNosLotes(companyId, produtoId, quantidade, "ajuste", refId));
            if (Boolean.TRUE.equals(consumiu)) {
                txNova.executeWithoutResult(s -> sincronizarValidadeProduto(companyId, produtoId));
            }
        } catch (Exception ex) {
            LOG.error("Falha na baixa FEFO por lote do ajuste de estoque do produto {}.", produtoId, ex);
        }
    }

    /**
     * Porta literal de ConsumirNosLotesAsync: consome em ordem FEFO (DataValidade ASC, CriadoEm ASC)
     * e grava em LoteConsumos. Deve rodar dentro de uma transacao ativa.
     */
    public boolean consumirNosLotes(
            String companyId,
            String produtoId,
            BigDecimal quantidade,
            String origem,
            String refId) {
        Integer jaFeito = jdbc.queryForObject(
                "SELECT COUNT(1) FROM LoteConsumos WHERE CompanyId = ? AND RefId = ? AND ProdutoId = ? AND Origem = ?;",
                Integer.class, companyId, refId, produtoId, origem);
        if (jaFeito != null && jaFeito > 0) {
            return false;
        }

        record LoteSaldo(String id, BigDecimal saldo) {}
        List<LoteSaldo> lotes = new ArrayList<>();
        jdbc.query("""
                SELECT Id, ISNULL(QtdAtual, 0) AS QtdAtual
                FROM ProdutoLotes WITH (UPDLOCK, ROWLOCK)
                WHERE CompanyId = ? AND ProdutoId = ? AND ISNULL(QtdAtual, 0) > 0
                ORDER BY DataValidade ASC, CriadoEm ASC;
                """, rs -> {
            lotes.add(new LoteSaldo(rs.getString("Id"), rs.getBigDecimal("QtdAtual")));
        }, companyId, produtoId);

        if (lotes.isEmpty()) {
            Integer temLote = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM ProdutoLotes WHERE CompanyId = ? AND ProdutoId = ?;",
                    Integer.class, companyId, produtoId);
            if (temLote == null || temLote == 0) {
                return false;
            }
        }

        BigDecimal restante = quantidade;
        for (LoteSaldo lote : lotes) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal tirar = lote.saldo().compareTo(restante) <= 0 ? lote.saldo() : restante;
            jdbc.update(
                    "UPDATE ProdutoLotes SET QtdAtual = ISNULL(QtdAtual, 0) - ? WHERE Id = ? AND CompanyId = ?;",
                    tirar, lote.id(), companyId);
            inserirConsumo(companyId, refId, origem, produtoId, lote.id(), tirar);
            restante = restante.subtract(tirar);
        }

        if (restante.compareTo(BigDecimal.ZERO) > 0) {
            inserirConsumo(companyId, refId, origem, produtoId, null, restante);
        }
        return true;
    }

    private void inserirConsumo(
            String companyId,
            String refId,
            String origem,
            String produtoId,
            String loteId,
            BigDecimal quantidade) {
        String id = "lc-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO LoteConsumos (Id, CompanyId, RefId, Origem, ProdutoId, LoteId, Quantidade)
                    VALUES (?, ?, ?, ?, ?, ?, ?);
                    """);
            ps.setNString(1, id);
            ps.setNString(2, companyId);
            ps.setNString(3, refId);
            ps.setNString(4, origem);
            ps.setNString(5, produtoId);
            if (loteId == null) ps.setNull(6, Types.NVARCHAR); else ps.setNString(6, loteId);
            ps.setBigDecimal(7, quantidade);
            return ps;
        });
    }

    private static LoteLinha lerLinha(ResultSet rs) throws SQLException {
        Date dv = rs.getDate("DataValidade");
        OffsetDateTime criadoEm = rs.getObject("CriadoEm", OffsetDateTime.class);
        int alertaCat = rs.getInt("DiasAlertaCategoria");
        Integer alertaCatObj = rs.wasNull() ? null : alertaCat;
        return new LoteLinha(
                lerTexto(rs, "Id"),
                lerTexto(rs, "ProdutoId"),
                lerTexto(rs, "NumeroLote"),
                dv.toLocalDate(),
                lerDecimal(rs, "QtdInicial"),
                lerDecimal(rs, "QtdAtual"),
                lerTexto(rs, "Origem"),
                rs.getBoolean("ValidadePadrao"),
                criadoEm,
                lerTexto(rs, "ProductCode"),
                lerTexto(rs, "ProductName"),
                lerDecimal(rs, "ProductQnt"),
                lerDecimal(rs, "ProductUnitPrice"),
                lerDecimal(rs, "ProductSalePrice"),
                rs.getInt("DiasAlertaProduto"),
                lerTexto(rs, "CategoriaNome"),
                lerTexto(rs, "CategoriaId"),
                lerTexto(rs, "CategoriaPaiId"),
                alertaCatObj);
    }

    private static String lerTexto(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? "" : s.trim();
    }

    private static BigDecimal lerDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal d = rs.getBigDecimal(col);
        return d == null ? BigDecimal.ZERO : d;
    }
}
