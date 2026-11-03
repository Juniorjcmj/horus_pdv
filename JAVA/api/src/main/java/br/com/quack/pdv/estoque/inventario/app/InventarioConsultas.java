package br.com.quack.pdv.estoque.inventario.app;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Consultas do inventario (porta de ListarAsync, ObterAsync, ItensAsync, ContagensAsync e TrilhaAsync do .NET). */
@Component
public class InventarioConsultas {

    static final String SQL_ITENS = """
            SELECT ProdutoId, Codigo, Nome, Unidade, Custo, SaldoNa1aContagem, Contado, Recontar,
                   PrimeiraContagemEm, UltimaContagemEm, SaldoAntes, Delta, SaldoDepois, ValorDelta, PisoAplicado
            FROM InventarioItens WHERE CompanyId = ? AND InventarioId = ? ORDER BY Nome
            """;

    private final JdbcTemplate jdbc;

    public InventarioConsultas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime data(ResultSet rs, String coluna) throws SQLException {
        return rs.getObject(coluna, OffsetDateTime.class);
    }

    public List<Map<String, Object>> listar(String companyId, String status) {
        String filtro = status == null || status.isBlank() ? null : status.trim();
        return jdbc.query("""
                SELECT TOP 200 i.Id, i.Numero, i.Descricao, i.Escopo, i.Status, i.AbertoPorNome, i.AbertoEm, i.AplicadoEm, i.CanceladoEm,
                       (SELECT COUNT(*) FROM InventarioItens it WHERE it.InventarioId = i.Id) AS Itens,
                       (SELECT COUNT(*) FROM InventarioItens it WHERE it.InventarioId = i.Id AND it.Contado IS NOT NULL) AS Contados
                FROM Inventarios i
                WHERE i.CompanyId = ? AND (? IS NULL OR i.Status = ?)
                ORDER BY i.Numero DESC
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("Id"));
            m.put("numero", rs.getInt("Numero"));
            m.put("descricao", rs.getString("Descricao"));
            m.put("escopo", rs.getString("Escopo"));
            m.put("status", rs.getString("Status"));
            m.put("abertoPor", rs.getString("AbertoPorNome"));
            m.put("abertoEm", data(rs, "AbertoEm"));
            m.put("aplicadoEm", data(rs, "AplicadoEm"));
            m.put("canceladoEm", data(rs, "CanceladoEm"));
            m.put("itens", rs.getInt("Itens"));
            m.put("contados", rs.getInt("Contados"));
            return m;
        }, companyId, filtro, filtro);
    }

    public Optional<Map<String, Object>> obter(String companyId, String id, boolean comValores) {
        List<Map<String, Object>> cabecalhos = jdbc.query("""
                SELECT Id, Numero, Descricao, Escopo, EscopoJson, Status, AbertoPorNome, AbertoEm, AplicadoPorNome, AplicadoEm,
                       SupervisorNome, Justificativa, ZerarNaoContados, CanceladoPorNome, CanceladoEm, MotivoCancelamento,
                       TotalSobra, TotalFalta, TotalLiquido
                FROM Inventarios WHERE CompanyId = ? AND Id = ?
                """, (rs, n) -> cabecalho(rs, comValores), companyId, id);
        if (cabecalhos.isEmpty()) return Optional.empty();

        List<ItemLinha> itens = lerItens(companyId, id);
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("itens", itens.size());
        resumo.put("contados", itens.stream().filter(ItemLinha::temContagem).count());
        resumo.put("naoContados", itens.stream().filter(i -> i.contado() == null && !i.recontar()).count());
        resumo.put("recontar", itens.stream().filter(ItemLinha::recontar).count());
        resumo.put("divergentes", itens.stream()
                .filter(i -> i.contado() != null && !igual(i.contado(), i.saldoNa1aContagem())).count());
        resumo.put("sobra", comValores ? soma(itens, true) : null);
        resumo.put("falta", comValores ? soma(itens, false) : null);
        resumo.put("liquido", comValores
                ? itens.stream().map(i -> valor(i.valorDiferenca())).reduce(BigDecimal.ZERO, BigDecimal::add)
                : null);

        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("inventario", cabecalhos.getFirst());
        dados.put("resumo", resumo);
        return Optional.of(dados);
    }

    private static Map<String, Object> cabecalho(ResultSet rs, boolean comValores) throws SQLException {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", rs.getString("Id"));
        h.put("numero", rs.getInt("Numero"));
        h.put("descricao", rs.getString("Descricao"));
        h.put("escopo", rs.getString("Escopo"));
        h.put("escopoJson", rs.getString("EscopoJson"));
        h.put("status", rs.getString("Status"));
        h.put("abertoPor", rs.getString("AbertoPorNome"));
        h.put("abertoEm", data(rs, "AbertoEm"));
        h.put("aplicadoPor", rs.getString("AplicadoPorNome"));
        h.put("aplicadoEm", data(rs, "AplicadoEm"));
        h.put("supervisor", rs.getString("SupervisorNome"));
        h.put("justificativa", rs.getString("Justificativa"));
        h.put("zerarNaoContados", rs.getBoolean("ZerarNaoContados"));
        h.put("canceladoPor", rs.getString("CanceladoPorNome"));
        h.put("canceladoEm", data(rs, "CanceladoEm"));
        h.put("motivoCancelamento", rs.getString("MotivoCancelamento"));
        h.put("totalSobra", comValores ? rs.getBigDecimal("TotalSobra") : null);
        h.put("totalFalta", comValores ? rs.getBigDecimal("TotalFalta") : null);
        h.put("totalLiquido", comValores ? rs.getBigDecimal("TotalLiquido") : null);
        return h;
    }

    private static boolean igual(BigDecimal a, BigDecimal b) {
        return b != null && a.compareTo(b) == 0;
    }

    private static BigDecimal valor(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal soma(List<ItemLinha> itens, boolean positivos) {
        return itens.stream()
                .map(i -> valor(i.valorDiferenca()))
                .filter(v -> positivos ? v.signum() > 0 : v.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<ItemLinha> lerItens(String companyId, String id) {
        return jdbc.query(SQL_ITENS, (rs, n) -> ItemLinha.calcular(
                rs.getString("ProdutoId"), rs.getString("Codigo"), rs.getString("Nome"), rs.getString("Unidade"),
                rs.getBigDecimal("Custo"), rs.getBigDecimal("SaldoNa1aContagem"), rs.getBigDecimal("Contado"),
                rs.getBoolean("Recontar"), data(rs, "PrimeiraContagemEm"), data(rs, "UltimaContagemEm"),
                rs.getBigDecimal("SaldoAntes"), rs.getBigDecimal("Delta"), rs.getBigDecimal("SaldoDepois"),
                rs.getBigDecimal("ValorDelta"), rs.getBigDecimal("PisoAplicado")), companyId, id);
    }

    /** Itens filtrados; vazio quando o inventario nao existe. */
    public Optional<List<ItemLinha>> itens(String companyId, String id, String filtro, boolean comValores) {
        if (!existe(companyId, id)) return Optional.empty();
        String f = filtro == null ? "" : filtro.trim().toLowerCase(Locale.ROOT);
        List<ItemLinha> resultado = new ArrayList<>();
        for (ItemLinha i : lerItens(companyId, id)) {
            boolean entra = switch (f) {
                case "divergentes" -> i.contado() != null && i.diferenca().signum() != 0;
                case "nao_contados" -> i.contado() == null && !i.recontar();
                case "recontar" -> i.recontar();
                case "contados" -> i.contado() != null;
                default -> true;
            };
            if (entra) resultado.add(comValores ? i : i.semValores());
        }
        return Optional.of(resultado);
    }

    public boolean existe(String companyId, String id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM Inventarios WHERE CompanyId = ? AND Id = ?", Integer.class, companyId, id);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> contagens(
            String companyId, String id, String usuarioId, String produtoId, int limite) {
        int topo = Math.max(1, Math.min(limite, 2000));
        return jdbc.query("""
                SELECT TOP (?) c.Id, c.ProdutoId, it.Codigo, it.Nome, c.Quantidade, c.Local, c.UsuarioNome, c.Terminal,
                       c.SaldoSistema, c.CriadoEm, c.AnuladaPorNome, c.AnuladaEm, c.AnuladaMotivo, c.UsuarioId
                FROM InventarioContagens c
                JOIN InventarioItens it ON it.InventarioId = c.InventarioId AND it.ProdutoId = c.ProdutoId
                WHERE c.CompanyId = ? AND c.InventarioId = ?
                  AND (? IS NULL OR c.UsuarioId = ?) AND (? IS NULL OR c.ProdutoId = ?)
                ORDER BY c.CriadoEm DESC
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("Id"));
            m.put("produtoId", rs.getString("ProdutoId"));
            m.put("codigo", rs.getString("Codigo"));
            m.put("nome", rs.getString("Nome"));
            m.put("quantidade", rs.getBigDecimal("Quantidade"));
            m.put("local", rs.getString("Local"));
            m.put("usuario", rs.getString("UsuarioNome"));
            m.put("terminal", rs.getString("Terminal"));
            m.put("saldoSistema", rs.getBigDecimal("SaldoSistema"));
            m.put("criadoEm", data(rs, "CriadoEm"));
            m.put("anuladaPor", rs.getString("AnuladaPorNome"));
            m.put("anuladaEm", data(rs, "AnuladaEm"));
            m.put("anuladaMotivo", rs.getString("AnuladaMotivo"));
            m.put("usuarioId", rs.getString("UsuarioId"));
            return m;
        }, topo, companyId, id, usuarioId, usuarioId, produtoId, produtoId);
    }

    public Optional<Map<String, Object>> trilha(String companyId, String id) {
        if (!existe(companyId, id)) return Optional.empty();
        List<Map<String, Object>> eventos = jdbc.query("""
                SELECT Tipo, OcorridoEm, UsuarioNome, SupervisorNome, Ip, Terminal, Descricao
                FROM InventarioEventos WHERE CompanyId = ? AND InventarioId = ? ORDER BY OcorridoEm
                """, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", rs.getString("Tipo"));
            m.put("ocorridoEm", data(rs, "OcorridoEm"));
            m.put("usuario", rs.getString("UsuarioNome"));
            m.put("gerente", rs.getString("SupervisorNome"));
            m.put("ip", rs.getString("Ip"));
            m.put("terminal", rs.getString("Terminal"));
            m.put("descricao", rs.getString("Descricao"));
            return m;
        }, companyId, id);
        List<ItemLinha> itens = lerItens(companyId, id).stream()
                .filter(i -> i.saldoAntes() != null || i.contado() != null)
                .toList();
        Map<String, Object> dados = new LinkedHashMap<>();
        dados.put("inventario", obter(companyId, id, true).orElse(null));
        dados.put("eventos", eventos);
        dados.put("contagens", contagens(companyId, id, null, null, 2000));
        dados.put("itens", itens);
        return Optional.of(dados);
    }
}
