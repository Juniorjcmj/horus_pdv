package br.com.quack.pdv.plataforma.sincronizacao.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Perfil loja num banco proprio: V5 cria as Sync*, o tracking so liga na ativacao e so nas tabelas nao FORA. */
@SpringBootTest
@ActiveProfiles({"it", "loja"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChangeTrackingLojaIT {

    static final String BANCO = "loja_it";

    static {
        BancoIT.criarBanco(BANCO);
    }

    @DynamicPropertySource
    static void bancoDaLoja(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", () -> BancoIT.urlDoBanco(BANCO));
        registro.add("spring.datasource.username", BancoIT.SQL::getUsername);
        registro.add("spring.datasource.password", BancoIT.SQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ChangeTrackingAdmin admin;
    @Autowired ChangeTrackingLoja runner;

    @Test
    @Order(1)
    void subidaDaLojaCriaSyncLigaSnapshotEDeixaTrackingDesligadoAteAtivar() {
        assertThat(jdbc.queryForObject("SELECT DB_NAME()", String.class)).isEqualTo(BANCO);
        List<String> sync = jdbc.queryForList("SELECT name FROM sys.tables WHERE name LIKE 'Sync%'", String.class);
        assertThat(sync).containsExactlyInAnyOrder("SyncEstado", "SyncReenvios", "SyncAlertas", "SyncConferencias",
                "SyncCarga", "SyncComandosAplicados", "SyncRecuperacao", "SyncRecuperacaoTabelas");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SyncEstado", Integer.class)).isEqualTo(1);
        assertThat(admin.snapshotLigado()).isTrue();
        assertThat(admin.bancoComTracking()).isFalse();
        assertThat(admin.tabelasComTracking()).isEmpty();
    }

    @Test
    @Order(2)
    void ativacaoLigaTrackingSoNasTabelasNaoFora() {
        int ligadas = admin.ligarTudo(ChangeTrackingAdmin.RETENCAO_LOJA_DIAS);
        assertThat(ligadas).isEqualTo(ChangeTrackingAdmin.tabelasEsperadas().size());
        assertThat(admin.bancoComTracking()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT retention_period FROM sys.change_tracking_databases WHERE database_id = DB_ID()", Integer.class))
                .isEqualTo(14);
        assertThat(admin.tabelasComTracking()).containsExactlyInAnyOrderElementsOf(ChangeTrackingAdmin.tabelasEsperadas());
        // FORA (inclusive Sync* e LoteBaixaPendencias) fica sem tracking
        Set<String> fora = new HashSet<>(jdbc.queryForList("SELECT name FROM sys.tables", String.class));
        fora.removeAll(ChangeTrackingAdmin.tabelasEsperadas());
        assertThat(fora).contains("Sessoes", "ProdutoClassificacao", "SyncEstado", "LoteBaixaPendencias");
        assertThat(admin.tabelasComTracking()).doesNotContainAnyElementsOf(fora);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.change_tracking_tables WHERE is_track_columns_updated_on = 1", Integer.class))
                .isZero();
        assertThat(admin.ligarTudo(14)).isZero(); // idempotente
    }

    @Test
    @Order(3)
    void lojaAtivadaReligaTrackingNaSubida() {
        admin.desligarTudo();
        assertThat(admin.tabelasComTracking()).isEmpty();
        assertThat(admin.bancoComTracking()).isFalse();

        runner.run(null); // loja ainda nao ativada: so o snapshot
        assertThat(admin.snapshotLigado()).isTrue();
        assertThat(admin.bancoComTracking()).isFalse();

        jdbc.update("UPDATE SyncEstado SET Ativada = 1 WHERE Id = 1");
        runner.run(null);
        assertThat(admin.bancoComTracking()).isTrue();
        assertThat(admin.tabelasComTracking()).containsExactlyInAnyOrderElementsOf(ChangeTrackingAdmin.tabelasEsperadas());
    }
}


