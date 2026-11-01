package br.com.quack.caixa.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.sqlite.mc.SQLiteMCSqlCipherConfig;

/**
 * Banco local do caixa: um SQLite criptografado (SQLCipher, AES-256 com HMAC por página), com chave guardada pela
 * DPAPI do Windows ({@link DeviceKey}). Guarda o cache por empresa, a fila offline (vendas e operações de caixa) e
 * o acesso offline. Sem a chave, o arquivo é ilegível e não dá para alterar preços ou vendas da fila sem que o
 * banco acuse a adulteração.
 *
 * <p>Durabilidade: WAL com synchronous=FULL (cada venda gravada vai até o disco antes de o caixa seguir) e uma
 * cópia só da fila em outro arquivo ({@value #MIRROR_FILE}). Se o banco principal se perder, a fila volta da cópia.
 *
 * <p>Um banco por pasta, compartilhado pelas stores; {@link #at(Path)} reabre sozinho depois de {@link #closeAll()}.
 */
public final class LocalDb {
    static final String DB_FILE = "quack.db";
    static final String MIRROR_FILE = "fila-copia.db";

    private static final Map<Path, LocalDb> OPEN = new HashMap<>();
    private static final List<String> NOTICES = new ArrayList<>();

    /** Linha da fila. status = pending | failed. */
    public record OutboxRow(String name, String kind, String body, String status, String error, String failedAt) {
    }

    /** Linha do cache: dado JSON gravado para uma empresa. */
    public record CacheRow(String companyId, String data) {
    }

    private final Path root;
    private Connection main;
    private Connection mirror;

    private LocalDb(Path root) {
        this.root = root;
    }

    /** Banco da pasta (abre na primeira chamada). */
    public static synchronized LocalDb at(Path root) {
        Path key = root.toAbsolutePath().normalize();
        LocalDb db = OPEN.get(key);
        if (db == null) {
            db = new LocalDb(key);
            db.open();
            OPEN.put(key, db);
        }
        return db;
    }

    /** Fecha todos os bancos abertos (fim do programa e testes). */
    public static synchronized void closeAll() {
        for (LocalDb db : OPEN.values()) {
            db.close();
        }
        OPEN.clear();
    }

    /** Avisos para o operador (ex.: banco ilegível guardado à parte). Esvazia a lista. */
    public static synchronized List<String> drainNotices() {
        List<String> out = List.copyOf(NOTICES);
        NOTICES.clear();
        return out;
    }

    private static void notice(String message) {
        System.err.println("[banco local] " + message);
        NOTICES.add(message);
    }

    // ---------------------------------------------------------------- abertura

    private void open() {
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(root);
        } catch (ClassNotFoundException | IOException e) {
            throw new LocalDbException("Não foi possível preparar o banco local.", e);
        }
        Path dbFile = root.resolve(DB_FILE);
        Path mirrorFile = root.resolve(MIRROR_FILE);

        byte[] key = null;
        if (DeviceKey.exists(root)) {
            try {
                key = DeviceKey.load(root);
            } catch (DeviceKey.UnreadableKeyException e) {
                String suffix = quarantineSuffix();
                quarantine(root.resolve(DeviceKey.FILE_NAME), suffix);
                quarantine(dbFile, suffix);
                quarantine(mirrorFile, suffix);
                notice(e.getMessage() + " O banco antigo foi guardado como " + DB_FILE + suffix
                        + " e um banco novo foi criado.");
            }
        } else if (Files.exists(dbFile) || Files.exists(mirrorFile)) {
            String suffix = quarantineSuffix();
            quarantine(dbFile, suffix);
            quarantine(mirrorFile, suffix);
            notice("A chave do banco local sumiu. O banco antigo foi guardado como " + DB_FILE + suffix
                    + " e um banco novo foi criado.");
        }
        if (key == null) {
            key = DeviceKey.create(root);
        }

        boolean mainIsNew = !Files.exists(dbFile);
        try {
            main = connect(dbFile, key);
        } catch (SQLException e) {
            String suffix = quarantineSuffix();
            quarantine(dbFile, suffix);
            notice("O banco local estava danificado (" + e.getMessage() + ") e foi guardado como " + DB_FILE + suffix
                    + ". A fila offline será recuperada da cópia.");
            mainIsNew = true;
            try {
                main = connect(dbFile, key);
            } catch (SQLException again) {
                throw new LocalDbException("Não foi possível criar o banco local.", again);
            }
        }
        try {
            mirror = connect(mirrorFile, key);
        } catch (SQLException e) {
            String suffix = quarantineSuffix();
            quarantine(mirrorFile, suffix);
            try {
                mirror = connect(mirrorFile, key);
            } catch (SQLException again) {
                mirror = null; // segue sem cópia; o principal continua íntegro
                System.err.println("[banco local] cópia da fila indisponível: " + again.getMessage());
            }
        }
        java.util.Arrays.fill(key, (byte) 0);

