package br.com.quack.pdv.plataforma.sincronizacao.envio;

import br.com.quack.pdv.plataforma.sincronizacao.envio.ClassificadorErro.Falha;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ParteLote;
import br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.SerializadorLote;
import br.com.quack.pdv.security.SecretProtector;
import br.com.quack.pdv.config.QuackProperties;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Envio loja -> nuvem (D4). A cada 2 s ocioso: le as mudancas desde SyncEstado.VersaoDe (retrato SNAPSHOT), envia por
 * partes e so avanca a versao depois da confirmacao. Falha temporaria: backoff com jitter. Falha definitiva: pausa e
 * alerta; so a rota de retomada religa. 409: recomeca da versao que a nuvem informa. Passou da retencao: retrato completo.
 */
@Component
@Profile("loja")
public class EnvioSincronizacao {

    private static final Logger LOG = LoggerFactory.getLogger(EnvioSincronizacao.class);

    public enum Resultado { INATIVO, AGUARDANDO, PAUSADO, SEM_MUDANCAS, ENVIADO, RECUPERANDO, REENVIAR_DA_NUVEM, FALHA_TEMPORARIA, FALHA_DEFINITIVA }

    private record Estado(boolean ativada, String companyId, String url, String tokenCifrado, Long versaoDe,
                          OffsetDateTime proxima, String pausa, int tentativas) {}

    /** Interrompe o envio de partes em andamento. */
    private static final class EnvioFalhou extends RuntimeException {
        final Falha falha;
        final Long esperado;
        EnvioFalhou(Falha falha, String msg, Long esperado, Throwable causa) {
            super(msg, causa);
            this.falha = falha;
            this.esperado = esperado;
        }
    }

    private final JdbcTemplate jdbc;
    private final LeitorMudancas leitor;
    private final ClienteNuvem cliente;
    private final SecretProtector protetor;
    private final QuackProperties props;
    private final Clock clock;
    private final List<AntesDoEnvio> antes;
    private volatile EstadoRecuperacao recuperacao;

    public EnvioSincronizacao(JdbcTemplate jdbc, LeitorMudancas leitor, ClienteNuvem cliente, SecretProtector protetor,
                              QuackProperties props, Clock clock, List<AntesDoEnvio> antes) {
        this.jdbc = jdbc;
        this.leitor = leitor;
        this.cliente = cliente;
        this.protetor = protetor;
        this.props = props;
        this.clock = clock;
        this.antes = antes;
    }

    /** Opcional (nao existe fora do perfil loja): enquanto a loja se recupera de uma restauracao nada e enviado. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void definirRecuperacao(EstadoRecuperacao recuperacao) { this.recuperacao = recuperacao; }

    @Scheduled(initialDelayString = "${quack.sync.intervalo-ms:2000}", fixedDelayString = "${quack.sync.intervalo-ms:2000}")
    public void executarAgendado() {
        if (!props.getSync().isEnvioAtivo()) return;
        try {
            ciclo();
        } catch (RuntimeException e) {
            LOG.error("Erro inesperado no ciclo de sincronizacao.", e);
        }
    }

    /** Um ciclo; sincronizado porque a rota de retomada e os testes tambem o chamam. */
    public synchronized Resultado ciclo() {
        if (recuperacao != null && recuperacao.emAndamento()) return Resultado.RECUPERANDO;
        Estado e = ler();
        if (!e.ativada() || e.versaoDe() == null || e.tokenCifrado() == null || e.tokenCifrado().isBlank()
                || e.url().isBlank()) return Resultado.INATIVO;
        if (e.pausa() != null) return Resultado.PAUSADO;
        if (e.proxima() != null && e.proxima().isAfter(OffsetDateTime.now(clock))) return Resultado.AGUARDANDO;

        for (AntesDoEnvio a : antes) a.executar();
        String token = protetor.unprotect(e.tokenCifrado());
        List<String[]> reenvios = reenvios();
        long[] confirmado = {-1};
        try {
            LeitorMudancas.Leitura l;
            try {
                l = leitor.ler(e.versaoDe(), false, novoLote(), e.companyId(), reenvios, p -> enviar(e.url(), token, e.companyId(), p, confirmado));
            } catch (LeitorMudancas.VersaoExpiradaException ex) {
                LOG.warn("Loja passou da retencao do Change Tracking: ressincronizacao completa. {}", ex.getMessage());
                l = leitor.ler(e.versaoDe(), true, novoLote(), e.companyId(), List.of(), p -> enviar(e.url(), token, e.companyId(), p, confirmado));
            }
            if (!l.enviou()) return Resultado.SEM_MUDANCAS;
            long novaVersao = confirmado[0] >= 0 ? confirmado[0] : l.ate();
            sucesso(novaVersao, reenvios);
            return Resultado.ENVIADO;
        } catch (EnvioFalhou f) {
            return falha(e, f);
        } catch (IllegalStateException f) {
            LOG.error("Falha local ao montar o lote: {}", f.getMessage());
            return falha(e, new EnvioFalhou(Falha.TEMPORARIA, f.getMessage(), null, f));
        }
    }

