package br.com.quack.pdv.plataforma.sincronizacao.recuperacao;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.carga.AplicadorCargaLoja;
import br.com.quack.pdv.plataforma.sincronizacao.carga.CargaInicialLoja;
import br.com.quack.pdv.plataforma.sincronizacao.carga.CargaPagina;
import br.com.quack.pdv.plataforma.sincronizacao.carga.ClienteCarga;
import br.com.quack.pdv.plataforma.sincronizacao.carga.ReferenciasGlobais;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.security.SecretProtector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Recuperacao do servidor da loja depois de restaurar um backup (tarefa 7.4). O banco restaurado e mais antigo que a
 * nuvem: as vendas, notas, movimentos e cadastros feitos depois do backup so existem la, e as versoes do Change Tracking
 * voltaram atras. Como a loja e a autoridade, a conferencia apagaria esses dados da nuvem. Por isso, antes de voltar ao
 * normal, o servidor puxa tudo o que falta e realinha as versoes. Sem apagar nada, nem na loja nem na nuvem.
 *
 * <ol>
 *   <li><b>preparando</b>: anota o que a loja ainda nao tinha enviado quando o backup foi feito (linhas mudadas desde o
 *       {@code VersaoDe} restaurado) em {@code SyncReenvios}: se existem so no backup, sobem depois;</li>
 *   <li><b>puxando</b>: tabela a tabela, na ordem do catalogo, as linhas da empresa na nuvem entram por upsert (a nuvem
 *       vence: e o valor mais recente que a loja enviou), sem tocar colunas LOCAL, com contadores de numeracao que so
 *       crescem e AuditLog sem repetir; nada e excluido;</li>
 *   <li><b>alinhando</b>: delta da nuvem ate vir vazio e reativacao ({@code POST ativar}) como na carga inicial: a nuvem
 *       refaz o espelho no mesmo retrato e passa a esperar {@code VersaoDe = versao atual da loja}. Corrige o
 *       {@code aplicadoAte} (nada de 409 nem de lote "ja aplicado" que pula mudancas);</li>
 *   <li><b>sequencias</b>: a sequencia fiscal passa do maior numero de nota que a loja ja tem. A numeracao de vendas,
 *       pedidos, ordens de compra e inventarios usa {@code MAX + 1}, entao ja acompanha as linhas puxadas.</li>
 * </ol>
 * Durante tudo isso o servidor nao aceita vendas ({@code RecuperacaoFilter}) e o envio, a conferencia, os comandos e a
 * fila fiscal ficam parados. Cada fase e cada pagina sao gravadas: uma queda, ou a falta de internet, so interrompe, e a
 * rotina retoma de onde parou. Terminando, o envio volta com a conferencia sozinha.
 */
@Service
@Profile("loja")
public class RecuperacaoLoja {

