package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ConversorValor;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.LeitorLinhas;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Nucleo da deteccao de edicoes da nuvem (D6, D7): compara a linha viva da nuvem com o espelho (o que veio da loja) e
 * cria os comandos {@code alterar}, {@code inserir}, {@code excluir}, {@code forcar} e {@code reenviar}, ou grava a
 * quarentena. Todos os metodos recebem o {@link JdbcTemplate} da transacao em que devem rodar (a do detector periodico,
 * em SNAPSHOT, ou a do lote, na deteccao inline).
 */
@Component
@Profile("nuvem")
public class MotorDeteccao {

    private static final Logger LOG = LoggerFactory.getLogger(MotorDeteccao.class);
    static final ObjectMapper JSON = new ObjectMapper();
    /** "SQ" 0001 = {@code sync-loja}; mesmo valor de {@link AplicadorLotes#CONTEXTO_SYNC_LOJA}. */
    private static final byte[] CONTEXTO_SYNC_LOJA = {0x53, 0x51, 0x00, 0x01};
    public static final String ORIGEM_EDICAO = "edicao-nuvem";
    public static final String ORIGEM_RESSINCRONIZACAO = "ressincronizacao";
    private static final TypeReference<LinkedHashMap<String, String>> MAPA = new TypeReference<>() {};

    /** Uma linha mudada: chave (texto de cada coluna), operacao do tracking e a linha viva (nulo = excluida). */
    public record Mudanca(List<String> pk, String operacao, Map<String, String> linha) {}

    private record UltimaMudanca(Long versao, byte[] contexto) {}

    private final JdbcTemplate base;
    private volatile Map<String, Set<String>> colunasPorTabela;
    private volatile Map<String, List<String>> chavesPorTabela;
    private final Map<String, List<ColunaSync>> tiposDaChave = new ConcurrentHashMap<>();

    public MotorDeteccao(@org.springframework.context.annotation.Lazy JdbcTemplate base) {
        this.base = base;
    }

    // ------------------------------------------------------------------ esquema

    private void carregarEsquema() {
        if (colunasPorTabela != null) return;
        EsquemaJdbc esquema = new EsquemaJdbc(base);
        chavesPorTabela = esquema.chavesPrimarias();
        colunasPorTabela = esquema.colunas();
    }

    public List<String> chavePrimaria(TabelaSincronizada t) {
        carregarEsquema();
        return chavesPorTabela.get(t.nome());
    }

    public List<String> colunas(TabelaSincronizada t) {
        carregarEsquema();
        return List.copyOf(colunasPorTabela.get(t.nome()));
    }

    public static boolean ehContextoSyncLoja(byte[] contexto) {
        return Arrays.equals(contexto, CONTEXTO_SYNC_LOJA);
    }

    public static List<String> partesDaChave(String chave) {
        return Arrays.asList(chave.split("\\|", -1));
    }

    // ------------------------------------------------------------------ leitura

    private List<ColunaSync> tiposDaChave(JdbcTemplate j, TabelaSincronizada t) {
        return tiposDaChave.computeIfAbsent(t.nome(), n -> {
            List<String> pk = chavePrimaria(t);
            return j.query("SELECT TOP 0 " + String.join(", ", pk.stream().map(c -> "[" + c + "]").toList())
                    + " FROM dbo.[" + n + "]", rs -> {
                return LeitorLinhas.tipos(rs.getMetaData(), pk, 0);
            });
        });
    }

    private void ligarChave(PreparedStatement ps, int primeiro, TabelaSincronizada t, JdbcTemplate j, List<String> pk)
            throws SQLException {
        List<ColunaSync> tipos = tiposDaChave(j, t);
        for (int i = 0; i < pk.size(); i++) {
            ConversorValor.bind(ps, primeiro + i, ConversorValor.decodificar(pk.get(i), tipos.get(i).tipo()), tipos.get(i).tipo());
        }
    }

    private static String listaColunas(List<String> nomes, String prefixo) {
        return String.join(", ", nomes.stream().map(c -> prefixo + "[" + c + "]").toList());
    }

