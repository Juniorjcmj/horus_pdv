package br.com.quack.pdv.plataforma.sincronizacao.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Perfil nuvem sobre um banco que ja tem o esquema da .NET (sem flyway_schema_history da Java): so entram as Sync*
 * e o historico proprio flyway_quack_sync; o Change Tracking e o snapshot sao ligados pelo db/nuvem/V1.
 */
@SpringBootTest
@ActiveProfiles({"it", "nuvem"})
class ChangeTrackingNuvemIT {

    static final String BANCO = "nuvem_it";
    static final Set<String> ANTES = new HashSet<>();
    static final List<Map<String, Object>> HISTORICO_ANTES = new ArrayList<>();

    static {
        BancoIT.criarBanco(BANCO);
        // "Banco da .NET": o esquema do V1 aplicado direto, com historico temporario removido depois.
        Flyway.configure().dataSource(BancoIT.urlDoBanco(BANCO), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword())
                .locations("classpath:db/migration").target("1").table("tmp_hist").load().migrate();
        try (Connection c = DriverManager.getConnection(BancoIT.urlDoBanco(BANCO), BancoIT.SQL.getUsername(), BancoIT.SQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP TABLE tmp_hist");
            // uma tabela flyway_schema_history ficticia (como a de uma loja) que o perfil nuvem nao pode tocar
            s.execute("CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version NVARCHAR(50), script NVARCHAR(200))");
            s.execute("INSERT INTO flyway_schema_history VALUES (1, N'1', N'V1__qualquer.sql')");
            try (var rs = s.executeQuery("SELECT name FROM sys.tables")) {
                while (rs.next()) ANTES.add(rs.getString(1));
            }
            try (var rs = s.executeQuery("SELECT installed_rank, version, script FROM flyway_schema_history")) {
                while (rs.next()) {
                    HISTORICO_ANTES.add(Map.of("r", rs.getInt(1), "v", rs.getString(2), "s", rs.getString(3)));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void bancoDaNuvem(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> BancoIT.urlDoBanco(BANCO));
        registro.add("spring.datasource.username", BancoIT.SQL::getUsername);
        registro.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void subidaNaoTocaNoHistoricoDaJavaECriaSoAsSyncENoHistoricoProprio() {
        assertThat(jdbc.queryForObject("SELECT DB_NAME()", String.class)).isEqualTo(BANCO);
        Set<String> depois = new HashSet<>(jdbc.queryForList("SELECT name FROM sys.tables", String.class));
        Set<String> novas = new HashSet<>(depois);
        novas.removeAll(ANTES);
        assertThat(novas).containsExactlyInAnyOrder("flyway_quack_sync", "SyncServidores", "SyncEstadoNuvem",
                "SyncLotePartes", "SyncEspelho", "SyncComandos", "SyncQuarentena", "SyncAuditoriaMapa",
                "SyncAlertas", "SyncConferencias", "SyncCargas");
        assertThat(depois).containsAll(ANTES);

        List<Map<String, Object>> historico = jdbc.query(
                "SELECT installed_rank, version, script FROM flyway_schema_history",
                (rs, i) -> Map.<String, Object>of("r", rs.getInt(1), "v", rs.getString(2), "s", rs.getString(3)));
        assertThat(historico).isEqualTo(HISTORICO_ANTES);
        // nem V2..V5 da loja rodaram: sem LoteBaixaPendencias (V4) nem as Sync da loja (SyncEstado, SyncCarga...)
        assertThat(depois).doesNotContain("LoteBaixaPendencias", "SyncEstado", "SyncCarga", "SyncReenvios");
    }

    @Test
    void trackingEmTodasAsNaoForaEEmMaisNenhuma() {
        ChangeTrackingAdmin admin = new ChangeTrackingAdmin(jdbc);
        assertThat(admin.snapshotLigado()).isTrue();
        assertThat(admin.bancoComTracking()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT retention_period FROM sys.change_tracking_databases WHERE database_id = DB_ID()", Integer.class))
                .isEqualTo(ChangeTrackingAdmin.RETENCAO_NUVEM_DIAS);
        assertThat(jdbc.queryForObject(
                "SELECT retention_period_units_desc FROM sys.change_tracking_databases WHERE database_id = DB_ID()",
                String.class)).isEqualTo("DAYS");
        assertThat(admin.tabelasComTracking()).containsExactlyInAnyOrderElementsOf(ChangeTrackingAdmin.tabelasEsperadas());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.change_tracking_tables WHERE is_track_columns_updated_on = 1", Integer.class))
                .isZero();
    }
}


