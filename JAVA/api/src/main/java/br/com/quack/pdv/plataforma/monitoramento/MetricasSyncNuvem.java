package br.com.quack.pdv.plataforma.monitoramento;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Metricas da sincronizacao por loja no receptor da nuvem (M3 e M5). Um agendamento de 30 s faz poucas consultas
 * agrupadas por empresa e troca o retrato dos medidores ({@link MultiGauge} com {@code overwrite=true}: empresa que some
 * do resultado, como um servidor revogado, perde as series no ciclo seguinte). A raspagem do Prometheus nunca toca o
 * banco: as idades sao calculadas na hora da raspagem a partir de instantes ja lidos. Erro de consulta mantem o retrato
 * anterior e so gera um WARN por ciclo.
 */
@Component
@Profile("nuvem")
public class MetricasSyncNuvem {

    private static final Logger LOG = LoggerFactory.getLogger(MetricasSyncNuvem.class);
    static final String[] STATUS_COMANDOS = {"pendente", "parado", "conflito_24h", "falhou_24h"};

    private record Loja(String empresa, String nome, String status, OffsetDateTime ultimoContato,
                        OffsetDateTime ultimaSincronizacao, Relato relato) {
        Tags tags() {
            return Tags.of("empresa", empresa, "loja", nome);
        }
    }

    private record Relato(OffsetDateTime recebidoEm, Long atrasoSegundos, Boolean pausada, OffsetDateTime ultimoBackupEm,
                          Boolean backupSucesso, Integer nfcePendentes, String versao) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Timer cicloTimer;
    private final ReentrantLock trava = new ReentrantLock();

    private final MultiGauge ultimoContato;
    private final MultiGauge ultimaSincronizacao;
    private final MultiGauge servidorAtivo;
    private final MultiGauge comandos;
    private final MultiGauge quarentena;
    private final MultiGauge alertas;
    private final MultiGauge relatoIdade;
    private final MultiGauge atrasoEnvio;
    private final MultiGauge backupIdade;
    private final MultiGauge backupSucesso;
    private final MultiGauge nfcePendentes;
    private final MultiGauge pausada;
    private final MultiGauge info;

    public MetricasSyncNuvem(JdbcTemplate jdbc, MeterRegistry registro, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.cicloTimer = Timer.builder("quack_sync_metricas_ciclo")
                .description("Duracao do ciclo que le o banco para publicar as metricas").register(registro);
        ultimoContato = multi(registro, "quack_sync_ultimo_contato_segundos", "Segundos desde o ultimo contato do servidor da loja");
        ultimaSincronizacao = multi(registro, "quack_sync_ultima_sincronizacao_segundos", "Segundos desde a ultima sincronizacao aplicada");
        servidorAtivo = multi(registro, "quack_sync_servidor_ativo", "1 se o servidor da loja esta ativo, 0 se pendente");
        comandos = multi(registro, "quack_sync_comandos", "Comandos por situacao (pendente, parado, conflito_24h, falhou_24h)");
        quarentena = multi(registro, "quack_sync_quarentena_aberta", "Entradas abertas na quarentena");
        alertas = multi(registro, "quack_sync_alertas", "Alertas das ultimas 24 h por motivo");
        relatoIdade = multi(registro, "quack_loja_relato_idade_segundos", "Segundos desde o ultimo relato da loja");
        atrasoEnvio = multi(registro, "quack_loja_atraso_envio_segundos", "Atraso do envio informado pela loja");
        backupIdade = multi(registro, "quack_loja_backup_idade_segundos", "Segundos desde o ultimo backup bem-sucedido");
        backupSucesso = multi(registro, "quack_loja_backup_sucesso", "1 se a ultima tentativa de backup deu certo, 0 se falhou");
        nfcePendentes = multi(registro, "quack_loja_nfce_pendentes", "NFC-e pendentes ha mais de 10 min");
        pausada = multi(registro, "quack_loja_pausada", "1 se a sincronizacao da loja esta pausada");
        info = multi(registro, "quack_loja_versao_programa", "Versao do programa do servidor da loja");
    }

