package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.BlocoTabela;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ConversorValor;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.LeitorLinhas;
import br.com.quack.pdv.security.SecretProtector;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Leitura da nuvem para a carga inicial (D5): paginas por chave crescente (cursor {@code depois}) e delta por Change
 * Tracking desde uma versao. Cada leitura e uma transacao SNAPSHOT curta. Escopo sempre pela empresa do token
 * (filhas sem empresa, pela mae). Colunas CIFRADA saem decifradas.
 */
@Component
@Profile("nuvem")
@org.springframework.context.annotation.Lazy
public class LeitorCargaNuvem {

    private static final int SNAPSHOT = 4096;

    private static final String CATEGORIAS = "Categorias";

    /**
     * Na nuvem ha produtos e promocoes ligados a categoria de outra empresa (as categorias padrao do seed sao da
     * empresa-principal). A chave estrangeira da loja exige a categoria: a carga leva tambem as usadas pela empresa e
     * as maes delas. Na loja elas ficam so como referencia: envio e conferencia filtram pela empresa.
     */
    private static final String USADAS_PELA_EMPRESA = "SELECT P.[CategoriaId] FROM dbo.[Produtos] AS P WHERE P.[CompanyId] = ?"
            + " UNION SELECT R.[CategoriaId] FROM dbo.[Promocoes] AS R WHERE R.[CompanyId] = ?";
    private static final String CATEGORIAS_DA_CARGA = "WHERE (T.[CompanyId] = ?"
            + " OR T.[Id] IN (" + USADAS_PELA_EMPRESA + ")"
            + " OR T.[Id] IN (SELECT C.[CategoriaPaiId] FROM dbo.[Categorias] AS C WHERE C.[CompanyId] = ?"
            + " OR C.[Id] IN (" + USADAS_PELA_EMPRESA + ")))";
    private static final int PARAMETROS_CATEGORIAS = (int) CATEGORIAS_DA_CARGA.chars().filter(c -> c == '?').count();
    public static final int LIMITE_MAXIMO = 5_000;

    /** A versao pedida ficou abaixo da retencao da nuvem: a carga precisa recomecar. */
    public static class VersaoExpiradaException extends RuntimeException {
        public VersaoExpiradaException(String tabela, long desde, long minima) {
            super("Versao " + desde + " abaixo da minima valida (" + minima + ") em " + tabela + "; refaca a carga.");
        }
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final SecretProtector segredos;
    private volatile Map<String, Set<String>> colunasPorTabela;
    private volatile Map<String, List<String>> chavesPorTabela;

    private final br.com.quack.pdv.plataforma.sincronizacao.nuvem.MotorDeteccao motor;

    public LeitorCargaNuvem(DataSource dataSource, JdbcTemplate jdbc, SecretProtector segredos,
                            br.com.quack.pdv.plataforma.sincronizacao.nuvem.MotorDeteccao motor) {
        this.motor = motor;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.segredos = segredos;
    }

    public long versaoAtual() {
        Long v = jdbc.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ paginas

    public CargaPagina pagina(String companyId, String nomeTabela, List<String> depois, int limite) {
        carregarEsquema();
        TabelaSincronizada t = tabela(nomeTabela);
        List<String> pk = chavesPorTabela.get(t.nome());
        if (depois != null && depois.size() != pk.size()) {
            throw new IllegalArgumentException("Cursor invalido para " + t.nome() + ".");
        }
        int tamanho = Math.max(1, Math.min(limite, LIMITE_MAXIMO));
        List<String> cols = List.copyOf(colunasPorTabela.get(t.nome()));
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            con.setTransactionIsolation(SNAPSHOT);
            try {
                long versao = versao(con);
                List<ColunaSync> tiposChave = tiposDaChave(con, t, pk);
                String sql = "SELECT TOP (" + (tamanho + 1) + ") " + listaT(cols) + " FROM dbo.[" + t.nome() + "] AS T "
                        + juncaoEWhere(t) + (depois == null ? "" : " AND " + cursor(pk))
                        + " ORDER BY " + listaT(pk);
                List<List<String>> linhas = new ArrayList<>();
                List<ColunaSync> colunas;
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    int i = 1;
                    for (int e = 0; e < parametrosDeEmpresa(t); e++) ps.setString(i++, companyId);
                    if (depois != null) {
                        for (int a = 0; a < pk.size(); a++) {
                            for (int b = 0; b <= a; b++) {
                                ps.setObject(i++, ConversorValor.decodificar(depois.get(b), tiposChave.get(b).tipo()));
                            }
                        }
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        colunas = LeitorLinhas.tipos(rs.getMetaData(), cols, 0);
                        while (rs.next()) {
                            linhas.add(decifrar(t, colunas, LeitorLinhas.valores(rs, colunas, 1)));
                        }
                    }
                }
                boolean fim = linhas.size() <= tamanho;
                if (!fim) linhas.remove(linhas.size() - 1);
                List<String> ultima = linhas.isEmpty() ? depois : chaveDe(cols, pk, linhas.get(linhas.size() - 1));
                return new CargaPagina(t.nome(), versao, colunas, linhas, fim ? null : ultima, fim);
            } finally {
                con.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao ler a pagina da carga: " + e.getMessage(), e);
        }
    }