    private void enviar(String url, String token, String companyId, ParteLote parte, long[] confirmado) {
        byte[] corpo = SerializadorLote.paraGzip(parte.comEmpresa(companyId));
        ClienteNuvem.Resposta r;
        try {
            r = cliente.enviarParte(url, token, corpo);
        } catch (IOException ex) {
            throw new EnvioFalhou(ClassificadorErro.porExcecao(ex), "Rede: " + ex.getMessage(), null, ex);
        }
        if (r.status() >= 200 && r.status() < 300) {
            if (r.aplicadoAte() != null) confirmado[0] = r.aplicadoAte();
            return;
        }
        Falha f = ClassificadorErro.porStatus(r.status());
        throw new EnvioFalhou(f, "Nuvem respondeu " + r.status() + (r.mensagem() == null ? "" : ": " + r.mensagem()),
                r.aplicadoAte(), null);
    }

    private Resultado falha(Estado e, EnvioFalhou f) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        switch (f.falha) {
            case VERSAO -> {
                if (f.esperado == null) return pausar(f, e);
                LOG.warn("Nuvem pediu para recomecar da versao {}.", f.esperado);
                jdbc.update("UPDATE SyncEstado SET VersaoDe = ?, ProximaTentativaEm = NULL, UltimoErro = ?, AtualizadoEm = ? WHERE Id = 1",
                        f.esperado, limitar(f.getMessage()), agora);
                return Resultado.REENVIAR_DA_NUVEM;
            }
            case TEMPORARIA -> {
                int n = e.tentativas() + 1;
                OffsetDateTime proxima = agora.plus(Backoff.espera(n, () -> ThreadLocalRandom.current().nextDouble()));
                LOG.warn("Sincronizacao: falha temporaria ({}); tentativa {} em {}.", f.getMessage(), n, proxima);
                jdbc.update("UPDATE SyncEstado SET TentativasSeguidas = ?, ProximaTentativaEm = ?, UltimoErro = ?, AtualizadoEm = ? WHERE Id = 1",
                        n, proxima, limitar(f.getMessage()), agora);
                return Resultado.FALHA_TEMPORARIA;
            }
            default -> {
                return pausar(f, e);
            }
        }
    }

    private Resultado pausar(EnvioFalhou f, Estado e) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        String motivo = limitar(f.getMessage());
        LOG.error("Sincronizacao PAUSADA: {}", motivo);
        jdbc.update("UPDATE SyncEstado SET PausadaEm = ?, PausadaMotivo = ?, UltimoErro = ?, AtualizadoEm = ? WHERE Id = 1",
                agora, motivo, motivo, agora);
        br.com.quack.pdv.plataforma.sincronizacao.alertas.AlertasSync.registrar(jdbc, agora,
                br.com.quack.pdv.plataforma.sincronizacao.alertas.AlertasSync.PAUSADA, e.companyId(), motivo);
        return Resultado.FALHA_DEFINITIVA;
    }

    /** Retomada manual (rota /api/loja-sync/retomar) depois de corrigir o problema. */
    public void retomar() {
        jdbc.update("UPDATE SyncEstado SET PausadaEm = NULL, PausadaMotivo = NULL, TentativasSeguidas = 0, ProximaTentativaEm = NULL, AtualizadoEm = ? WHERE Id = 1",
                OffsetDateTime.now(clock));
    }

    private void sucesso(long versao, List<String[]> reenvios) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        jdbc.update("""
                UPDATE SyncEstado SET VersaoDe = ?, AplicadoAteNuvem = ?, UltimaSincronizacaoEm = ?, UltimoErro = NULL,
                       TentativasSeguidas = 0, ProximaTentativaEm = NULL, AtualizadoEm = ? WHERE Id = 1
                """, versao, versao, agora, agora);
        for (String[] r : reenvios) jdbc.update("DELETE FROM SyncReenvios WHERE Tabela = ? AND Chave = ?", r[0], r[1]);
    }

    private List<String[]> reenvios() {
        return new ArrayList<>(jdbc.query("SELECT Tabela, Chave FROM SyncReenvios ORDER BY CriadoEm",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}));
    }

    private Estado ler() {
        return jdbc.queryForObject("""
                SELECT Ativada, CompanyId, NuvemUrl, TokenCifrado, VersaoDe, ProximaTentativaEm, PausadaMotivo, TentativasSeguidas
                  FROM SyncEstado WHERE Id = 1
                """, (rs, i) -> {
            String url = rs.getString(3);
            if (url == null || url.isBlank()) url = props.getSync().getNuvemUrl();
            long v = rs.getLong(5);
            return new Estado(rs.getBoolean(1), rs.getString(2), url, rs.getString(4), rs.wasNull() ? null : v,
                    rs.getObject(6, OffsetDateTime.class), rs.getString(7), rs.getInt(8));
        });
    }

    private static String novoLote() { return UUID.randomUUID().toString().replace("-", ""); }

    private static String limitar(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480);
    }
}
