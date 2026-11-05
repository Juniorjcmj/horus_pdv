package br.com.quack.pdv.plataforma.sincronizacao.recuperacao;

import br.com.quack.pdv.config.QuackProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Estado da recuperacao pos-restauracao (tarefa 7.4), guardado em {@code SyncRecuperacao}. E consultado a cada
 * requisicao pelo filtro que bloqueia vendas, pelo envio, pela conferencia e pelas rotinas de escrita em segundo plano.
 *
 * <p>"Em andamento" = marcador do {@code restaurar.ps1} presente OU estado {@code pendente}/{@code executando}. O marcador
 * conta desde a primeira requisicao porque o banco restaurado nao sabe que foi restaurado: antes de o marcador virar
 * estado no banco, nada pode vender. A leitura do banco tem cache de 1 s; toda escrita daqui invalida o cache.
 */
@Component
@Profile("loja")
public class EstadoRecuperacao {

    public static final String NENHUMA = "nenhuma";
    public static final String PENDENTE = "pendente";
    public static final String EXECUTANDO = "executando";
    public static final String CONCLUIDA = "concluida";

    /** Fases na ordem; uma recuperacao interrompida retoma da fase gravada. */
    public static final String PREPARANDO = "preparando";
    public static final String PUXANDO = "puxando";
    public static final String ALINHANDO = "alinhando";
    public static final String SEQUENCIAS = "sequencias";

    private static final Logger LOG = LoggerFactory.getLogger(EstadoRecuperacao.class);
    private static final long VALIDADE_CACHE_MS = 1_000;

    /** Retrato do estado, como a situacao o mostra. */
    public record Foto(String estado, String fase, String motivo, OffsetDateTime solicitadaEm, OffsetDateTime iniciadaEm,
                       OffsetDateTime concluidaEm, int tentativas, String ultimoErro, Long versaoAntiga, Long cargaVersao,
                       boolean reenviosEnfileirados, String tabelaAtual, long linhas) {
        public boolean emAndamento() { return PENDENTE.equals(estado) || EXECUTANDO.equals(estado); }
    }

    private final JdbcTemplate jdbc;
    private final QuackProperties props;
    private final Clock clock;
    private volatile boolean bancoEmAndamento;
    private volatile long leituraEm;

    public EstadoRecuperacao(JdbcTemplate jdbc, QuackProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** O servidor esta se recuperando: nada de vendas, envio nem conferencia. */
    public boolean emAndamento() {
        if (marcadorPresente()) return true;
        long agora = System.currentTimeMillis();
        if (agora - leituraEm > VALIDADE_CACHE_MS) {
            try {
                Integer n = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM dbo.SyncRecuperacao WHERE Id = 1 AND Estado IN (N'pendente', N'executando')",
                        Integer.class);
                bancoEmAndamento = n != null && n > 0;
                leituraEm = agora;
            } catch (RuntimeException e) {
                // banco indisponivel (restauracao em curso): por seguranca, bloqueia
                LOG.warn("Nao foi possivel ler o estado da recuperacao: {}", e.getMessage());
                return true;
            }
        }
        return bancoEmAndamento;
    }

    public boolean marcadorPresente() {
        String caminho = props.getSync().getRecuperacaoMarcador();
        return !caminho.isBlank() && Files.exists(Path.of(caminho));
    }

    /**
     * Se o {@code restaurar.ps1} deixou o marcador, grava o pedido no banco e apaga o arquivo. Nesta ordem: se a API cair
     * entre uma coisa e outra, o marcador continua e o pedido se repete (idempotente).
     */
    public boolean consumirMarcador() {
        if (!marcadorPresente()) return false;
        solicitar("restauracao");
        try {
            Files.deleteIfExists(Path.of(props.getSync().getRecuperacaoMarcador()));
        } catch (IOException e) {
            LOG.warn("Nao foi possivel apagar o marcador da recuperacao: {}", e.getMessage());
        }
        return true;
    }

    /** Pede a recuperacao. Idempotente: se ja esta pendente ou executando, nao muda nada. */
    public void solicitar(String motivo) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        int n = jdbc.update("""
                UPDATE dbo.SyncRecuperacao SET Estado = N'pendente', Fase = N'preparando', Motivo = ?, SolicitadaEm = ?,
                       IniciadaEm = NULL, ConcluidaEm = NULL, Tentativas = 0, UltimoErro = NULL, VersaoAntiga = NULL,
                       CargaVersao = NULL, ReenviosEnfileirados = 0, RetratoCompleto = 0, TabelaAtual = NULL, Linhas = 0, AtualizadoEm = ?
                 WHERE Id = 1 AND Estado NOT IN (N'pendente', N'executando')
                """, limitar(motivo, 200), agora, agora);
        if (n > 0) LOG.warn("Recuperacao do servidor da loja solicitada: {}.", motivo);
        invalidar();
    }

    public Foto foto() {
        List<Foto> l = jdbc.query("""
                SELECT Estado, Fase, Motivo, SolicitadaEm, IniciadaEm, ConcluidaEm, Tentativas, UltimoErro, VersaoAntiga,
                       CargaVersao, ReenviosEnfileirados, TabelaAtual, Linhas
                  FROM dbo.SyncRecuperacao WHERE Id = 1
                """, (rs, i) -> new Foto(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class), rs.getObject(5, OffsetDateTime.class),
                rs.getObject(6, OffsetDateTime.class), rs.getInt(7), rs.getString(8), (Long) rs.getObject(9),
                (Long) rs.getObject(10), rs.getBoolean(11), rs.getString(12), rs.getLong(13)));
        return l.isEmpty() ? new Foto(NENHUMA, null, null, null, null, null, 0, null, null, null, false, null, 0) : l.getFirst();
    }

    // ------------------------------------------------------------------ escritas da propria recuperacao

    void executando() {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        jdbc.update("UPDATE dbo.SyncRecuperacao SET Estado = N'executando', IniciadaEm = COALESCE(IniciadaEm, ?), "
                + "Tentativas = Tentativas + 1, AtualizadoEm = ? WHERE Id = 1", agora, agora);
        invalidar();
    }

    void fase(String fase) {
        jdbc.update("UPDATE dbo.SyncRecuperacao SET Fase = ?, AtualizadoEm = SYSDATETIMEOFFSET() WHERE Id = 1", fase);
    }

    void falhou(String erro) {
        jdbc.update("UPDATE dbo.SyncRecuperacao SET Estado = N'pendente', UltimoErro = ?, AtualizadoEm = SYSDATETIMEOFFSET() "
                + "WHERE Id = 1", limitar(erro, 480));
        invalidar();
    }

    void concluida(String nota) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        jdbc.update("UPDATE dbo.SyncRecuperacao SET Estado = N'concluida', Fase = NULL, ConcluidaEm = ?, UltimoErro = ?, "
                + "TabelaAtual = NULL, AtualizadoEm = ? WHERE Id = 1", agora, limitar(nota, 480), agora);
        invalidar();
    }

    private void invalidar() { leituraEm = 0; }

    static String limitar(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
