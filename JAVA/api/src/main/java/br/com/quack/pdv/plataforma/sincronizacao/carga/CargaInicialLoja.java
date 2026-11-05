package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.tracking.ChangeTrackingAdmin;
import br.com.quack.pdv.security.SecretProtector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Carga inicial da loja a partir da nuvem e ativacao (D5). Roda uma vez, antes da loja operar, num banco sem uso:
 * <ol>
 *   <li>registra o servidor (ou reaproveita o registro ainda nao ativado, guardado em {@code SyncEstado});</li>
 *   <li>tabela a tabela, na ordem do catalogo, pagina a pagina; cada pagina e o progresso {@code SyncCarga} vao na
 *       mesma transacao, entao uma queda no meio recomeca da ultima pagina gravada, sem duplicar;</li>
 *   <li>delta ({@code CHANGETABLE} da nuvem desde a versao do retrato) repetido ate vir vazio;</li>
 *   <li>liga o Change Tracking e a nuvem ativa o servidor ({@code POST ativar}); so entao {@code SyncEstado.Ativada = 1},
 *       com {@code VersaoDe} = versao atual do tracking da loja.</li>
 * </ol>
 * Acionamento: linha de comando ({@link CargaInicialRunner}), chamada pelo instalador (grupo 7). Nao ha rota HTTP: o
 * banco da loja esta vazio e ainda nao tem administrador para autenticar.
 */
@Service
@Profile("loja")
public class CargaInicialLoja {

