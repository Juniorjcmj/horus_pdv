package br.com.quack.pdv.plataforma.sincronizacao.integracao;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.QuackPdvApiApplication;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteNuvemHttp;
import br.com.quack.pdv.plataforma.sincronizacao.tracking.ChangeTrackingAdmin;
import br.com.quack.pdv.security.SecretProtector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;

/**
 * Dois SQL Server (Testcontainers), dois contextos Spring (perfis loja e nuvem, portas aleatorias), os dois bancos com
 * o mesmo seed, o tracking da loja ligado e o servidor registrado direto em SyncServidores (a rota registrar e da 3.1).
 */
public final class AmbienteSync implements AutoCloseable {

    public static final String EMPRESA = "empresa-principal";
    static final MSSQLServerContainer<?> SQL_NUVEM =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();

    static {
        SQL_NUVEM.start();
    }

    public final String token = "tok-" + UUID.randomUUID();
    public final String bancoLoja;
    public final String bancoNuvem;
    public final ConfigurableApplicationContext loja;
    public final ConfigurableApplicationContext nuvem;
    public final JdbcTemplate jdbcLoja;
    public final JdbcTemplate jdbcNuvem;
    public final int portaNuvem;
    public final int portaLoja;
    public final HttpClient http = HttpClient.newHttpClient();

    private AmbienteSync(String sufixo) {
        bancoLoja = "loja_" + sufixo;
        bancoNuvem = "nuvem_" + sufixo;
        BancoIT.criarBanco(bancoLoja);
        criarBancoNuvem(bancoNuvem);
        prepararEsquemaDotNet();

        nuvem = subir("nuvem", urlNuvem(), SQL_NUVEM.getUsername(), SQL_NUVEM.getPassword(), 2000);
        loja = subir("loja", BancoIT.urlDoBanco(bancoLoja), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword(), 300);
        portaNuvem = porta(nuvem);
        portaLoja = porta(loja);
        jdbcLoja = loja.getBean(JdbcTemplate.class);
        jdbcNuvem = nuvem.getBean(JdbcTemplate.class);

        EstadoDoBanco.por(BancoIT.urlDoBanco(bancoLoja), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword()).restaurar();
        EstadoDoBanco.por(urlNuvem(), SQL_NUVEM.getUsername(), SQL_NUVEM.getPassword()).restaurar();
        ativar();
    }

    public static AmbienteSync iniciar(String sufixo) {
        return new AmbienteSync(sufixo);
    }

    public String urlNuvem() {
        return SQL_NUVEM.getJdbcUrl() + ";databaseName=" + bancoNuvem;
    }

    public String urlBaseNuvem() {
        return "http://localhost:" + portaNuvem;
    }

    public String urlBaseLoja() {
        return "http://localhost:" + portaLoja;
    }

    public ClienteNuvemHttp cliente() {
        return loja.getBean(ClienteNuvemHttp.class);
    }

    // ------------------------------------------------------------------ preparo

