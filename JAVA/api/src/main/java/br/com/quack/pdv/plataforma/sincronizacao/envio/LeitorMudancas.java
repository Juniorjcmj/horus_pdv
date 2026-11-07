package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.EmpacotadorPartes;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.LeitorLinhas;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ParteLote;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Le as mudancas da loja num retrato consistente (transacao SNAPSHOT, D4): CHANGETABLE por tabela na ordem do catalogo
 * (incremental) ou a tabela inteira (completo, D5). Entrega partes ao consumidor enquanto le.
 */
@Component
@Profile("loja")
public class LeitorMudancas {

    /** TRANSACTION_SNAPSHOT do mssql-jdbc. */
    private static final int SNAPSHOT = 4096;

    /** A versao de partida ficou abaixo da retencao do Change Tracking: so a ressincronizacao completa resolve. */
    public static class VersaoExpiradaException extends RuntimeException {
        public VersaoExpiradaException(String tabela, long de, long minima) {
            super("Versao " + de + " abaixo da minima valida (" + minima + ") em " + tabela + ".");
        }
    }

    /** Resultado: versao lida e se houve algo a enviar. */
    public record Leitura(long ate, boolean enviou) {}

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private volatile Map<String, Set<String>> colunasPorTabela;
    private volatile Map<String, List<String>> chavesPorTabela;
    private int maxLinhas = EmpacotadorPartes.MAX_LINHAS;
    private long maxBytes = EmpacotadorPartes.MAX_BYTES;

    public LeitorMudancas(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    /** Tamanho das partes (testes usam valores pequenos). */
    public void definirLimites(int maxLinhas, long maxBytes) {
        this.maxLinhas = maxLinhas;
        this.maxBytes = maxBytes;
    }

    public Leitura ler(long de, boolean completo, String loteId, String companyId, List<String[]> reenvios,
                        Consumer<ParteLote> saida) {
        carregarEsquema();
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try {
                con.setTransactionIsolation(SNAPSHOT);
                long ate = escalar(con, "SELECT CHANGE_TRACKING_CURRENT_VERSION()");
                if (!completo && ate == de && reenvios.isEmpty()) {
                    con.rollback();
                    return new Leitura(ate, false);
                }
                if (!completo) conferirVersaoMinima(con, de);
                long deLote = completo ? ate : de;
                EmpacotadorPartes pac = new EmpacotadorPartes(loteId, deLote, ate, completo, maxLinhas, maxBytes, saida);
                for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
                    Set<List<String>> jaEnviadas = lerTabela(con, t, de, completo, companyId, pac);
                    lerReenvios(con, t, reenvios, jaEnviadas, pac);
                }
                // Mesmo sem linhas da empresa (so mudancas de outra empresa ou de tabela que nao sobe), o lote vazio
                // vai: a nuvem confirma a versao nova e a loja nao fica para tras (atraso crescendo sem fim).
                pac.concluir();
                con.rollback(); // so leitura
                return new Leitura(ate, true);
            } catch (RuntimeException | SQLException e) {
                try { con.rollback(); } catch (SQLException ignorada) { /* a falha original e a que importa */ }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao ler as mudancas da loja: " + e.getMessage(), e);
        }
    }

    private void carregarEsquema() {
        if (colunasPorTabela != null) return;
        EsquemaJdbc esquema = new EsquemaJdbc(jdbc);
        chavesPorTabela = esquema.chavesPrimarias();
        colunasPorTabela = esquema.colunas();
    }