    /** {@code (k1 > ?) OR (k1 = ? AND k2 > ?) ...}: a ordem dos parametros e a de {@link #pagina}. */
    private static String cursor(List<String> pk) {
        List<String> partes = new ArrayList<>();
        for (int a = 0; a < pk.size(); a++) {
            StringBuilder p = new StringBuilder("(");
            for (int b = 0; b < a; b++) p.append("T.[").append(pk.get(b)).append("] = ? AND ");
            p.append("T.[").append(pk.get(a)).append("] > ?)");
            partes.add(p.toString());
        }
        return "(" + String.join(" OR ", partes) + ")";
    }

    // ------------------------------------------------------------------ delta

    public CargaDelta delta(String companyId, long desde) {
        carregarEsquema();
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            con.setTransactionIsolation(SNAPSHOT);
            try {
                long ate = versao(con);
                List<BlocoTabela> blocos = new ArrayList<>();
                for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
                    conferirMinima(con, t, desde);
                    BlocoTabela b = deltaDaTabela(con, t, companyId, desde);
                    if (!b.linhas().isEmpty() || !b.exclusoes().isEmpty()) blocos.add(b);
                }
                return new CargaDelta(ate, blocos);
            } finally {
                con.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao ler o delta da carga: " + e.getMessage(), e);
        }
    }

    /** Uma linha do espelho (D6): tabela, chave serializada e as colunas guardadas. */
    public record EspelhoLinha(TabelaSincronizada tabela, String chave, Map<String, String> colunas) {}

    /** Resultado de {@link #retratoDeAtivacao}: versao lida, se o delta desde {@code desde} esta vazio e o espelho. */
    public record RetratoAtivacao(long ate, boolean vazio, List<EspelhoLinha> espelho) {}