    private static void criarBancoNuvem(String nome) {
        try (Connection c = DriverManager.getConnection(SQL_NUVEM.getJdbcUrl(), SQL_NUVEM.getUsername(), SQL_NUVEM.getPassword());
             Statement s = c.createStatement()) {
            s.execute("IF DB_ID('" + nome + "') IS NULL BEGIN CREATE DATABASE [" + nome + "]; ALTER DATABASE [" + nome
                    + "] SET RECOVERY SIMPLE; END");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O banco da nuvem ja tem o esquema da .NET (V1 a V4 aplicados sem historico da Java). */
    private void prepararEsquemaDotNet() {
        Flyway.configure().dataSource(urlNuvem(), SQL_NUVEM.getUsername(), SQL_NUVEM.getPassword())
                .locations("classpath:db/migration").target("4").table("tmp_hist").load().migrate();
        try (Connection c = DriverManager.getConnection(urlNuvem(), SQL_NUVEM.getUsername(), SQL_NUVEM.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE tmp_hist");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConfigurableApplicationContext subir(String perfil, String url, String usuario, String senha, int intervalo) {
        return new SpringApplicationBuilder(QuackPdvApiApplication.class)
                .profiles("it", perfil)
                .run("--server.port=0", "--spring.datasource.url=" + url, "--spring.datasource.username=" + usuario,
                        "--spring.datasource.password=" + senha, "--quack.sync.intervalo-ms=" + intervalo,
                        "--spring.main.banner-mode=off", "--spring.jmx.enabled=false");
    }

    private static int porta(ConfigurableApplicationContext ctx) {
        return ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    }

    /** Equivale a carga + ativacao (grupo 3): tracking ligado, servidor ativo e as duas pontas na mesma versao. */
    private void ativar() {
        loja.getBean(ChangeTrackingAdmin.class).ligarTudo(ChangeTrackingAdmin.RETENCAO_LOJA_DIAS);
        Long versao = jdbcLoja.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
        String cifrado = loja.getBean(SecretProtector.class).protect(token);
        jdbcLoja.update("""
                UPDATE SyncEstado SET CompanyId = ?, ServidorId = 'srv-it', NuvemUrl = ?, TokenCifrado = ?, Ativada = 1,
                       AtivadaEm = SYSDATETIMEOFFSET(), VersaoDe = ?, AplicadoAteNuvem = ? WHERE Id = 1
                """, EMPRESA, urlBaseNuvem(), cifrado, versao, versao);
        registrarServidor("srv-it", EMPRESA, token, "ativo");
        jdbcNuvem.update("INSERT INTO SyncEstadoNuvem (CompanyId, ServidorId, AplicadoAte) VALUES (?, 'srv-it', ?)", EMPRESA, versao);
    }

    public void registrarServidor(String id, String empresa, String tokenClaro, String status) {
        jdbcNuvem.update("INSERT INTO SyncServidores (Id, CompanyId, Nome, TokenHash, Status) VALUES (?, ?, 'IT', ?, ?)",
                id, empresa, br.com.quack.pdv.plataforma.sincronizacao.nuvem.ServidorLojaFilter.hash(tokenClaro), status);
    }

    // ------------------------------------------------------------------ utilidades de teste

    public static void esperar(String o, long timeoutMs, BooleanSupplier condicao) {
        long fim = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < fim) {
            try {
                if (condicao.getAsBoolean()) return;
            } catch (RuntimeException ignorada) {
                // banco ocupado: tenta de novo
            }
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Tempo esgotado esperando: " + o);
    }

    /** A loja alcancou a nuvem: VersaoDe da loja == AplicadoAte da nuvem == versao corrente da loja. */
    public boolean convergiu() {
        Long atual = jdbcLoja.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
        Long deLoja = jdbcLoja.queryForObject("SELECT VersaoDe FROM SyncEstado WHERE Id = 1", Long.class);
        Long nuvemAte = jdbcNuvem.queryForObject("SELECT AplicadoAte FROM SyncEstadoNuvem WHERE CompanyId = ?", Long.class, EMPRESA);
        return atual != null && atual.equals(deLoja) && atual.equals(nuvemAte);
    }

    public void esperarConvergencia(long timeoutMs) {
        esperar("convergencia loja/nuvem", timeoutMs, this::convergiu);
    }

    /** Remove a espera do backoff (testes comprimem o tempo). */
    public void zerarBackoff() {
        jdbcLoja.update("UPDATE SyncEstado SET ProximaTentativaEm = NULL WHERE Id = 1");
    }

    /**
     * Conferencia por SQL (a do grupo 6 ainda nao existe): para cada tabela nao FORA compara as linhas das duas pontas,
     * sem colunas CIFRADA/LOCAL; AuditLog so por contagem (os Ids da nuvem sao proprios). Devolve as diferencas.
     */
    public List<String> diferencas() {
        List<String> dif = new ArrayList<>();
        EsquemaJdbc esquema = new EsquemaJdbc(jdbcLoja);
        Map<String, Set<String>> colunas = esquema.colunas();
        Map<String, List<String>> pks = esquema.chavesPrimarias();
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            if (t.classe() == ClasseTabela.ACRESCIMO) {
                Integer a = jdbcLoja.queryForObject("SELECT COUNT(*) FROM AuditLog WHERE CompanyId = ?", Integer.class, EMPRESA);
                Integer b = jdbcNuvem.queryForObject("SELECT COUNT(*) FROM AuditLog WHERE CompanyId = ?", Integer.class, EMPRESA);
                if (!a.equals(b)) dif.add("AuditLog: contagem loja=" + a + " nuvem=" + b);
                continue;
            }
            List<String> cols = colunas.get(t.nome()).stream().filter(c -> {
                ClasseColuna cl = t.classeDaColuna(c, null);
                return cl != ClasseColuna.CIFRADA && cl != ClasseColuna.LOCAL;
            }).toList();
            String sql = "SELECT " + String.join(", ", cols.stream().map(c -> "[" + c + "]").toList()) + " FROM dbo.[" + t.nome()
                    + "] ORDER BY " + String.join(", ", pks.get(t.nome()).stream().map(c -> "[" + c + "]").toList());
            List<String> l = linhas(jdbcLoja, sql, cols);
            List<String> n = linhas(jdbcNuvem, sql, cols);
            if (!l.equals(n)) {
                dif.add(t.nome() + ": loja=" + l.size() + " nuvem=" + n.size() + " " + primeiraDiferenca(l, n));
            }
        }
        return dif;
    }

    private static String primeiraDiferenca(List<String> a, List<String> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "<falta>";
            String y = i < b.size() ? b.get(i) : "<falta>";
            if (!x.equals(y)) return "primeira diferenca: loja=" + x + " nuvem=" + y;
        }
        return "";
    }

    private static List<String> linhas(JdbcTemplate jdbc, String sql, List<String> cols) {
        return jdbc.query(sql, (rs, i) -> {
            StringBuilder sb = new StringBuilder();
            for (int c = 1; c <= cols.size(); c++) {
                Object v = rs.getObject(c);
                sb.append(cols.get(c - 1)).append('=');
                if (v instanceof byte[] b) sb.append(Arrays.toString(b));
                else if (v instanceof java.math.BigDecimal d) sb.append(d.toPlainString());
                else sb.append(v);
                sb.append(';');
            }
            return sb.toString();
        });
    }

    // ------------------------------------------------------------------ HTTP

    public HttpResponse<String> postLote(String autorizacao, byte[] corpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(urlBaseNuvem() + "/api/loja-sync/lotes"))
                .header("Content-Type", "application/json").header("Content-Encoding", "gzip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(corpo));
        if (autorizacao != null) b.header("Authorization", autorizacao);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
        if (loja != null) loja.close();
        if (nuvem != null) nuvem.close();
    }

    /** Linha de uma tabela como mapa (para os testes). */
    public static Map<String, Object> linha(JdbcTemplate jdbc, String sql, Object... args) {
        List<Map<String, Object>> l = jdbc.queryForList(sql, args);
        return l.isEmpty() ? new LinkedHashMap<>() : l.getFirst();
    }
}