        try {
            createSchema(main, true);
            if (mirror != null) {
                createSchema(mirror, false);
            }
            if (mainIsNew && mirror != null) {
                int recovered = copyOutbox(mirror, main);
                if (recovered > 0) {
                    notice(recovered + " operação(ões) da fila offline recuperada(s) da cópia de segurança.");
                }
            }
            refreshMirror();
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível preparar o banco local.", e);
        }
        LegacyJsonMigration.run(this, root);
    }

    private static Connection connect(Path file, byte[] key) throws SQLException {
        Properties props = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build().toProperties();
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), props);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            // Lê a primeira página: chave errada ou arquivo danificado falha aqui ("file is not a database").
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                String result = rs.next() ? rs.getString(1) : "ok";
                if (!"ok".equalsIgnoreCase(result)) {
                    throw new SQLException("verificação de integridade: " + result);
                }
            }
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = FULL");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    private static void createSchema(Connection c, boolean full) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        seq INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        kind TEXT NOT NULL,
                        body TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'pending',
                        error TEXT,
                        failed_at TEXT,
                        created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                    )""");
            if (full) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS cache (
                            name TEXT PRIMARY KEY,
                            company_id TEXT,
                            data TEXT NOT NULL,
                            updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
                        )""");
                st.execute("CREATE TABLE IF NOT EXISTS offline_users (email TEXT PRIMARY KEY, body TEXT NOT NULL)");
            }
        }
    }

    /** Copia as linhas da fila que faltam no destino, na ordem. Devolve quantas entraram. */
    private static int copyOutbox(Connection from, Connection to) throws SQLException {
        List<OutboxRow> rows = selectOutbox(from, null);
        int added = 0;
        boolean auto = to.getAutoCommit();
        to.setAutoCommit(false);
        try {
            for (OutboxRow r : rows) {
                added += insertOutbox(to, r, true);
            }
            to.commit();
        } catch (SQLException e) {
            to.rollback();
            throw e;
        } finally {
            to.setAutoCommit(auto);
        }
        return added;
    }

    /** Deixa a cópia igual à fila do banco principal. */
    private void refreshMirror() {
        if (mirror == null) {
            return;
        }
        try {
            mirror.setAutoCommit(false);
            try (Statement st = mirror.createStatement()) {
                st.execute("DELETE FROM outbox");
            }
            for (OutboxRow r : selectOutbox(main, null)) {
                insertOutbox(mirror, r, false);
            }
            mirror.commit();
        } catch (SQLException e) {
            mirrorFailed(e);
        } finally {
            try {
                if (mirror != null) {
                    mirror.setAutoCommit(true);
                }
            } catch (SQLException ignored) {
                // conexão perdida
            }
        }
    }

    private void mirrorFailed(SQLException e) {
        System.err.println("[banco local] cópia da fila não atualizada: " + e.getMessage());
        try {
            if (mirror != null) {
                mirror.rollback();
            }
        } catch (SQLException ignored) {
            // sem transação aberta
        }
    }

    private synchronized void close() {
        for (Connection c : new Connection[] {main, mirror}) {
            if (c != null) {
                try (Statement st = c.createStatement()) {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // fecha mesmo assim
                }
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // já fechada
                }
            }
        }
        main = null;
        mirror = null;
    }

    private static String quarantineSuffix() {
        return ".ilegivel-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private static void quarantine(Path file, String suffix) {
        for (String extra : new String[] {"", "-wal", "-shm"}) {
            Path f = file.resolveSibling(file.getFileName() + extra);
            if (Files.exists(f)) {
                try {
                    Files.move(f, f.resolveSibling(file.getFileName() + suffix + extra),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.err.println("[banco local] não foi possível guardar " + f + ": " + e.getMessage());
                }
            }
        }
    }

    // ---------------------------------------------------------------- fila

    private static int insertOutbox(Connection c, OutboxRow r, boolean ignoreExisting) throws SQLException {
        String sql = (ignoreExisting ? "INSERT OR IGNORE" : "INSERT")
                + " INTO outbox (name, kind, body, status, error, failed_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, r.name());
            ps.setString(2, r.kind());
            ps.setString(3, r.body());
            ps.setString(4, r.status() == null ? "pending" : r.status());
            ps.setString(5, r.error());
            ps.setString(6, r.failedAt());
            return ps.executeUpdate();
        }
    }

    private static List<OutboxRow> selectOutbox(Connection c, String status) throws SQLException {
        String sql = "SELECT name, kind, body, status, error, failed_at FROM outbox"
                + (status == null ? "" : " WHERE status = ?") + " ORDER BY seq";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (status != null) {
                ps.setString(1, status);
            }
            List<OutboxRow> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new OutboxRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)));
                }
            }
            return out;
        }
    }

    /** Grava um item na fila (banco principal, depois a cópia). Falha no principal = exceção. */
    public synchronized void outboxAdd(String name, String kind, String body) {
        OutboxRow row = new OutboxRow(name, kind, body, "pending", null, null);
        try {
            insertOutbox(conn(), row, false);
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível gravar na fila local.", e);
        }
        if (mirror != null) {
            try {
                insertOutbox(mirror, row, true);
            } catch (SQLException e) {
                mirrorFailed(e);
            }
        }
    }

    public synchronized List<OutboxRow> outbox(String status) {
        try {
            return selectOutbox(conn(), status);
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível ler a fila local.", e);
        }
    }

    public synchronized int outboxCount(String status) {
        return queryInt("SELECT COUNT(*) FROM outbox WHERE status = ?", status);
    }

    public synchronized boolean outboxHasPending(String kind) {
        return queryInt("SELECT COUNT(*) FROM outbox WHERE status = 'pending' AND kind = ?", kind) > 0;
    }

    public synchronized void outboxRemove(String name) {
        updateBoth("DELETE FROM outbox WHERE name = ?", name);
    }

    public synchronized void outboxFail(String name, String error, String failedAt) {
        updateBoth("UPDATE outbox SET status = 'failed', error = ?, failed_at = ? WHERE name = ?", error, failedAt,
                name);
    }

    /** Volta um item com falha para a fila (mesma posição: seq original). */
    public synchronized boolean outboxRetry(String name) {
        int before = queryInt("SELECT COUNT(*) FROM outbox WHERE name = ? AND status = 'failed'", name);
        updateBoth("UPDATE outbox SET status = 'pending', error = NULL, failed_at = NULL WHERE name = ? AND status = 'failed'",
                name);
        return before > 0;
    }

    /** Descarta um item com falha: sai da fila e dos avisos, mas fica guardado no banco (status discarded) para consulta. */
    public synchronized void outboxDiscard(String name, String note, String at) {
        updateBoth("UPDATE outbox SET status = 'discarded', error = COALESCE(error, '') || ? , failed_at = ? "
                + "WHERE name = ? AND status = 'failed'", note, at, name);
    }

    // ---------------------------------------------------------------- cache

    public synchronized void cachePut(String name, String companyId, String data) {
        update("INSERT INTO cache (name, company_id, data, updated_at) VALUES (?, ?, ?, "
                + "strftime('%Y-%m-%dT%H:%M:%fZ','now')) ON CONFLICT(name) DO UPDATE SET company_id = excluded.company_id, "
                + "data = excluded.data, updated_at = excluded.updated_at", name, companyId, data);
    }

    public synchronized Optional<CacheRow> cacheGet(String name) {
        try (PreparedStatement ps = conn().prepareStatement("SELECT company_id, data FROM cache WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new CacheRow(rs.getString(1), rs.getString(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível ler o cache local.", e);
        }
    }

    // ---------------------------------------------------------------- acesso offline

    public synchronized void userPut(String email, String body) {
        update("INSERT INTO offline_users (email, body) VALUES (?, ?) ON CONFLICT(email) DO UPDATE SET body = excluded.body",
                email, body);
    }

    public synchronized void userDelete(String email) {
        update("DELETE FROM offline_users WHERE email = ?", email);
    }

    public synchronized List<String> users() {
        try (Statement st = conn().createStatement();
                ResultSet rs = st.executeQuery("SELECT body FROM offline_users ORDER BY email")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível ler o acesso offline.", e);
        }
    }

    // ---------------------------------------------------------------- migração (LegacyJsonMigration)

    /** Importa tudo de uma vez (uma transação). Itens da fila que já existem são ignorados. */
    synchronized void importLegacy(List<OutboxRow> outbox, Map<String, CacheRow> caches, Map<String, String> users) {
        Connection c = conn();
        try {
            c.setAutoCommit(false);
            for (OutboxRow r : outbox) {
                insertOutbox(c, r, true);
            }
            for (Map.Entry<String, CacheRow> e : caches.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO cache (name, company_id, data) VALUES (?, ?, ?)")) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue().companyId());
                    ps.setString(3, e.getValue().data());
                    ps.executeUpdate();
                }
            }
            for (Map.Entry<String, String> e : users.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT OR IGNORE INTO offline_users (email, body) VALUES (?, ?)")) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            try {
                c.rollback();
            } catch (SQLException ignored) {
                // conexão perdida
            }
            throw new LocalDbException("Não foi possível migrar os arquivos antigos para o banco local.", e);
        } finally {
            try {
                c.setAutoCommit(true);
            } catch (SQLException ignored) {
                // conexão perdida
            }
        }
        refreshMirror();
    }

    /** O item da fila está gravado no banco principal? (conferência antes de apagar o JSON antigo) */
    synchronized boolean outboxContains(String name) {
        return queryInt("SELECT COUNT(*) FROM outbox WHERE name = ?", name) > 0;
    }

    // ---------------------------------------------------------------- utilitários

    private Connection conn() {
        if (main == null) {
            throw new LocalDbException("Banco local fechado.", null);
        }
        return main;
    }

    private int queryInt(String sql, Object... args) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível consultar o banco local.", e);
        }
    }

    private void update(String sql, Object... args) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LocalDbException("Não foi possível gravar no banco local.", e);
        }
    }

    private void updateBoth(String sql, Object... args) {
        update(sql, args);
        if (mirror != null) {
            try (PreparedStatement ps = mirror.prepareStatement(sql)) {
                bind(ps, args);
                ps.executeUpdate();
            } catch (SQLException e) {
                mirrorFailed(e);
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    /** Erro do banco local. */
    public static final class LocalDbException extends RuntimeException {
        LocalDbException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