    /**
     * Na mesma transacao SNAPSHOT: confere que nada mudou desde {@code desde} e le o que a loja tem (linhas das
     * tabelas com espelho), para o espelho nascer exatamente na versao {@code ate} em que o detector passa a olhar.
     */
    public RetratoAtivacao retratoDeAtivacao(String companyId, long desde) {
        carregarEsquema();
        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            con.setTransactionIsolation(SNAPSHOT);
            try {
                long ate = versao(con);
                for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
                    conferirMinima(con, t, desde);
                    BlocoTabela b = deltaDaTabela(con, t, companyId, desde);
                    if (!b.linhas().isEmpty() || !b.exclusoes().isEmpty()) return new RetratoAtivacao(ate, false, List.of());
                }
                JdbcTemplate j = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(con, true));
                List<EspelhoLinha> espelho = new ArrayList<>();
                for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
                    if (!t.usaEspelho()) continue;
                    for (var m : motor.lerTodas(j, t, companyId)) {
                        espelho.add(new EspelhoLinha(t, String.join("|", m.pk()), motor.espelhoDe(t, m.linha())));
                    }
                }
                return new RetratoAtivacao(ate, true, espelho);
            } finally {
                con.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao ler o retrato da ativacao: " + e.getMessage(), e);
        }
    }

    private BlocoTabela deltaDaTabela(Connection con, TabelaSincronizada t, String companyId, long desde) throws SQLException {
        List<String> pk = chavesPorTabela.get(t.nome());
        List<String> cols = List.copyOf(colunasPorTabela.get(t.nome()));
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < pk.size(); i++) sql.append("CT.[").append(pk.get(i)).append("] AS __k").append(i).append(", ");
        sql.append(listaT(cols)).append(" FROM CHANGETABLE(CHANGES dbo.[").append(t.nome()).append("], ?) AS CT LEFT JOIN dbo.[")
                .append(t.nome()).append("] AS T ON ")
                .append(String.join(" AND ", pk.stream().map(c -> "T.[" + c + "] = CT.[" + c + "]").toList()));
        String filtro;
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
            sql.append(" LEFT JOIN dbo.[").append(mae.nome()).append("] AS M ON M.[")
                    .append(chavesPorTabela.get(mae.nome()).getFirst()).append("] = T.[").append(t.colunaLigacao()).append("]");
            filtro = "M.[" + mae.colunaEmpresa() + "]";
        } else {
            filtro = "T.[" + t.colunaEmpresa() + "]";
        }
        // linha excluida (T nula) nao tem empresa para filtrar: sai so a chave (ver design, ajustes do grupo 3)
        sql.append(" WHERE T.[").append(pk.getFirst()).append("] IS NULL OR ").append(filtro)
                .append(" = ? ORDER BY CT.SYS_CHANGE_VERSION");
        List<List<String>> linhas = new ArrayList<>();
        List<List<String>> exclusoes = new ArrayList<>();
        List<ColunaSync> colunas;
        List<ColunaSync> colunasChave;
        try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
            ps.setLong(1, desde);
            ps.setString(2, companyId);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                colunasChave = LeitorLinhas.tipos(md, pk, 0);
                colunas = LeitorLinhas.tipos(md, cols, pk.size());
                int posPk = cols.indexOf(pk.getFirst());
                while (rs.next()) {
                    if (rs.getObject(pk.size() + posPk + 1) == null) {
                        // so se a nuvem sabe de que empresa era (espelho); senao fica para a conferencia (grupo 6) e
                        // nao vaza a chave de linha de outra empresa
                        List<String> chave = LeitorLinhas.valores(rs, colunasChave, 1);
                        if (t.usaEspelho() && motor.lerEspelho(jdbc, t, String.join("|", chave), companyId).isPresent()) {
                            exclusoes.add(chave);
                        }
                    } else {
                        linhas.add(decifrar(t, colunas, LeitorLinhas.valores(rs, colunas, pk.size() + 1)));
                    }
                }
            }
        }
        return new BlocoTabela(t.nome(), colunas, linhas, colunasChave, exclusoes);
    }

    private void conferirMinima(Connection con, TabelaSincronizada t, long desde) throws SQLException {
        try (var st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT CHANGE_TRACKING_MIN_VALID_VERSION(OBJECT_ID(N'dbo." + t.nome() + "'))")) {
            if (rs.next()) {
                long minima = rs.getLong(1);
                if (!rs.wasNull() && desde < minima) throw new VersaoExpiradaException(t.nome(), desde, minima);
            }
        }
    }

    // ------------------------------------------------------------------ apoio

    private List<String> decifrar(TabelaSincronizada t, List<ColunaSync> colunas, List<String> valores) {
        List<String> saida = new ArrayList<>(valores);
        for (int i = 0; i < colunas.size(); i++) {
            if (valores.get(i) != null && t.classeDaColuna(colunas.get(i).nome(), null) == ClasseColuna.CIFRADA) {
                try {
                    saida.set(i, segredos.unprotect(valores.get(i)));
                } catch (IllegalStateException e) {
                    throw new IllegalStateException("Segredo ilegivel com a chave da nuvem em " + t.nome() + "."
                            + colunas.get(i).nome() + " (a chave de cifra da nuvem mudou?).", e);
                }
            }
        }
        return saida;
    }

    private static List<String> chaveDe(List<String> cols, List<String> pk, List<String> linha) {
        List<String> k = new ArrayList<>();
        for (String p : pk) k.add(linha.get(cols.indexOf(p)));
        return k;
    }

    private List<ColunaSync> tiposDaChave(Connection con, TabelaSincronizada t, List<String> pk) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT TOP 0 " + listaT(pk) + " FROM dbo.[" + t.nome() + "] AS T");
             ResultSet rs = ps.executeQuery()) {
            return LeitorLinhas.tipos(rs.getMetaData(), pk, 0);
        }
    }

    /** {@code JOIN} da mae (filhas) e {@code WHERE} da empresa, com um parametro. */
    private String juncaoEWhere(TabelaSincronizada t) {
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
            return "JOIN dbo.[" + mae.nome() + "] AS M ON M.[" + chavesPorTabela.get(mae.nome()).getFirst() + "] = T.["
                    + t.colunaLigacao() + "] WHERE M.[" + mae.colunaEmpresa() + "] = ?";
        }
        if (ReferenciasGlobais.global(t)) return "WHERE 1 = 1";
        if (t.nome().equals(CATEGORIAS)) return CATEGORIAS_DA_CARGA;
        return "WHERE T.[" + t.colunaEmpresa() + "] = ?";
    }

    /** Quantos {@code ?} da empresa o {@link #juncaoEWhere} tem. */
    private static int parametrosDeEmpresa(TabelaSincronizada t) {
        if (ReferenciasGlobais.global(t)) return 0;
        return t.nome().equals(CATEGORIAS) ? PARAMETROS_CATEGORIAS : 1;
    }

    private static String listaT(List<String> nomes) {
        return String.join(", ", nomes.stream().map(c -> "T.[" + c + "]").toList());
    }

    private static TabelaSincronizada tabela(String nome) {
        return ReferenciasGlobais.daCarga(nome)
                .orElseThrow(() -> new IllegalArgumentException("Tabela fora da sincronizacao: " + nome));
    }

    private static long versao(Connection con) throws SQLException {
        try (var st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT CHANGE_TRACKING_CURRENT_VERSION()")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private void carregarEsquema() {
        if (colunasPorTabela != null) return;
        EsquemaJdbc esquema = new EsquemaJdbc(jdbc);
        chavesPorTabela = esquema.chavesPrimarias();
        colunasPorTabela = esquema.colunas();
    }
}