    private static final Logger LOG = LoggerFactory.getLogger(RecuperacaoLoja.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMITE_PAGINA = 500;

    public enum Resultado { NADA_A_FAZER, CONCLUIDA, SEM_INTERNET, FALHOU }

    private record Conexao(String url, String token, String companyId) {}

    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final EstadoRecuperacao estado;
    private final ClienteCarga nuvem;
    private final CargaInicialLoja carga;
    private final AplicadorCargaLoja aplicador;
    private final SecretProtector segredos;
    private final QuackProperties props;
    private final br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos numerador;
    private volatile int limitePagina = LIMITE_PAGINA;
    private volatile java.util.function.Consumer<CargaPagina> aposPagina = p -> { };

    public RecuperacaoLoja(JdbcTemplate jdbc, TransactionOperations tx, EstadoRecuperacao estado, ClienteCarga nuvem,
                           CargaInicialLoja carga, AplicadorCargaLoja aplicador, SecretProtector segredos,
                           QuackProperties props, br.com.quack.pdv.plataforma.numeracao.NumeradorDocumentos numerador) {
        this.numerador = numerador;
        this.jdbc = jdbc;
        this.tx = tx;
        this.estado = estado;
        this.nuvem = nuvem;
        this.carga = carga;
        this.aplicador = aplicador;
        this.segredos = segredos;
        this.props = props;
    }

    /** Tamanho da pagina (os testes usam valores pequenos para ter varias paginas). */
    public void definirLimitePagina(int limite) { this.limitePagina = Math.max(1, limite); }

    /** Gancho depois de cada pagina gravada (os testes simulam a queda lancando excecao). */
    public void aoGravarPagina(java.util.function.Consumer<CargaPagina> gancho) {
        this.aposPagina = gancho == null ? p -> { } : gancho;
    }

    /** Uma passada. Repetivel: sem internet fica pendente e a proxima passada retoma. */
    public synchronized Resultado executar() {
        estado.consumirMarcador();
        EstadoRecuperacao.Foto f = estado.foto();
        if (!f.emAndamento()) return Resultado.NADA_A_FAZER;
        List<Map<String, Object>> se = jdbc.queryForList(
                "SELECT Ativada, CompanyId, NuvemUrl, TokenCifrado FROM dbo.SyncEstado WHERE Id = 1");
        Map<String, Object> e = se.getFirst();
        if (!Boolean.TRUE.equals(e.get("Ativada")) || e.get("TokenCifrado") == null) {
            estado.concluida("A loja restaurada ainda nao tinha sido ativada: rode a carga inicial.");
            LOG.warn("Recuperacao dispensada: o banco restaurado nao estava ativado. Rode a carga inicial.");
            return Resultado.CONCLUIDA;
        }
        String url = e.get("NuvemUrl") == null || e.get("NuvemUrl").toString().isBlank()
                ? props.getSync().getNuvemUrl() : e.get("NuvemUrl").toString();
        Conexao c = new Conexao(url.replaceAll("/+$", ""), segredos.unprotect((String) e.get("TokenCifrado")),
                (String) e.get("CompanyId"));
        try {
            estado.executando();
            String fase = f.fase() == null ? EstadoRecuperacao.PREPARANDO : f.fase();
            if (fase.equals(EstadoRecuperacao.PREPARANDO)) {
                preparar(c);
                fase = EstadoRecuperacao.PUXANDO;
            }
            if (fase.equals(EstadoRecuperacao.PUXANDO)) {
                puxar(c);
                estado.fase(EstadoRecuperacao.ALINHANDO);
                fase = EstadoRecuperacao.ALINHANDO;
            }
            if (fase.equals(EstadoRecuperacao.ALINHANDO)) {
                alinhar(c);
                estado.fase(EstadoRecuperacao.SEQUENCIAS);
            }
            avancarSequencias();
            estado.concluida(null);
            LOG.warn("Recuperacao do servidor da loja concluida: vendas liberadas, envio e conferencia religados.");
            return Resultado.CONCLUIDA;
        } catch (ClienteCarga.CargaException ex) {
            if (ex.status() == 410) { // o retrato da nuvem passou da retencao: recomeca a puxada
                LOG.warn("Recuperacao: {} Recomecando a puxada.", ex.getMessage());
                reiniciarPuxada();
                estado.falhou(ex.getMessage());
                return Resultado.FALHOU;
            }
            estado.falhou(ex.getMessage());
            LOG.warn("Recuperacao pendente: {}", ex.getMessage());
            return ex.status() == 0 || ex.status() >= 500 ? Resultado.SEM_INTERNET : Resultado.FALHOU;
        } catch (RuntimeException ex) {
            LOG.error("Recuperacao falhou (sera repetida): {}", ex.getMessage(), ex);
            estado.falhou(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return Resultado.FALHOU;
        }
    }

    // ------------------------------------------------------------------ preparando

    private void preparar(Conexao conexao) {
        Long versaoDe = jdbc.queryForObject("SELECT VersaoDe FROM dbo.SyncEstado WHERE Id = 1", Long.class);
        long confirmada = nuvem.versaoConfirmada(conexao.url(), conexao.token());
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM dbo.SyncRecuperacaoTabelas");
            jdbc.update("UPDATE dbo.SyncRecuperacao SET VersaoAntiga = ?, CargaVersao = NULL, Linhas = 0 WHERE Id = 1", versaoDe);
        });
        boolean ok = versaoDe != null && enfileirarPendentes(versaoDe, confirmada);
        jdbc.update("UPDATE dbo.SyncRecuperacao SET ReenviosEnfileirados = 1, RetratoCompleto = ? WHERE Id = 1", !ok);
        if (!ok) LOG.warn("Recuperacao: Change Tracking do banco restaurado inutilizavel; sera enviado o retrato completo.");
        estado.fase(EstadoRecuperacao.PUXANDO);
    }