    private void conferirVersaoMinima(Connection con, long de) throws SQLException {
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            Long minima = escalarNulo(con, "SELECT CHANGE_TRACKING_MIN_VALID_VERSION(OBJECT_ID(N'dbo." + t.nome() + "'))");
            if (minima != null && de < minima) throw new VersaoExpiradaException(t.nome(), de, minima);
        }
    }

    private List<String> colunasEnviadas(TabelaSincronizada t) {
        List<String> cols = new ArrayList<>();
        for (String c : colunasPorTabela.get(t.nome())) {
            if (t.classeDaColuna(c, null) != ClasseColuna.CIFRADA) cols.add(c); // segredos nao sobem (recifrados pela nuvem)
        }
        return cols;
    }

    private Set<List<String>> lerTabela(Connection con, TabelaSincronizada t, long de, boolean completo, String companyId,
                                        EmpacotadorPartes pac) throws SQLException {
        List<String> pk = chavesPorTabela.get(t.nome());
        List<String> cols = colunasEnviadas(t);
        StringBuilder sql = new StringBuilder("SELECT ");
        int k = pk.size();
        if (completo) {
            sql.append(String.join(", ", cols.stream().map(c -> "T.[" + c + "]").toList()));
            sql.append(" FROM dbo.[").append(t.nome()).append("] AS T ");
            if (t.filhaSemEmpresa()) {
                TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
                sql.append("JOIN dbo.[").append(mae.nome()).append("] AS M ON M.[")
                        .append(chavesPorTabela.get(mae.nome()).getFirst()).append("] = T.[").append(t.colunaLigacao())
                        .append("] WHERE M.[").append(mae.colunaEmpresa()).append("] = ? "); // so a empresa da loja
            } else {
                sql.append("WHERE T.[").append(t.colunaEmpresa()).append("] = ? ");
            }
            sql.append("ORDER BY ")
                    .append(String.join(", ", pk.stream().map(c -> "T.[" + c + "]").toList()));
        } else {
            for (int i = 0; i < k; i++) sql.append("CT.[").append(pk.get(i)).append("] AS __k").append(i).append(", ");
            sql.append(String.join(", ", cols.stream().map(c -> "T.[" + c + "]").toList()));
            sql.append(" FROM CHANGETABLE(CHANGES dbo.[").append(t.nome()).append("], ?) AS CT LEFT JOIN dbo.[")
                    .append(t.nome()).append("] AS T ON ")
                    .append(String.join(" AND ", pk.stream().map(c -> "T.[" + c + "] = CT.[" + c + "]").toList()))
                    .append(" ORDER BY CT.SYS_CHANGE_VERSION");
        }
        Set<List<String>> enviadas = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            if (completo) ps.setString(1, companyId); else ps.setLong(1, de);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int off = completo ? 0 : k;
                List<ColunaSync> colunas = LeitorLinhas.tipos(md, cols, off);
                List<ColunaSync> chave = completo ? null : LeitorLinhas.tipos(md, pk, 0);
                int posPk = cols.indexOf(pk.getFirst());
                while (rs.next()) {
                    boolean existe = completo || posPk < 0 || rs.getObject(off + posPk + 1) != null;
                    if (!completo && !existe) {
                        pac.adicionarExclusao(t.nome(), chave, LeitorLinhas.valores(rs, chave, 1));
                        continue;
                    }
                    List<String> v = LeitorLinhas.valores(rs, colunas, off + 1);
                    enviadas.add(chaveDe(cols, pk, v));
                    pac.adicionarLinha(t.nome(), colunas, v);
                }
            }
        }
        return enviadas;
    }

    private void lerReenvios(Connection con, TabelaSincronizada t, List<String[]> reenvios,
                             Set<List<String>> jaEnviadas, EmpacotadorPartes pac) throws SQLException {
        List<String> pk = chavesPorTabela.get(t.nome());
        List<String> cols = colunasEnviadas(t);
        for (String[] r : reenvios) {
            if (!r[0].equals(t.nome())) continue;
            String[] partes = r[1].split("\\|", -1);
            if (partes.length != pk.size()) continue;
            String sql = "SELECT " + String.join(", ", cols.stream().map(c -> "[" + c + "]").toList()) + " FROM dbo.["
                    + t.nome() + "] WHERE " + String.join(" AND ", pk.stream().map(c -> "[" + c + "] = ?").toList());
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                for (int i = 0; i < partes.length; i++) ps.setString(i + 1, partes[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ColunaSync> colunas = LeitorLinhas.tipos(rs.getMetaData(), cols, 0);
                    while (rs.next()) {
                        List<String> v = LeitorLinhas.valores(rs, colunas, 1);
                        if (jaEnviadas.add(chaveDe(cols, pk, v))) pac.adicionarLinha(t.nome(), colunas, v);
                    }
                }
            }
        }
    }

    private static List<String> chaveDe(List<String> cols, List<String> pk, List<String> valores) {
        List<String> k = new ArrayList<>();
        for (String p : pk) k.add(valores.get(cols.indexOf(p)));
        return k;
    }

    private static long escalar(Connection con, String sql) throws SQLException {
        Long v = escalarNulo(con, sql);
        return v == null ? 0 : v;
    }

    private static Long escalarNulo(Connection con, String sql) throws SQLException {
        try (var st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) return null;
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }
    }
}
