package br.com.quack.pdv.plataforma.monitoramento;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.MultiGauge.Row;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Metricas locais do servidor da loja (M6), lidas na rede da loja mesmo sem internet. Os mesmos valores do relato a
 * nuvem, sem rotulo de empresa (a loja tem uma so). Atualiza a cada 30 s a partir do {@link RetratoLoja}; a raspagem
 * nao consulta o banco.
 */
@Component
@Profile("loja")
public class MetricasLoja {

    private final RetratoLoja retrato;
    private final Clock clock;
    private final MultiGauge atrasoEnvio;
    private final MultiGauge backupIdade;
    private final MultiGauge backupSucesso;
    private final MultiGauge nfcePendentes;
    private final MultiGauge pausada;
    private final MultiGauge info;

    public MetricasLoja(RetratoLoja retrato, MeterRegistry registro, Clock clock) {
        this.retrato = retrato;
        this.clock = clock;
        atrasoEnvio = multi(registro, "quack_loja_atraso_envio_segundos", "Atraso do envio a nuvem");
        backupIdade = multi(registro, "quack_loja_backup_idade_segundos", "Segundos desde o ultimo backup bem-sucedido");
        backupSucesso = multi(registro, "quack_loja_backup_sucesso", "1 se a ultima tentativa de backup deu certo, 0 se falhou");
        nfcePendentes = multi(registro, "quack_loja_nfce_pendentes", "NFC-e pendentes ha mais de 10 min");
        pausada = multi(registro, "quack_loja_pausada", "1 se a sincronizacao esta pausada");
        info = multi(registro, "quack_loja_versao_programa", "Versao do programa");
    }

    private static MultiGauge multi(MeterRegistry registro, String nome, String descricao) {
        return MultiGauge.builder(nome).description(descricao).register(registro);
    }

    @Scheduled(initialDelay = 15_000, fixedDelayString = "${quack.metrics.intervalo-ms:30000}")
    public void agendado() {
        atualizar();
    }

    /** Le um retrato novo e troca os medidores. Publico para os testes; nunca lanca excecao. */
    public void atualizar() {
        RetratoLoja.Retrato r = retrato.atualizar();
        if (r == null) return;
        List<Row<?>> rAtraso = new ArrayList<>();
        List<Row<?>> rBackupIdade = new ArrayList<>();
        List<Row<?>> rBackupSucesso = new ArrayList<>();
        List<Row<?>> rNfce = new ArrayList<>();
        List<Row<?>> rPausada = new ArrayList<>();
        List<Row<?>> rInfo = new ArrayList<>();
        if (r.atrasoSegundos() != null) rAtraso.add(Row.of(Tags.empty(), r.atrasoSegundos()));
        if (r.ultimoBackupEm() != null) rBackupIdade.add(Row.of(Tags.empty(), r.ultimoBackupEm(), this::idade));
        if (r.backupSucesso() != null) rBackupSucesso.add(Row.of(Tags.empty(), r.backupSucesso() ? 1 : 0));
        rNfce.add(Row.of(Tags.empty(), r.nfcePendentes()));
        rPausada.add(Row.of(Tags.empty(), r.pausada() ? 1 : 0));
        if (r.versaoPrograma() != null) rInfo.add(Row.of(Tags.of("versao", r.versaoPrograma()), 1));
        atrasoEnvio.register(rAtraso, true);
        backupIdade.register(rBackupIdade, true);
        backupSucesso.register(rBackupSucesso, true);
        nfcePendentes.register(rNfce, true);
        pausada.register(rPausada, true);
        info.register(rInfo, true);
    }

    private double idade(OffsetDateTime instante) {
        return Math.max(0, Duration.between(instante.toInstant(), clock.instant()).toSeconds());
    }
}
