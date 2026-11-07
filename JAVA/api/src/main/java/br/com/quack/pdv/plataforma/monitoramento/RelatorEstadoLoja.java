package br.com.quack.pdv.plataforma.monitoramento;

import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteJsonNuvem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relato de estado da loja a nuvem (M4): a cada 60 s envia o que so o servidor da loja sabe (atraso do envio, backup,
 * NFC-e pendentes, pausa, versao). E observacao, nao sincronizacao: nunca usa a fila de envio, nunca grava alerta, nao
 * disputa as travas do envio e descarta o relato do minuto se a nuvem nao responde. Receptor antigo (404) so rende uma
 * linha de log por hora.
 */
@Component
@Profile("loja")
public class RelatorEstadoLoja {

    private static final Logger LOG = LoggerFactory.getLogger(RelatorEstadoLoja.class);
    static final String ROTA = "/api/loja-sync/estado";
    static final int TIMEOUT_S = 10;
    static final Duration INTERVALO_LOG_404 = Duration.ofHours(1);
    static final int LIMITE_ERRO = 500;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** O que aconteceu com o relato de um ciclo. */
    public enum Resultado { ENVIADO, DESATIVADA, RECEPTOR_ANTIGO, DESCARTADO }

    private final RetratoLoja retrato;
    private final ClienteJsonNuvem nuvem;
    private final Clock clock;
    private final AtomicInteger logs404 = new AtomicInteger();
    private volatile Instant ultimoLog404;

    public RelatorEstadoLoja(RetratoLoja retrato, ClienteJsonNuvem nuvem, Clock clock) {
        this.retrato = retrato;
        this.nuvem = nuvem;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${quack.metrics.relato-intervalo-ms:60000}")
    public void agendado() {
        try {
            relatar();
        } catch (RuntimeException e) {
            LOG.debug("Relato de estado nao enviado: {}", e.getMessage());
        }
    }

    /** Monta e envia um relato agora. Publico para os testes. */
    public Resultado relatar() {
        RetratoLoja.Retrato r = retrato.obter();
        if (r == null || !r.sincronizacaoAtivada()) return Resultado.DESATIVADA;
        ObjectNode corpo = JSON.createObjectNode();
        corpo.put("geradoEm", r.lidoEm().toString());
        if (r.atrasoSegundos() == null) corpo.putNull("atrasoSegundos");
        else corpo.put("atrasoSegundos", r.atrasoSegundos());
        corpo.put("pausada", r.pausada());
        corpo.put("ultimoBackupEm", r.ultimoBackupEm() == null ? null : r.ultimoBackupEm().toString());
        if (r.backupSucesso() == null) corpo.putNull("backupSucesso");
        else corpo.put("backupSucesso", r.backupSucesso());
        String erro = r.backupErro();
        corpo.put("backupErro", erro == null || erro.length() <= LIMITE_ERRO ? erro : erro.substring(0, LIMITE_ERRO));
        corpo.put("nfcePendentes", r.nfcePendentes());
        corpo.put("versaoPrograma", r.versaoPrograma());
        try {
            nuvem.post(ROTA, corpo, TIMEOUT_S);
            return Resultado.ENVIADO;
        } catch (ClienteJsonNuvem.RespostaHttpException e) {
            if (e.status() == 404) {
                registrarReceptorAntigo();
                return Resultado.RECEPTOR_ANTIGO;
            }
            LOG.debug("Relato de estado recusado pela nuvem: {}", e.getMessage());
            return Resultado.DESCARTADO;
        } catch (IOException e) {
            LOG.debug("Relato de estado descartado (nuvem inacessivel): {}", e.getMessage());
            return Resultado.DESCARTADO;
        }
    }

    private void registrarReceptorAntigo() {
        Instant agora = clock.instant();
        Instant anterior = ultimoLog404;
        if (anterior != null && Duration.between(anterior, agora).compareTo(INTERVALO_LOG_404) < 0) return;
        ultimoLog404 = agora;
        logs404.incrementAndGet();
        LOG.info("A nuvem ainda nao aceita o relato de estado ({}): receptor antigo; a sincronizacao segue normal.", ROTA);
    }

    /** Quantas linhas de log o 404 ja gerou (para os testes). */
    int logsDe404() {
        return logs404.get();
    }
}