    /**
     * Linhas mudadas na loja depois da ultima versao que a NUVEM confirmou ({@code confirmada}), no banco restaurado: a nuvem
     * nao as viu, entao existem so aqui. (As mudadas desde a ultima versao enviada, mas ate {@code confirmada}, a nuvem ja
     * tem e vale o valor dela.) Entram em SyncReenvios, sobem no primeiro lote depois da recuperacao e, na puxada, mantem
     * as colunas LOJA do backup. Devolve falso se o tracking nao serve.
     */
    private boolean enfileirarPendentes(long de, long confirmada) {
        Map<String, List<String>> pks = new EsquemaJdbc(jdbc).chavesPrimarias();
        try {
            tx.executeWithoutResult(s -> {
                for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
                    List<String> pk = pks.get(t.nome());
                    String chave = pk.size() == 1 ? "CONVERT(NVARCHAR(200), CT.[" + pk.getFirst() + "])"
                            : "CONCAT_WS(N'|', " + String.join(", ", pk.stream()
                                    .map(c -> "CONVERT(NVARCHAR(100), CT.[" + c + "])").toList()) + ")";
                    jdbc.update("INSERT INTO dbo.SyncReenvios (Tabela, Chave, Motivo) SELECT ?, " + chave + ", N'recuperacao' "
                            + "FROM CHANGETABLE(CHANGES dbo.[" + t.nome() + "], ?) AS CT JOIN dbo.[" + t.nome() + "] AS T ON "
                            + String.join(" AND ", pk.stream().map(c -> "T.[" + c + "] = CT.[" + c + "]").toList())
                            + " WHERE CT.SYS_CHANGE_VERSION > ? AND NOT EXISTS (SELECT 1 FROM dbo.SyncReenvios R WHERE R.Tabela = ? AND R.Chave = " + chave + ")",
                            t.nome(), de, confirmada, t.nome());
                }
            });
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Recuperacao: nao foi possivel ler o Change Tracking desde {}: {}", de, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ puxando

    private void puxar(Conexao c) {
        carregarProtegidas();
        aplicador.suspenderChavesAutorreferentes();
        List<TabelaSincronizada> ordem = new ArrayList<>(ReferenciasGlobais.tabelas());
        ordem.addAll(CatalogoSincronizacao.ordemDeAplicacao());
        for (TabelaSincronizada t : ordem) puxarTabela(c, t);
    }

    private void carregarProtegidas() {
        aplicador.definirProtegidas(new java.util.HashSet<>(jdbc.query(
                "SELECT Tabela + N'|' + Chave FROM dbo.SyncReenvios WHERE Motivo = N'recuperacao'", (rs, i) -> rs.getString(1))));
    }

    private void puxarTabela(Conexao c, TabelaSincronizada t) {
        List<Map<String, Object>> progresso = jdbc.queryForList(
                "SELECT UltimaChave, Concluida FROM dbo.SyncRecuperacaoTabelas WHERE Tabela = ?", t.nome());
        List<String> cursor = null;
        if (!progresso.isEmpty()) {
            if (Boolean.TRUE.equals(progresso.getFirst().get("Concluida"))) return;
            cursor = lerCursor((String) progresso.getFirst().get("UltimaChave"));
        }
        jdbc.update("UPDATE dbo.SyncRecuperacao SET TabelaAtual = ? WHERE Id = 1", t.nome());
        while (true) {
            boolean primeira = jdbc.queryForObject("SELECT CargaVersao FROM dbo.SyncRecuperacao WHERE Id = 1", Long.class) == null;
            CargaPagina pagina = nuvem.pagina(c.url(), c.token(), t.nome(), cursor, limitePagina, primeira);
            final List<String> anterior = cursor;
            tx.executeWithoutResult(s -> {
                int n = aplicador.aplicarLinhas(t.nome(), pagina.colunas(), pagina.linhas(), true);
                gravarProgresso(t.nome(), pagina.ultimaChave() != null ? pagina.ultimaChave() : anterior, n, pagina.fim());
                jdbc.update("UPDATE dbo.SyncRecuperacao SET CargaVersao = ? WHERE Id = 1 AND CargaVersao IS NULL", pagina.versao());
                jdbc.update("UPDATE dbo.SyncRecuperacao SET Linhas = Linhas + ?, AtualizadoEm = SYSDATETIMEOFFSET() WHERE Id = 1", n);
            });
            aposPagina.accept(pagina);
            if (pagina.fim()) return;
            cursor = pagina.ultimaChave();
        }
    }

    private void gravarProgresso(String tabela, List<String> ultimaChave, int linhas, boolean concluida) {
        String json = ultimaChave == null ? null : escrever(ultimaChave);
        int n = jdbc.update("UPDATE dbo.SyncRecuperacaoTabelas SET UltimaChave = ?, Linhas = Linhas + ?, Concluida = ?, "
                + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE Tabela = ?", json, linhas, concluida, tabela);
        if (n == 0) {
            jdbc.update("INSERT INTO dbo.SyncRecuperacaoTabelas (Tabela, UltimaChave, Linhas, Concluida) VALUES (?, ?, ?, ?)",
                    tabela, json, linhas, concluida);
        }
    }

    private void reiniciarPuxada() {
        tx.executeWithoutResult(s -> {
            jdbc.update("DELETE FROM dbo.SyncRecuperacaoTabelas");
            jdbc.update("UPDATE dbo.SyncRecuperacao SET CargaVersao = NULL, Fase = N'puxando' WHERE Id = 1");
        });
    }

    // ------------------------------------------------------------------ alinhando

    /** Delta ate vir vazio e reativacao: a nuvem passa a esperar a versao atual da loja (tracking religado, se preciso). */
    private void alinhar(Conexao c) {
        Long v0 = jdbc.queryForObject("SELECT CargaVersao FROM dbo.SyncRecuperacao WHERE Id = 1", Long.class);
        if (v0 == null) throw new IllegalStateException("Recuperacao sem versao de retrato; a puxada sera refeita.");
        long desde = carga.delta(c.url(), c.token(), v0, true);
        carga.ativar(c.url(), c.token(), desde, true);
        aplicador.restaurarChavesAutorreferentes();
    }

    // ------------------------------------------------------------------ sequencias

    /**
     * A sequencia fiscal da loja nunca fica abaixo do maior numero de NFC-e que a loja tem (nem inventa linha com 1, o
     * que o numerador faria se ela faltasse). Roda depois da reativacao: o que muda aqui sobe no primeiro lote. Se o
     * Change Tracking estava inutilizavel, {@code VersaoDe = -1} faz o envio mandar o retrato completo.
     */
    private void avancarSequencias() {
        tx.executeWithoutResult(s -> {
            int ajustadas = numerador.alinharSequenciaFiscalComDocumentos();
            if (ajustadas > 0) LOG.warn("Recuperacao: sequencia fiscal ajustada em {} linha(s).", ajustadas);
            Boolean completo = jdbc.queryForObject("SELECT RetratoCompleto FROM dbo.SyncRecuperacao WHERE Id = 1", Boolean.class);
            if (Boolean.TRUE.equals(completo)) jdbc.update("UPDATE dbo.SyncEstado SET VersaoDe = -1 WHERE Id = 1");
        });
    }

    // ------------------------------------------------------------------ apoio

    private static List<String> lerCursor(String json) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Progresso da recuperacao corrompido: " + e.getMessage(), e);
        }
    }

    private static String escrever(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