    private static Map<String, String> linhaTexto(ResultSet rs, List<String> cols, List<ColunaSync> tipos, int primeiro)
            throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> v = LeitorLinhas.valores(rs, tipos, primeiro);
        for (int i = 0; i < cols.size(); i++) m.put(cols.get(i), v.get(i));
        return m;
    }

    /** Linha viva por chave primaria (texto), ou vazio se nao existe. */
    public Optional<Map<String, String>> lerLinha(JdbcTemplate j, TabelaSincronizada t, List<String> pk) {
        List<String> cols = colunas(t);
        String sql = "SELECT " + listaColunas(cols, "") + " FROM dbo.[" + t.nome() + "] WHERE "
                + String.join(" AND ", chavePrimaria(t).stream().map(c -> "[" + c + "] = ?").toList());
        Map<String, String> r = j.query(sql, ps -> ligarChave(ps, 1, t, j, pk), rs -> {
            if (!rs.next()) return null;
            return linhaTexto(rs, cols, LeitorLinhas.tipos(rs.getMetaData(), cols, 0), 1);
        });
        return Optional.ofNullable(r);
    }

    private String juncaoMae(TabelaSincronizada t) {
        if (!t.filhaSemEmpresa()) return "";
        TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
        return " LEFT JOIN dbo.[" + mae.nome() + "] AS M ON M.[" + chavePrimaria(mae).getFirst() + "] = T.["
                + t.colunaLigacao() + "]";
    }

    private String empresaExpr(TabelaSincronizada t) {
        if (!t.filhaSemEmpresa()) return "T.[" + t.colunaEmpresa() + "]";
        return "M.[" + CatalogoSincronizacao.tabela(t.mae()).orElseThrow().colunaEmpresa() + "]";
    }

    /** Mudancas da empresa desde {@code desde}, ignorando o que o aplicador de lotes escreveu. */
    public List<Mudanca> lerMudancas(JdbcTemplate j, TabelaSincronizada t, String company, long desde) {
        List<String> pk = chavePrimaria(t);
        List<String> cols = colunas(t);
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < pk.size(); i++) sql.append("CT.[").append(pk.get(i)).append("] AS __k").append(i).append(", ");
        sql.append("CT.SYS_CHANGE_OPERATION AS __op, ").append(listaColunas(cols, "T."));
        sql.append(" FROM CHANGETABLE(CHANGES dbo.[").append(t.nome()).append("], ?) AS CT LEFT JOIN dbo.[")
                .append(t.nome()).append("] AS T ON ")
                .append(String.join(" AND ", pk.stream().map(c -> "T.[" + c + "] = CT.[" + c + "]").toList()))
                .append(juncaoMae(t))
                .append(" WHERE (CT.SYS_CHANGE_CONTEXT IS NULL OR CT.SYS_CHANGE_CONTEXT <> 0x53510001) AND (T.[")
                .append(pk.getFirst()).append("] IS NULL OR ").append(empresaExpr(t)).append(" = ?)")
                .append(" ORDER BY CT.SYS_CHANGE_VERSION");
        return j.query(sql.toString(), ps -> {
            ps.setLong(1, desde);
            ps.setString(2, company);
        }, rs -> {
            List<Mudanca> r = new ArrayList<>();
            if (!rs.isBeforeFirst()) return r;
            ResultSetMetaData md = rs.getMetaData();
            List<ColunaSync> tiposPk = LeitorLinhas.tipos(md, pk, 0);
            List<ColunaSync> tipos = LeitorLinhas.tipos(md, cols, pk.size() + 1);
            int posPk = cols.indexOf(pk.getFirst());
            while (rs.next()) {
                List<String> chave = LeitorLinhas.valores(rs, tiposPk, 1);
                String op = rs.getString(pk.size() + 1);
                Map<String, String> linha = rs.getObject(pk.size() + 1 + 1 + posPk) == null ? null
                        : linhaTexto(rs, cols, tipos, pk.size() + 2);
                r.add(new Mudanca(chave, op, linha));
            }
            return r;
        });
    }

    /** Todas as linhas vivas da empresa (deteccao completa). */
    public List<Mudanca> lerTodas(JdbcTemplate j, TabelaSincronizada t, String company) {
        List<String> pk = chavePrimaria(t);
        List<String> cols = colunas(t);
        String sql = "SELECT " + listaColunas(cols, "T.") + " FROM dbo.[" + t.nome() + "] AS T" + juncaoMae(t)
                + " WHERE " + empresaExpr(t) + " = ?";
        return j.query(sql, ps -> ps.setString(1, company), rs -> {
            List<Mudanca> r = new ArrayList<>();
            List<ColunaSync> tipos = LeitorLinhas.tipos(rs.getMetaData(), cols, 0);
            while (rs.next()) {
                Map<String, String> linha = linhaTexto(rs, cols, tipos, 1);
                r.add(new Mudanca(pk.stream().map(linha::get).toList(), "U", linha));
            }
            return r;
        });
    }

    private UltimaMudanca ultimaMudanca(JdbcTemplate j, TabelaSincronizada t, List<String> pk) {
        List<String> cols = chavePrimaria(t);
        String sql = "SELECT CT.SYS_CHANGE_VERSION, CT.SYS_CHANGE_CONTEXT FROM CHANGETABLE(VERSION dbo.[" + t.nome() + "], ("
                + listaColunas(cols, "") + "), (" + "?, ".repeat(cols.size() - 1) + "?)) AS CT";
        List<UltimaMudanca> r = j.query(sql, ps -> ligarChave(ps, 1, t, j, pk), (rs, i) -> {
            long v = rs.getLong(1);
            return new UltimaMudanca(rs.wasNull() ? null : v, rs.getBytes(2));
        });
        return r.isEmpty() ? null : r.getFirst();
    }

    // ------------------------------------------------------------------ espelho

    public Optional<Map<String, String>> lerEspelho(JdbcTemplate j, TabelaSincronizada t, String chave, String company) {
        List<String> r = j.queryForList("SELECT ColunasJson FROM dbo.SyncEspelho WHERE Tabela = ? AND Chave = ? AND CompanyId = ?",
                String.class, t.nome(), chave, company);
        if (r.isEmpty()) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(r.getFirst(), MAPA));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Espelho ilegivel em " + t.nome() + " " + chave, e);
        }
    }

    public void gravarEspelho(JdbcTemplate j, TabelaSincronizada t, String chave, String company, Map<String, String> colunas) {
        String json = escrever(colunas);
        j.update("""
                MERGE dbo.SyncEspelho AS e USING (SELECT ? AS Tabela, ? AS Chave) AS s ON e.Tabela = s.Tabela AND e.Chave = s.Chave
                WHEN MATCHED THEN UPDATE SET CompanyId = ?, ColunasJson = ?, AtualizadoEm = SYSDATETIMEOFFSET()
                WHEN NOT MATCHED THEN INSERT (Tabela, Chave, CompanyId, ColunasJson) VALUES (s.Tabela, s.Chave, ?, ?);
                """, t.nome(), chave, company, json, company, json);
    }

    public void apagarEspelho(JdbcTemplate j, TabelaSincronizada t, String chave) {
        j.update("DELETE FROM dbo.SyncEspelho WHERE Tabela = ? AND Chave = ?", t.nome(), chave);
    }

    /**
     * Colunas guardadas no espelho para uma linha: EDITAVEL, LOJA, NUVEM e CIFRADA (valor da nuvem), sem a chave e sem
     * LOCAL. Tabelas que so registram a origem (ex.: DocumentosFiscais modelo 65) guardam so a chave ({}).
     */
    public Map<String, String> espelhoDe(TabelaSincronizada t, Map<String, String> linha) {
        Map<String, String> r = new LinkedHashMap<>();
        Map<String, Object> obj = new LinkedHashMap<>(linha);
        if (t.classe() != ClasseTabela.EDITAVEL && !t.autoriaDaNuvem(obj)) return r;
        List<String> pk = chavePrimaria(t);
        for (Map.Entry<String, String> e : linha.entrySet()) {
            if (pk.contains(e.getKey())) continue;
            if (t.classeDaColuna(e.getKey(), obj) != ClasseColuna.LOCAL) r.put(e.getKey(), e.getValue());
        }
        return r;
    }

    // ------------------------------------------------------------------ inline

    /** Deteccao inline de uma linha que o lote vai escrever; devolve as colunas com comando pendente. */
    public Set<String> inline(JdbcTemplate j, String company, TabelaSincronizada t, String chave, boolean existe) {
        return inline(j, company, t, chave, existe, false);
    }

    /**
     * Como {@link #inline(JdbcTemplate, String, TabelaSincronizada, String, boolean)}; em {@code ressincronizacao} (retrato
     * completo, D5) a linha da nuvem que o retrato vai sobrescrever e guardada na quarentena com origem
     * {@code ressincronizacao} (D7), nao {@code edicao-nuvem}.
     */
    public Set<String> inline(JdbcTemplate j, String company, TabelaSincronizada t, String chave, boolean existe,
                              boolean ressincronizacao) {
        if (!existe || t.classe() == ClasseTabela.ACRESCIMO) return Set.of();
        List<Long> det = j.query("SELECT DetectorDesde FROM dbo.SyncEstadoNuvem WHERE CompanyId = ?",
                (rs, i) -> { long v = rs.getLong(1); return rs.wasNull() ? null : v; }, company);
        List<String> pk = partesDaChave(chave);
        if (!det.isEmpty() && det.getFirst() != null) {
            UltimaMudanca u = ultimaMudanca(j, t, pk);
            if (u != null && u.versao() != null && u.versao() > det.getFirst() && !ehContextoSyncLoja(u.contexto())) {
                lerLinha(j, t, pk).ifPresent(live -> processarLinha(j, company, t, "U", pk, live, true,
                        ressincronizacao ? ORIGEM_RESSINCRONIZACAO : ORIGEM_EDICAO));
            }
        }
        return colunasPendentes(j, company, t, chave);
    }

    public Set<String> colunasPendentes(JdbcTemplate j, String company, TabelaSincronizada t, String chave) {
        List<String> payloads = j.queryForList("""
                SELECT PayloadJson FROM dbo.SyncComandos
                 WHERE CompanyId = ? AND Tabela = ? AND Chave = ? AND Status = N'pendente' AND Tipo IN (N'alterar', N'forcar')
                """, String.class, company, t.nome(), chave);
        Set<String> r = new HashSet<>();
        for (String p : payloads) {
            try {
                for (JsonNode c : JSON.readTree(p).path("colunas")) r.add(c.path("nome").asText());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Payload de comando ilegivel.", e);
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ classificacao de uma linha mudada

    /**
     * Decide o que fazer com uma linha da empresa alterada na nuvem fora do aplicador. {@code operacao}: I, U ou D.
     * {@code live} e nulo se a linha foi excluida. {@code inline}: a linha esta no lote que esta sendo aplicado, que ja a
     * traz de volta, entao nao cria {@code reenviar}.
     */
    public void processarLinha(JdbcTemplate j, String company, TabelaSincronizada t, String operacao, List<String> pk,
                               Map<String, String> live, boolean inline) {
        processarLinha(j, company, t, operacao, pk, live, inline, ORIGEM_EDICAO);
    }

    void processarLinha(JdbcTemplate j, String company, TabelaSincronizada t, String operacao, List<String> pk,
                        Map<String, String> live, boolean inline, String origem) {
        String chave = String.join("|", pk);
        Optional<Map<String, String>> esp = t.usaEspelho() ? lerEspelho(j, t, chave, company) : Optional.empty();
        if (live == null) {
            excluida(j, company, t, chave, esp.orElse(null));
            return;
        }
        Map<String, Object> obj = new LinkedHashMap<>(live);
        if (t.classe() == ClasseTabela.EDITAVEL || t.autoriaDaNuvem(obj)) {
            editavel(j, company, t, chave, live, obj, esp.orElse(null), inline, origem);
        } else {
            tabelaDaLoja(j, company, t, chave, operacao, live, obj, esp.isPresent(), inline, origem);
        }
    }

    private void excluida(JdbcTemplate j, String company, TabelaSincronizada t, String chave, Map<String, String> esp) {
        if (esp == null) return; // empresa desconhecida: a conferencia (grupo 6) corrige exclusao em tabela LOJA
        if (t.classe() == ClasseTabela.EDITAVEL) {
            ObjectNode p = JSON.createObjectNode();
            ArrayNode cols = p.putArray("colunas");
            for (Map.Entry<String, String> e : esp.entrySet()) {
                if (t.classeDaColuna(e.getKey(), null) == ClasseColuna.EDITAVEL) {
                    cols.addObject().put("nome", e.getKey()).put("base", e.getValue());
                }
            }
            criarComando(j, company, "excluir", t, chave, p);
        }
        apagarEspelho(j, t, chave);
    }

    private void editavel(JdbcTemplate j, String company, TabelaSincronizada t, String chave, Map<String, String> live,
                          Map<String, Object> obj, Map<String, String> esp, boolean inline, String origem) {
        if (esp == null) { // linha criada na nuvem
            ObjectNode p = JSON.createObjectNode();
            ArrayNode cols = p.putArray("colunas");
            for (Map.Entry<String, String> e : live.entrySet()) {
                ObjectNode c = cols.addObject().put("nome", e.getKey());
                c.put("valor", e.getValue());
                if (t.classeDaColuna(e.getKey(), obj) == ClasseColuna.CIFRADA) c.put("cifrada", true);
            }
            criarComando(j, company, "inserir", t, chave, p);
            gravarEspelho(j, t, chave, company, espelhoDe(t, live));
            return;
        }
        ObjectNode alterar = JSON.createObjectNode();
        ArrayNode alterarCols = alterar.putArray("colunas");
        ObjectNode forcar = JSON.createObjectNode();
        ArrayNode forcarCols = forcar.putArray("colunas");
        List<String> lojaMudadas = new ArrayList<>();
        Map<String, String> novoEsp = new LinkedHashMap<>(esp);
        for (Map.Entry<String, String> e : esp.entrySet()) {
            String col = e.getKey();
            String atual = live.get(col);
            if (Objects.equals(e.getValue(), atual)) continue;
            switch (t.classeDaColuna(col, obj)) {
                case EDITAVEL -> alterarCols.addObject().put("nome", col).put("base", e.getValue()).put("novo", atual);
                case NUVEM, CIFRADA -> {
                    ObjectNode c = forcarCols.addObject().put("nome", col).put("novo", atual);
                    if (t.classeDaColuna(col, obj) == ClasseColuna.CIFRADA) c.put("cifrada", true);
                    novoEsp.put(col, atual); // a loja vai adotar: o espelho acompanha
                }
                case LOJA -> lojaMudadas.add(col + "=" + atual);
                default -> { }
            }
        }
        if (!alterarCols.isEmpty()) criarComando(j, company, "alterar", t, chave, alterar);
        if (!forcarCols.isEmpty()) {
            criarComando(j, company, "forcar", t, chave, forcar);
            gravarEspelho(j, t, chave, company, novoEsp);
        }
        if (!lojaMudadas.isEmpty()) {
            quarentenar(j, company, t, chave, live, origem, "Coluna da loja alterada na nuvem: " + String.join(", ", lojaMudadas));
            if (!inline) criarComando(j, company, "reenviar", t, chave, JSON.createObjectNode());
        }
    }

    private void tabelaDaLoja(JdbcTemplate j, String company, TabelaSincronizada t, String chave, String operacao,
                              Map<String, String> live, Map<String, Object> obj, boolean origemConhecida, boolean inline,
                              String origem) {
        Optional<TabelaSincronizada.AceiteDaNuvem> aceite = t.aceiteDaNuvem(obj);
        boolean criadaNaNuvem = "I".equals(operacao) && !origemConhecida;
        if (aceite.isPresent()) {
            ObjectNode p = JSON.createObjectNode();
            ArrayNode cols = p.putArray("colunas");
            for (String c : aceite.get().colunas()) cols.addObject().put("nome", c).put("novo", live.get(c));
            criarComando(j, company, "forcar", t, chave, p);
            LOG.info("Nuvem alterou {} {} (transicao aceita): enviando por forcar.", t.nome(), chave);
            return;
        }
        if (criadaNaNuvem) {
            quarentenar(j, company, t, chave, live, origem, "Linha criada na nuvem em tabela que so a loja grava.");
            return; // MUST NOT excluir: a spec manda manter; a loja nem tem a linha
        }
        List<String> nuvemCols = new ArrayList<>();
        for (String c : live.keySet()) {
            if (t.classeDaColuna(c, obj) == ClasseColuna.NUVEM && !chavePrimaria(t).contains(c)) nuvemCols.add(c);
        }
        if (!nuvemCols.isEmpty()) { // resultado fiscal (D8): as colunas NUVEM descem por forcar
            ObjectNode p = JSON.createObjectNode();
            ArrayNode cols = p.putArray("colunas");
            for (String c : nuvemCols) cols.addObject().put("nome", c).put("novo", live.get(c));
            criarComando(j, company, "forcar", t, chave, p);
            return;
        }
        quarentenar(j, company, t, chave, live, origem, "Linha alterada na nuvem em tabela que so a loja grava.");
        if (!inline) criarComando(j, company, "reenviar", t, chave, JSON.createObjectNode());
    }

    // ------------------------------------------------------------------ deteccao completa

    /**
     * Compara todas as linhas EDITAVEL da empresa com o espelho (detector parado alem da retencao do tracking).
     * Linhas so no espelho foram excluidas na nuvem.
     */
    public int detectarCompleto(JdbcTemplate j, String company) {
        int n = 0;
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            if (t.classe() != ClasseTabela.EDITAVEL) continue;
            Set<String> vistas = new HashSet<>();
            for (Mudanca m : lerTodas(j, t, company)) {
                String chave = String.join("|", m.pk());
                vistas.add(chave);
                processarLinha(j, company, t, "U", m.pk(), m.linha(), false);
                n++;
            }
            List<String> chaves = j.queryForList("SELECT Chave FROM dbo.SyncEspelho WHERE Tabela = ? AND CompanyId = ?",
                    String.class, t.nome(), company);
            for (String chave : chaves) {
                if (vistas.contains(chave)) continue;
                processarLinha(j, company, t, "D", partesDaChave(chave), null, false);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ comandos, quarentena, alerta

    void criarComando(JdbcTemplate j, String company, String tipo, TabelaSincronizada t, String chave, ObjectNode payload) {
        String json = payload.toString();
        Integer igual = j.queryForObject("""
                SELECT COUNT(*) FROM dbo.SyncComandos
                 WHERE CompanyId = ? AND Tabela = ? AND Chave = ? AND Tipo = ? AND Status = N'pendente'
                   AND ISNULL(PayloadJson, N'') = ?
                """, Integer.class, company, t.nome(), chave, tipo, json);
        if (igual != null && igual > 0) return;
        j.update("INSERT INTO dbo.SyncComandos (CompanyId, Tipo, Tabela, Chave, PayloadJson) VALUES (?, ?, ?, ?, ?)",
                company, tipo, t.nome(), chave, json);
    }

    private void quarentenar(JdbcTemplate j, String company, TabelaSincronizada t, String chave, Map<String, String> linha,
                             String origem, String motivo0) {
        String motivo = ORIGEM_RESSINCRONIZACAO.equals(origem)
                ? "Linha da nuvem sobrescrita na ressincronizacao completa. " + motivo0 : motivo0;
        String json = escrever(linha);
        Integer igual = j.queryForObject("""
                SELECT COUNT(*) FROM dbo.SyncQuarentena
                 WHERE CompanyId = ? AND Tabela = ? AND Chave = ? AND ResolvidoEm IS NULL AND Motivo = ?
                   AND ISNULL(LinhaJson, N'') = ?
                """, Integer.class, company, t.nome(), chave, motivo, json);
        if (igual != null && igual > 0) return;
        j.update("INSERT INTO dbo.SyncQuarentena (CompanyId, Tabela, Chave, Origem, LinhaJson, Motivo) "
                + "VALUES (?, ?, ?, ?, ?, ?)", company, t.nome(), chave, origem, json, truncar(motivo, 500));
        alertar(j, "quarentena", company, t.nome() + " " + chave + ": " + motivo);
    }

    static void alertar(JdbcTemplate j, String motivo, String company, String detalhe) {
        br.com.quack.pdv.plataforma.sincronizacao.alertas.AlertasSync.registrar(j, java.time.OffsetDateTime.now(), motivo,
                company, detalhe);
    }

    private static String escrever(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String truncar(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
