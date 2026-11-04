package br.com.quack.pdv.plataforma.sincronizacao.tracking;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Liga e desliga o Change Tracking e o isolamento snapshot (D3) nas tabelas nao FORA do catalogo. Idempotente.
 * {@code ALTER DATABASE} nao roda dentro de transacao: este componente nao e transacional e usa o autocommit do
 * {@link JdbcTemplate}. Bean so no perfil loja: no padrao nada o chama, e os bancos de teste do contrato ficam sem tracking.
 */
@Component
@org.springframework.context.annotation.Profile("loja")
public class ChangeTrackingAdmin {

    public static final int RETENCAO_LOJA_DIAS = 14;
    public static final int RETENCAO_NUVEM_DIAS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(ChangeTrackingAdmin.class);

    private final JdbcTemplate jdbc;

    public ChangeTrackingAdmin(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean snapshotLigado() {
        Integer estado = jdbc.queryForObject(
                "SELECT snapshot_isolation_state FROM sys.databases WHERE database_id = DB_ID()", Integer.class);
        return estado != null && estado == 1;
    }

    public boolean bancoComTracking() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys.change_tracking_databases WHERE database_id = DB_ID()", Integer.class);
        return n != null && n > 0;
    }

    /** Tabelas (dbo) com Change Tracking ligado. */
    public Set<String> tabelasComTracking() {
        return new HashSet<>(jdbc.queryForList(
                "SELECT t.name FROM sys.change_tracking_tables ct JOIN sys.tables t ON t.object_id = ct.object_id",
                String.class));
    }

    public void ligarSnapshot() {
        if (snapshotLigado()) return;
        jdbc.execute("ALTER DATABASE " + nomeDoBanco() + " SET ALLOW_SNAPSHOT_ISOLATION ON");
        LOG.info("ALLOW_SNAPSHOT_ISOLATION ligado.");
    }

    public void ligarBanco(int retencaoDias) {
        if (bancoComTracking()) return;
        jdbc.execute("ALTER DATABASE " + nomeDoBanco() + " SET CHANGE_TRACKING = ON (CHANGE_RETENTION = "
                + retencaoDias + " DAYS, AUTO_CLEANUP = ON)");
        LOG.info("Change Tracking ligado no banco (retencao {} dias).", retencaoDias);
    }

    /** Liga nas tabelas nao FORA que ainda nao tem. Devolve quantas ligou. */
    public int ligarTabelas() {
        Set<String> ja = tabelasComTracking();
        int ligadas = 0;
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            if (ja.contains(t.nome())) continue;
            jdbc.execute("ALTER TABLE dbo.[" + t.nome() + "] ENABLE CHANGE_TRACKING WITH (TRACK_COLUMNS_UPDATED = OFF)");
            ligadas++;
        }
        return ligadas;
    }

    /** Snapshot + banco + tabelas, na ordem que o SQL Server exige. */
    public int ligarTudo(int retencaoDias) {
        ligarSnapshot();
        ligarBanco(retencaoDias);
        return ligarTabelas();
    }

    /** Procedimento de emergencia (rollback, D12): desliga tabelas, banco e snapshot. Mantem as tabelas Sync*. */
    public void desligarTudo() {
        for (String tabela : tabelasComTracking()) {
            jdbc.execute("ALTER TABLE dbo.[" + tabela + "] DISABLE CHANGE_TRACKING");
        }
        if (bancoComTracking()) jdbc.execute("ALTER DATABASE " + nomeDoBanco() + " SET CHANGE_TRACKING = OFF");
        if (snapshotLigado()) jdbc.execute("ALTER DATABASE " + nomeDoBanco() + " SET ALLOW_SNAPSHOT_ISOLATION OFF");
    }

    /** Nomes das tabelas que devem ter tracking (todas as nao FORA). */
    public static List<String> tabelasEsperadas() {
        return CatalogoSincronizacao.ordemDeAplicacao().stream().map(TabelaSincronizada::nome).toList();
    }

    private String nomeDoBanco() {
        String nome = jdbc.queryForObject("SELECT DB_NAME()", String.class);
        return "[" + nome.replace("]", "]]") + "]";
    }
}