    private static final Logger LOG = LoggerFactory.getLogger(CargaInicialLoja.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final int LIMITE_PAGINA = 500;
    private static final int MAX_TENTATIVAS_ATIVACAO = 20;

    /** Dados de uma execucao. A senha vem do administrador da nuvem e so e usada no registro. */
    public record Parametros(String nuvemUrl, String email, String senha, String nomeServidor) {}

    public record Resultado(String serverId, String companyId, long linhas, long versaoLoja) {}

    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final ClienteCarga nuvem;
    private final AplicadorCargaLoja aplicador;
    private final ChangeTrackingAdmin tracking;
    private final SecretProtector segredos;
    private volatile int limitePagina = LIMITE_PAGINA;
    private volatile Consumer<CargaPagina> aposPagina = p -> { };

    public CargaInicialLoja(JdbcTemplate jdbc, TransactionOperations tx, ClienteCarga nuvem, AplicadorCargaLoja aplicador,
                            ChangeTrackingAdmin tracking, SecretProtector segredos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.nuvem = nuvem;
        this.aplicador = aplicador;
        this.tracking = tracking;
        this.segredos = segredos;
    }

    /** Tamanho da pagina (os testes usam valores pequenos para ter varias paginas). */
    public void definirLimitePagina(int limite) {
        this.limitePagina = Math.max(1, limite);
    }

    /** Gancho chamado depois de cada pagina gravada (os testes simulam a queda lancando excecao). */
    public void aoGravarPagina(Consumer<CargaPagina> gancho) {
        this.aposPagina = gancho == null ? p -> { } : gancho;
    }

    public Resultado executar(Parametros p) {
        if (ativada()) {
            throw new IllegalStateException("Esta loja ja foi ativada; a carga inicial so roda num banco novo.");
        }
        String url = p.nuvemUrl().replaceAll("/+$", "");
        ClienteCarga.Registro registro = registro(url, p);
        String token = segredos.unprotect(jdbc.queryForObject("SELECT TokenCifrado FROM SyncEstado WHERE Id = 1", String.class));

        boolean nova = jdbc.queryForObject("SELECT COUNT(*) FROM SyncCarga", Integer.class) == 0;
        if (nova) {
            LOG.info("Carga inicial: banco novo, limpando os dados de referencia.");
            aplicador.esvaziarTabelas();
        }
        aplicador.suspenderChavesAutorreferentes();

        long linhas = 0;
        List<TabelaSincronizada> ordem = new java.util.ArrayList<>(ReferenciasGlobais.tabelas());
        ordem.addAll(CatalogoSincronizacao.ordemDeAplicacao());
        for (TabelaSincronizada t : ordem) {
            linhas += carregarTabela(url, token, t);
        }
        long desde = delta(url, token, jdbc.queryForObject("SELECT CargaVersao FROM SyncEstado WHERE Id = 1", Long.class), false);
        long versaoLoja = ativar(url, token, desde, false);
        aplicador.restaurarChavesAutorreferentes();
        LOG.info("Carga inicial concluida: {} linhas, servidor {} ativo, versao da loja {}.", linhas, registro.serverId(), versaoLoja);
        return new Resultado(registro.serverId(), registro.companyId(), linhas, versaoLoja);
    }

    // ------------------------------------------------------------------ registro

    private ClienteCarga.Registro registro(String url, Parametros p) {
        Map<String, Object> estado = jdbc.queryForMap("SELECT ServidorId, CompanyId, TokenCifrado FROM SyncEstado WHERE Id = 1");
        String token = (String) estado.get("TokenCifrado");
        if (token != null && !token.isBlank() && estado.get("ServidorId") != null && estado.get("CompanyId") != null) {
            LOG.info("Carga inicial: retomando com o servidor {} ja registrado.", estado.get("ServidorId"));
            return new ClienteCarga.Registro((String) estado.get("ServidorId"), (String) estado.get("CompanyId"), null);
        }
        ClienteCarga.Registro r = nuvem.registrar(url, p.email(), p.senha(), p.nomeServidor());
        jdbc.update("UPDATE SyncEstado SET CompanyId = ?, ServidorId = ?, NuvemUrl = ?, TokenCifrado = ?, "
                + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE Id = 1", r.companyId(), r.serverId(), url,
                segredos.protect(r.token()));
        return r;
    }

    // ------------------------------------------------------------------ tabelas

    private long carregarTabela(String url, String token, TabelaSincronizada t) {
        List<Map<String, Object>> progresso = jdbc.queryForList(
                "SELECT UltimaChave, Concluida FROM SyncCarga WHERE Tabela = ?", t.nome());
        List<String> cursor = null;
        if (!progresso.isEmpty()) {
            if (Boolean.TRUE.equals(progresso.getFirst().get("Concluida"))) return 0;
            cursor = lerCursor((String) progresso.getFirst().get("UltimaChave"));
        }
        long total = 0;
        while (true) {
            CargaPagina pagina = nuvem.pagina(url, token, t.nome(), cursor, limitePagina);
            final List<String> chaveAnterior = cursor;
            int n = tx.execute(s -> {
                int gravadas = aplicador.aplicarLinhas(t.nome(), pagina.colunas(), pagina.linhas());
                gravarProgresso(t.nome(), pagina.ultimaChave() != null ? pagina.ultimaChave() : chaveAnterior, gravadas,
                        pagina.fim());
                jdbc.update("UPDATE SyncEstado SET CargaVersao = ? WHERE Id = 1 AND CargaVersao IS NULL", pagina.versao());
                return gravadas;
            });
            total += n;
            aposPagina.accept(pagina);
            if (pagina.fim()) return total;
            cursor = pagina.ultimaChave();
        }
    }

    private void gravarProgresso(String tabela, List<String> ultimaChave, int linhas, boolean concluida) {
        String json = ultimaChave == null ? null : escrever(ultimaChave);
        int n = jdbc.update("UPDATE SyncCarga SET UltimaChave = ?, Linhas = Linhas + ?, Concluida = ?, "
                + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE Tabela = ?", json, linhas, concluida, tabela);
        if (n == 0) {
            jdbc.update("INSERT INTO SyncCarga (Tabela, UltimaChave, Linhas, Concluida) VALUES (?, ?, ?, ?)", tabela, json,
                    linhas, concluida);
        }
    }

    // ------------------------------------------------------------------ delta e ativacao

    /** Aplica o delta ate vir vazio e devolve a versao da nuvem ate onde foi aplicado. */
    public long delta(String url, String token, long desde, boolean recuperacao) {
        long atual = desde;
        while (true) {
            CargaDelta d = nuvem.delta(url, token, atual);
            if (d.vazio()) return d.ate();
            tx.executeWithoutResult(s -> aplicador.aplicarDelta(d.blocos(), recuperacao));
            LOG.info("Carga inicial: delta aplicado ({} tabelas).", d.blocos().size());
            atual = d.ate();
        }
    }

    /**
     * Liga o tracking e pede a ativacao. Se a nuvem mudou desde o ultimo delta (409), aplica o delta e tenta de novo;
     * o que o delta grava ja com o tracking ligado nao sobe, pois {@code VersaoDe} e lida depois dele e a sincronizacao
     * so comeca com {@code Ativada = 1}.
     */
    public long ativar(String url, String token, long desde, boolean recuperacao) {
        long ate = desde;
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_ATIVACAO; tentativa++) {
            tracking.ligarTudo(ChangeTrackingAdmin.RETENCAO_LOJA_DIAS);
            Long versao = jdbc.queryForObject("SELECT CHANGE_TRACKING_CURRENT_VERSION()", Long.class);
            long v = versao == null ? 0 : versao;
            try {
                nuvem.ativar(url, token, ate, v, recuperacao);
                jdbc.update("UPDATE SyncEstado SET Ativada = 1, AtivadaEm = SYSDATETIMEOFFSET(), VersaoDe = ?, "
                        + "AplicadoAteNuvem = ?, TentativasSeguidas = 0, ProximaTentativaEm = NULL, UltimoErro = NULL, "
                        + "PausadaEm = NULL, PausadaMotivo = NULL, AtualizadoEm = SYSDATETIMEOFFSET() WHERE Id = 1", v, v);
                return v;
            } catch (ClienteCarga.CargaException e) {
                if (e.status() != 409) throw e;
                LOG.info("Carga inicial: a nuvem mudou antes da ativacao (tentativa {}); repetindo o delta.", tentativa);
                ate = delta(url, token, ate, recuperacao);
            }
        }
        throw new IllegalStateException("A nuvem nao parou de mudar: nao foi possivel ativar. Tente de novo em instantes.");
    }

    // ------------------------------------------------------------------ apoio

    private boolean ativada() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM SyncEstado WHERE Id = 1 AND Ativada = 1", Integer.class);
        return n != null && n > 0;
    }

    private static List<String> lerCursor(String json) {
        if (json == null) return null;
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {});
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Progresso da carga corrompido: " + e.getMessage(), e);
        }
    }

    private static String escrever(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