    private static MultiGauge multi(MeterRegistry registro, String nome, String descricao) {
        return MultiGauge.builder(nome).description(descricao).register(registro);
    }

    @Scheduled(initialDelay = 10_000, fixedDelayString = "${quack.metrics.intervalo-ms:30000}")
    public void agendado() {
        atualizar();
    }

    /** Le o banco e troca o retrato dos medidores. Publico para os testes; nunca lanca excecao. */
    public void atualizar() {
        trava.lock();
        long inicio = System.nanoTime();
        try {
            publicar(clock.instant().atOffset(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            LOG.warn("Metricas da sincronizacao nao atualizadas (mantido o retrato anterior): {}", e.getMessage());
        } finally {
            cicloTimer.record(System.nanoTime() - inicio, TimeUnit.NANOSECONDS);
            trava.unlock();
        }
    }

    private void publicar(OffsetDateTime agora) {
        OffsetDateTime corte24h = agora.minusHours(24);
        List<Loja> lojas = lerLojas();
        Map<String, Map<String, Integer>> porComando = lerComandos(corte24h);
        Map<String, Integer> porQuarentena = lerQuarentena();
        List<String[]> porAlerta = lerAlertas(corte24h);
        // tudo lido sem excecao: so agora troca os medidores
        Map<String, Loja> porEmpresa = new HashMap<>();
        for (Loja l : lojas) porEmpresa.put(l.empresa(), l);

        List<Row<?>> rUltimoContato = new ArrayList<>();
        List<Row<?>> rUltimaSync = new ArrayList<>();
        List<Row<?>> rAtivo = new ArrayList<>();
        List<Row<?>> rComandos = new ArrayList<>();
        List<Row<?>> rQuarentena = new ArrayList<>();
        List<Row<?>> rAlertas = new ArrayList<>();
        List<Row<?>> rRelatoIdade = new ArrayList<>();
        List<Row<?>> rAtraso = new ArrayList<>();
        List<Row<?>> rBackupIdade = new ArrayList<>();
        List<Row<?>> rBackupSucesso = new ArrayList<>();
        List<Row<?>> rNfce = new ArrayList<>();
        List<Row<?>> rPausada = new ArrayList<>();
        List<Row<?>> rInfo = new ArrayList<>();

        for (Loja l : lojas) {
            Tags t = l.tags();
            if (l.ultimoContato() != null) rUltimoContato.add(Row.of(t, l.ultimoContato(), this::idade));
            if (l.ultimaSincronizacao() != null) rUltimaSync.add(Row.of(t, l.ultimaSincronizacao(), this::idade));
            rAtivo.add(Row.of(t, "ativo".equals(l.status()) ? 1 : 0));
            Map<String, Integer> c = porComando.getOrDefault(l.empresa(), Map.of());
            for (String status : STATUS_COMANDOS) rComandos.add(Row.of(t.and("status", status), c.getOrDefault(status, 0)));
            rQuarentena.add(Row.of(t, porQuarentena.getOrDefault(l.empresa(), 0)));
            Relato r = l.relato();
            if (r == null) continue; // loja com jar antigo (nunca relatou): nenhuma serie quack_loja_*
            rRelatoIdade.add(Row.of(t, r.recebidoEm(), this::idade));
            if (r.atrasoSegundos() != null) rAtraso.add(Row.of(t, r.atrasoSegundos()));
            if (r.ultimoBackupEm() != null) rBackupIdade.add(Row.of(t, r.ultimoBackupEm(), this::idade));
            if (r.backupSucesso() != null) rBackupSucesso.add(Row.of(t, r.backupSucesso() ? 1 : 0));
            if (r.nfcePendentes() != null) rNfce.add(Row.of(t, r.nfcePendentes()));
            if (r.pausada() != null) rPausada.add(Row.of(t, r.pausada() ? 1 : 0));
            if (r.versao() != null && !r.versao().isBlank()) rInfo.add(Row.of(t.and("versao", r.versao()), 1));
        }
        for (String[] a : porAlerta) {
            Loja l = porEmpresa.get(a[0]);
            if (l != null) rAlertas.add(Row.of(l.tags().and("motivo", a[1]), 1));
        }

        ultimoContato.register(rUltimoContato, true);
        ultimaSincronizacao.register(rUltimaSync, true);
        servidorAtivo.register(rAtivo, true);
        comandos.register(rComandos, true);
        quarentena.register(rQuarentena, true);
        alertas.register(rAlertas, true);
        relatoIdade.register(rRelatoIdade, true);
        atrasoEnvio.register(rAtraso, true);
        backupIdade.register(rBackupIdade, true);
        backupSucesso.register(rBackupSucesso, true);
        nfcePendentes.register(rNfce, true);
        pausada.register(rPausada, true);
        info.register(rInfo, true);
    }

    /** Segundos entre o instante e agora, calculado na raspagem (a leitura do banco pode ter ate 30 s). */
    private double idade(OffsetDateTime instante) {
        return Math.max(0, Duration.between(instante.toInstant(), clock.instant()).toSeconds());
    }

    private List<Loja> lerLojas() {
        return jdbc.query("""
                SELECT s.CompanyId, s.Nome, s.Status, s.UltimoContato, e.UltimaSincronizacaoEm,
                       r.CompanyId, r.RecebidoEm, r.AtrasoSegundos, r.Pausada, r.UltimoBackupEm, r.BackupSucesso,
                       r.NfcePendentes, r.VersaoPrograma
                  FROM dbo.SyncServidores s
                  LEFT JOIN dbo.SyncEstadoNuvem e ON e.CompanyId = s.CompanyId
                  LEFT JOIN dbo.SyncEstadoLoja r ON r.CompanyId = s.CompanyId
                 WHERE s.Status <> N'revogado'
                """, (rs, i) -> {
            Relato relato = rs.getString(6) == null ? null : new Relato(rs.getObject(7, OffsetDateTime.class),
                    (Long) rs.getObject(8), (Boolean) rs.getObject(9), rs.getObject(10, OffsetDateTime.class),
                    (Boolean) rs.getObject(11), (Integer) rs.getObject(12), rs.getString(13));
            return new Loja(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                    rs.getObject(5, OffsetDateTime.class), relato);
        });
    }

    private Map<String, Map<String, Integer>> lerComandos(OffsetDateTime corte) {
        Map<String, Map<String, Integer>> m = new HashMap<>();
        jdbc.query("""
                SELECT CompanyId, Status, COUNT(*) FROM dbo.SyncComandos
                 WHERE Status IN (N'pendente', N'parado')
                    OR (Status IN (N'conflito', N'falhou') AND AtualizadoEm >= ?)
                 GROUP BY CompanyId, Status
                """, rs -> {
            String status = rs.getString(2);
            String nome = "conflito".equals(status) || "falhou".equals(status) ? status + "_24h" : status;
            m.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(nome, rs.getInt(3));
        }, corte);
        return m;
    }

    private Map<String, Integer> lerQuarentena() {
        Map<String, Integer> m = new HashMap<>();
        jdbc.query("SELECT CompanyId, COUNT(*) FROM dbo.SyncQuarentena WHERE ResolvidoEm IS NULL GROUP BY CompanyId",
                rs -> {
                    m.put(rs.getString(1), rs.getInt(2));
                });
        return m;
    }

    private List<String[]> lerAlertas(OffsetDateTime corte) {
        return jdbc.query("SELECT CompanyId, Motivo FROM dbo.SyncAlertas WHERE UltimaEm >= ? AND CompanyId <> N''",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, corte);
    }
}
