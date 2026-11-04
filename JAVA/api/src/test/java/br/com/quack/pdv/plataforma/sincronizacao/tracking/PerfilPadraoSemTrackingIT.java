package br.com.quack.pdv.plataforma.sincronizacao.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.BancoIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** O perfil padrao (bancos de teste do contrato) cria as Sync* da loja mas nunca liga Change Tracking nem snapshot. */
@SpringBootTest
class PerfilPadraoSemTrackingIT extends BancoIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext contexto;

    @Test
    void bancoPadraoFicaSemTrackingESemSnapshot() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.change_tracking_databases WHERE database_id = DB_ID()",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.change_tracking_tables", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT snapshot_isolation_state FROM sys.databases WHERE database_id = DB_ID()",
                Integer.class)).isZero();
        assertThat(contexto.getBeansOfType(ChangeTrackingAdmin.class)).isEmpty();
        assertThat(contexto.getBeansOfType(ChangeTrackingLoja.class)).isEmpty();
    }

    @Test
    void migracaoV5CriouAsTabelasSyncDaLoja() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.tables WHERE name LIKE 'Sync%'", Integer.class))
                .isEqualTo(6);
    }
}
