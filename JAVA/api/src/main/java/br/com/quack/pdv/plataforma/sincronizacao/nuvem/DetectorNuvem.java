package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Detector periodico das edicoes feitas na nuvem (D6): a cada ciclo, para cada empresa em modo loja, le o Change
 * Tracking desde {@code SyncEstadoNuvem.DetectorDesde} (ignorando o contexto {@code sync-loja}) e entrega cada linha ao
 * {@link MotorDeteccao}. Uma transacao SNAPSHOT por empresa; avanca {@code DetectorDesde} junto com os comandos criados.
 * Serializa com o aplicador de lotes pela trava {@link #recurso(String)}.
 */
@Component
@Profile("nuvem")
public class DetectorNuvem {

    private static final Logger LOG = LoggerFactory.getLogger(DetectorNuvem.class);
    private static final int SNAPSHOT = 4096;

    private final org.springframework.beans.factory.ObjectProvider<DataSource> dataSource;
    private final org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbc;
    private final org.springframework.beans.factory.ObjectProvider<MotorDeteccao> motor;
    private final QuackProperties props;
    private volatile boolean pausado;
    /** ReentrantLock em vez de synchronized: com virtual threads (Java 21) o synchronized prende a thread do sistema durante o I/O. */
    private final ReentrantLock trava = new ReentrantLock();

    public DetectorNuvem(org.springframework.beans.factory.ObjectProvider<DataSource> dataSource,
                         org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbc,
                         org.springframework.beans.factory.ObjectProvider<MotorDeteccao> motor, QuackProperties props) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.motor = motor;
        this.props = props;
    }

    /** Testes pausam o detector para montar corridas (lote antes do ciclo). */
    public void setPausado(boolean pausado) { this.pausado = pausado; }

    /** Nome da trava de aplicacao que serializa detector e aplicador de lotes de uma empresa. */
    static String recurso(String companyId) { return "quack:sync-nuvem:" + companyId; }

    @Scheduled(initialDelayString = "${quack.sync.intervalo-ms:2000}", fixedDelayString = "${quack.sync.intervalo-ms:2000}")
    public void agendado() {
        if (pausado || !props.getSync().isDetectorAtivo() || jdbc.getIfAvailable() == null) return;
        try {
            detectarTodas();
        } catch (RuntimeException e) {
            LOG.error("Erro no detector de edicoes da nuvem.", e);
        }
    }

    /** Um ciclo para todas as empresas em modo loja; devolve quantos comandos/avisos foram processados. */
    public int detectarTodas() {
        trava.lock();
        try {
            return detectarTodasSemTrava();
        } finally {
            trava.unlock();
        }
    }

    private int detectarTodasSemTrava() {
        List<String> empresas = jdbc.getObject().queryForList("""
                SELECT e.CompanyId FROM dbo.SyncEstadoNuvem e JOIN dbo.SyncServidores s ON s.Id = e.ServidorId
                 WHERE e.DetectorDesde IS NOT NULL AND s.Status = N'ativo'
                """, String.class);
        int n = 0;
        for (String empresa : empresas) n += detectar(empresa);
        return n;
    }

    /** Um ciclo de uma empresa. */
    public int detectar(String companyId) {
        trava.lock();
        try {
            return detectarSemTrava(companyId);
        } finally {
            trava.unlock();
        }
    }

    private int detectarSemTrava(String companyId) {
        try (Connection con = dataSource.getObject().getConnection()) {
            JdbcTemplate j = new JdbcTemplate(new SingleConnectionDataSource(con, true));
            Integer codigo = j.queryForObject("""
                    DECLARE @r INT;
                    EXEC @r = sp_getapplock @Resource = ?, @LockMode = 'Exclusive', @LockOwner = 'Session', @LockTimeout = 30000;
                    SELECT @r;
                    """, Integer.class, recurso(companyId));
            if (codigo == null || codigo < 0) throw new IllegalStateException("Trava do detector indisponivel (" + codigo + ").");
            try {
                con.setAutoCommit(false);
                con.setTransactionIsolation(SNAPSHOT);
                try {
                    int n = ciclo(j, companyId);
                    con.commit();
                    return n;
                } catch (RuntimeException e) {
                    con.rollback();
                    throw e;
                } finally {
                    con.setAutoCommit(true);
                    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                }
            } finally {
                j.queryForObject("DECLARE @r INT; EXEC @r = sp_releaseapplock @Resource = ?, @LockOwner = 'Session'; SELECT @r;",
                        Integer.class, recurso(companyId));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha no detector da nuvem: " + e.getMessage(), e);
        }
    }

    private int ciclo(JdbcTemplate j, String companyId) {
        List<Long> estado = j.query("SELECT DetectorDesde FROM dbo.SyncEstadoNuvem WHERE CompanyId = ?",
                (rs, i) -> { long v = rs.getLong(1); return rs.wasNull() ? null : v; }, companyId);
        if (estado.isEmpty() || estado.getFirst() == null) return 0;
        long desde = estado.getFirst();
        Long atual = j.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
        if (atual == null || atual <= desde) return 0;
        int n = 0;
        if (expirou(j, desde)) {
            LOG.warn("Detector parado alem da retencao do tracking: deteccao completa para {}.", companyId);
            n = motor.getObject().detectarCompleto(j, companyId);
        } else {
            MotorDeteccao mt = motor.getObject();
            ColetorOperacoes coletor = RecursosLoja.suporta(j, companyId, RecursosLoja.OPERACAO_ESTOQUE)
                    ? new ColetorOperacoes() : null;
            // O3: Produtos vem antes de EstoqueMovimentacoes na ordem, entao as movimentacoes do ciclo sao lidas antes.
            TabelaSincronizada movs = CatalogoSincronizacao.tabela("EstoqueMovimentacoes").orElseThrow();
            List<MotorDeteccao.Mudanca> movimentos = null;
            if (coletor != null) {
                movimentos = mt.lerMudancas(j, movs, companyId, desde);
                for (MotorDeteccao.Mudanca m : movimentos) {
                    if (m.linha() != null && "I".equals(m.operacao())) coletor.marcarProduto(m.linha().get("ProdutoId"));
                }
            }
            for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
                if (t.classe() == ClasseTabela.ACRESCIMO) continue;
                List<MotorDeteccao.Mudanca> lidas = t == movs && movimentos != null ? movimentos
                        : mt.lerMudancas(j, t, companyId, desde);
                for (MotorDeteccao.Mudanca m : lidas) {
                    mt.processarLinha(j, companyId, t, m.operacao(), m.pk(), m.linha(), false,
                            MotorDeteccao.ORIGEM_EDICAO, coletor, m.versao());
                    n++;
                }
            }
            if (coletor != null) n += mt.criarOperacoes(j, companyId, coletor);
        }
        j.update("UPDATE dbo.SyncEstadoNuvem SET DetectorDesde = ? WHERE CompanyId = ?", atual, companyId);
        return n;
    }

    private boolean expirou(JdbcTemplate j, long desde) {
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            Long minima = j.queryForObject("SELECT CHANGE_TRACKING_MIN_VALID_VERSION(OBJECT_ID(N'dbo." + t.nome() + "'))",
                    Long.class);
            if (minima != null && desde < minima) return true;
        }
        return false;
    }
}
