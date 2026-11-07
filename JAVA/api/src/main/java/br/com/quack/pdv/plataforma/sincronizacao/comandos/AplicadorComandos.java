package br.com.quack.pdv.plataforma.sincronizacao.comandos;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ConversorValor;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.LeitorLinhas;
import br.com.quack.pdv.security.SecretProtector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Aplica na loja os comandos da nuvem (D6), na ordem de criacao. Uma transacao por comando, com o contexto do
 * Change Tracking {@code comando-nuvem}. {@code alterar} confere a base coluna a coluna (a loja vence o conflito);
 * a idempotencia vem de {@code SyncComandosAplicados} (o mesmo seq entregue duas vezes nao reaplica); toda aplicacao
 * grava {@code AuditLog SincronizacaoComando}. O resultado volta a nuvem; so depois o cursor {@code ComandosApos} avanca.
 */
@Component
@Profile("loja")
public class AplicadorComandos {

    private static final Logger LOG = LoggerFactory.getLogger(AplicadorComandos.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Contexto do Change Tracking das escritas deste aplicador ("SQ" 0002). */
    public static final String CONTEXTO_COMANDO_NUVEM = "0x53510002";
    private static final String COM_CONTEXTO = "WITH CHANGE_TRACKING_CONTEXT (" + CONTEXTO_COMANDO_NUVEM + ") ";
    static final String COM_CONTEXTO_NUVEM = COM_CONTEXTO;

    public enum Ciclo { INATIVO, OCIOSO, APLICOU, APLICOU_COM_FALHA, SEM_COMUNICACAO }

    /** Resultado da aplicacao de um comando, antes de ir para a nuvem. */
    private record Desfecho(String resultado, ObjectNode detalhe) {}

    private record Estado(boolean ativada, String url, String tokenCifrado, long apos) {}

    private final JdbcTemplate jdbc;
    private final TransactionOperations tx;
    private final ClienteComandos cliente;
    private final SecretProtector segredos;
    private final QuackProperties props;
    private final AplicadorOperacoes operacoes;
    private volatile Map<String, Set<String>> colunasPorTabela;
    private volatile Map<String, List<String>> chavesPorTabela;

    public AplicadorComandos(JdbcTemplate jdbc, TransactionOperations tx, ClienteComandos cliente, SecretProtector segredos,
                             QuackProperties props, AplicadorOperacoes operacoes) {
        this.operacoes = operacoes;
        this.jdbc = jdbc;
        this.tx = tx;
        this.cliente = cliente;
        this.segredos = segredos;
        this.props = props;
    }

    /** Um ciclo: busca (long-poll na nuvem), aplica em ordem, devolve os resultados e avanca o cursor. */
    public synchronized Ciclo ciclo() {
        Estado e = estado();
        if (!e.ativada() || e.tokenCifrado() == null || e.tokenCifrado().isBlank() || e.url().isBlank()) return Ciclo.INATIVO;
        String token = segredos.unprotect(e.tokenCifrado());
        List<ClienteComandos.Comando> comandos;
        try {
            comandos = cliente.buscar(e.url(), token, e.apos());
        } catch (IOException ex) {
            LOG.warn("Comandos: sem comunicacao com a nuvem ({}).", ex.getMessage());
            return Ciclo.SEM_COMUNICACAO;
        }
        if (comandos.isEmpty()) return Ciclo.OCIOSO;
        List<ClienteComandos.Resultado> resultados = new ArrayList<>();
        boolean falhou = false;
        long maior = e.apos();
        for (ClienteComandos.Comando c : comandos) {
            Desfecho d = aplicarUm(c);
            resultados.add(new ClienteComandos.Resultado(c.seq(), d.resultado(), d.detalhe()));
            falhou |= d.resultado().equals("falhou");
            maior = Math.max(maior, c.seq());
        }
        try {
            cliente.enviarResultados(e.url(), token, resultados);
        } catch (IOException ex) {
            LOG.warn("Comandos: resultados nao entregues ({}); a nuvem reenviara os comandos.", ex.getMessage());
            return Ciclo.SEM_COMUNICACAO;
        }
        final long cursor = maior;
        jdbc.update("UPDATE SyncEstado SET ComandosApos = CASE WHEN ComandosApos < ? THEN ? ELSE ComandosApos END, "
                + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE Id = 1", cursor, cursor);
        return falhou ? Ciclo.APLICOU_COM_FALHA : Ciclo.APLICOU;
    }

    private Estado estado() {
        return jdbc.queryForObject("SELECT Ativada, NuvemUrl, TokenCifrado, ComandosApos FROM SyncEstado WHERE Id = 1", (rs, i) -> {
            String url = rs.getString(2);
            if (url == null || url.isBlank()) url = props.getSync().getNuvemUrl();
            return new Estado(rs.getBoolean(1), url, rs.getString(3), rs.getLong(4));
        });
    }

    // ------------------------------------------------------------------ um comando

    private Desfecho aplicarUm(ClienteComandos.Comando c) {
        List<Map<String, Object>> ja = jdbc.queryForList(
                "SELECT Resultado, ResultadoJson FROM SyncComandosAplicados WHERE Seq = ?", c.seq());
        if (!ja.isEmpty() && !"falhou".equals(ja.getFirst().get("Resultado"))) { // idempotencia: nao reaplica
            return new Desfecho((String) ja.getFirst().get("Resultado"), (ObjectNode) lerJson((String) ja.getFirst().get("ResultadoJson")));
        }
        try {
            return tx.execute(s -> {
                Desfecho d = executar(c);
                registrar(c, d);
                return d;
            });
        } catch (RuntimeException ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (ex instanceof org.springframework.dao.DataAccessException dae) msg = dae.getMostSpecificCause().getMessage();
            ObjectNode det = JSON.createObjectNode().put("erro", truncar(msg, 600));
            Desfecho d = new Desfecho("falhou", det);
            LOG.warn("Comando {} ({} {} {}) falhou: {}", c.seq(), c.tipo(), c.tabela(), c.chave(), msg);
            tx.executeWithoutResult(s -> registrar(c, d));
            return d;
        }
    }

    private void registrar(ClienteComandos.Comando c, Desfecho d) {
        jdbc.update("""
                MERGE SyncComandosAplicados AS a USING (SELECT ? AS Seq) AS s ON a.Seq = s.Seq
                WHEN MATCHED THEN UPDATE SET Resultado = ?, ResultadoJson = ?, AplicadoEm = SYSDATETIMEOFFSET()
                WHEN NOT MATCHED THEN INSERT (Seq, Tipo, Tabela, Chave, Resultado, ResultadoJson) VALUES (s.Seq, ?, ?, ?, ?, ?);
                """, c.seq(), d.resultado(), d.detalhe().toString(), c.tipo(), c.tabela(), c.chave(), d.resultado(),
                d.detalhe().toString());
        String empresa = jdbc.queryForObject("SELECT CompanyId FROM SyncEstado WHERE Id = 1", String.class);
        jdbc.update("INSERT INTO AuditLog (CompanyId, UserId, UserName, EventType, EntityType, EntityId, Description, Ip) "
                + "VALUES (?, N'sistema', N'Sincronizacao', ?, ?, ?, ?, N'comando-nuvem')", empresa,
                "operacao".equals(c.tipo()) ? "SincronizacaoOperacao" : "SincronizacaoComando",
                truncar(c.tabela(), 60), truncar(c.chave(), 80),
                truncar("Comando " + c.seq() + " " + c.tipo() + ": " + d.resultado() + " " + d.detalhe(), 500));
    }

    private Desfecho executar(ClienteComandos.Comando c) {
        if ("operacao".equals(c.tipo())) { // operacao de estoque da nuvem (nao e uma linha so)
            String empresa = jdbc.queryForObject("SELECT CompanyId FROM SyncEstado WHERE Id = 1", String.class);
            AplicadorOperacoes.Resultado r = operacoes.aplicar(empresa, c.payload());
            return new Desfecho(r.resultado(), r.detalhe());
        }
        carregarEsquema();
        TabelaSincronizada t = CatalogoSincronizacao.tabela(c.tabela()).filter(TabelaSincronizada::sincroniza)
                .orElseThrow(() -> new IllegalArgumentException("Tabela fora da sincronizacao: " + c.tabela()));
        List<String> pk = chavesPorTabela.get(t.nome());
        List<String> chave = List.of(c.chave().split("\\|", -1));
        if (chave.size() != pk.size()) throw new IllegalArgumentException("Chave invalida para " + t.nome() + ": " + c.chave());
        return switch (c.tipo()) {
            case "alterar" -> alterar(t, pk, chave, c.payload());
            case "inserir" -> inserir(t, pk, chave, c.payload());
            case "excluir" -> excluir(t, pk, chave, c.payload());
            case "forcar" -> forcar(t, pk, chave, c.payload());
            case "reenviar" -> reenviar(t, c.chave());
            default -> throw new IllegalArgumentException("Tipo de comando desconhecido: " + c.tipo());
        };
    }

    // ------------------------------------------------------------------ tipos

    private Desfecho alterar(TabelaSincronizada t, List<String> pk, List<String> chave, JsonNode payload) {
        if (!existe(t, pk, chave)) return conflito("Linha inexistente na loja.");
        List<String> nomes = nomes(payload);
        Map<String, ColunaSync> tipos = tipos(t, nomes, pk);
        ArrayNode aplicadas = JSON.createArrayNode();
        ArrayNode conflitos = JSON.createArrayNode();
        for (JsonNode col : payload.path("colunas")) {
            String nome = col.path("nome").asText();
            if (t.classeDaColuna(nome, null) != ClasseColuna.EDITAVEL) {
                throw new IllegalArgumentException("Coluna nao editavel pela nuvem: " + t.nome() + "." + nome);
            }
            ColunaSync tipo = tipos.get(nome);
            String base = textoOuNulo(col.path("base"));
            String novo = textoOuNulo(col.path("novo"));
            String sql = COM_CONTEXTO + "UPDATE dbo.[" + t.nome() + "] SET [" + nome + "] = ? WHERE " + onde(pk)
                    + " AND " + (base == null ? "[" + nome + "] IS NULL" : "[" + nome + "] = ?");
            int n = jdbc.update(sql, ps -> {
                int i = 1;
                ConversorValor.bind(ps, i++, ConversorValor.decodificar(novo, tipo.tipo()), tipo.tipo());
                i = ligarChave(ps, i, t, pk, chave);
                if (base != null) ConversorValor.bind(ps, i, ConversorValor.decodificar(base, tipo.tipo()), tipo.tipo());
            });
            if (n > 0) {
                aplicadas.add(nome);
                continue;
            }
            String atual = lerColuna(t, pk, chave, nome, tipo);
            if (Objects.equals(atual, novo)) { // a loja ja tem o valor: idempotente
                aplicadas.add(nome);
            } else {
                conflitos.addObject().put("coluna", nome).put("base", base).put("novo", novo).put("loja", atual);
            }
        }
        if (conflitos.isEmpty()) {
            ObjectNode ok = JSON.createObjectNode();
            ok.set("aplicadas", aplicadas);
            return new Desfecho("aplicado", ok);
        }
        // a loja vence: leva a linha de volta para a nuvem no proximo lote, para as duas pontas ficarem iguais
        reenviar(t, String.join("|", chave));
        ObjectNode det = JSON.createObjectNode();
        det.set("aplicadas", aplicadas);
        det.set("conflitos", conflitos);
        return new Desfecho("conflito", det);
    }

    private Desfecho inserir(TabelaSincronizada t, List<String> pk, List<String> chave, JsonNode payload) {
        if (existe(t, pk, chave)) return conflito("A linha ja existe na loja.");
        List<String> nomes = nomes(payload);
        Map<String, ColunaSync> tipos = tipos(t, nomes, List.of());
        List<Object> valores = new ArrayList<>();
        for (JsonNode col : payload.path("colunas")) {
            ColunaSync tipo = tipos.get(col.path("nome").asText());
            valores.add(valorParaLoja(col, "valor", tipo));
        }
        String sql = COM_CONTEXTO + "INSERT INTO dbo.[" + t.nome() + "] ("
                + String.join(", ", nomes.stream().map(n -> "[" + n + "]").toList()) + ") VALUES ("
                + "?, ".repeat(nomes.size() - 1) + "?)";
        jdbc.update(sql, ps -> {
            for (int i = 0; i < nomes.size(); i++) {
                ConversorValor.bind(ps, i + 1, valores.get(i), tipos.get(nomes.get(i)).tipo());
            }
        });
        return new Desfecho("aplicado", JSON.createObjectNode().put("inserida", true));
    }

    private Desfecho excluir(TabelaSincronizada t, List<String> pk, List<String> chave, JsonNode payload) {
        if (!existe(t, pk, chave)) return new Desfecho("aplicado", JSON.createObjectNode().put("jaNaoExistia", true));
        List<String> nomes = nomes(payload);
        Map<String, ColunaSync> tipos = tipos(t, nomes, pk);
        ArrayNode conflitos = JSON.createArrayNode();
        for (JsonNode col : payload.path("colunas")) {
            String nome = col.path("nome").asText();
            String base = textoOuNulo(col.path("base"));
            String atual = lerColuna(t, pk, chave, nome, tipos.get(nome));
            if (!Objects.equals(base, atual)) {
                conflitos.addObject().put("coluna", nome).put("base", base).put("loja", atual);
            }
        }
        if (!conflitos.isEmpty()) {
            ObjectNode det = JSON.createObjectNode();
            det.set("conflitos", conflitos);
            reenviar(t, String.join("|", chave));
            return new Desfecho("conflito", det);
        }
        jdbc.update(COM_CONTEXTO + "DELETE FROM dbo.[" + t.nome() + "] WHERE " + onde(pk),
                ps -> ligarChave(ps, 1, t, pk, chave)); // chave estrangeira impede: vira falhou, com a mensagem
        return new Desfecho("aplicado", JSON.createObjectNode().put("excluida", true));
    }

    private Desfecho forcar(TabelaSincronizada t, List<String> pk, List<String> chave, JsonNode payload) {
        List<String> nomes = nomes(payload);
        Map<String, ColunaSync> tipos = tipos(t, nomes, pk);
        List<Object> valores = new ArrayList<>();
        for (JsonNode col : payload.path("colunas")) valores.add(valorParaLoja(col, "novo", tipos.get(col.path("nome").asText())));
        String sql = COM_CONTEXTO + "UPDATE dbo.[" + t.nome() + "] SET "
                + String.join(", ", nomes.stream().map(n -> "[" + n + "] = ?").toList()) + " WHERE " + onde(pk);
        int n = jdbc.update(sql, ps -> {
            for (int i = 0; i < nomes.size(); i++) {
                ConversorValor.bind(ps, i + 1, valores.get(i), tipos.get(nomes.get(i)).tipo());
            }
            ligarChave(ps, nomes.size() + 1, t, pk, chave);
        });
        if (n == 0) return conflito("Linha inexistente na loja.");
        return new Desfecho("aplicado", JSON.createObjectNode().put("forcadas", nomes.size()));
    }

    private Desfecho reenviar(TabelaSincronizada t, String chave) {
        jdbc.update("""
                MERGE SyncReenvios AS r USING (SELECT ? AS Tabela, ? AS Chave) AS s ON r.Tabela = s.Tabela AND r.Chave = s.Chave
                WHEN NOT MATCHED THEN INSERT (Tabela, Chave, Motivo) VALUES (s.Tabela, s.Chave, N'comando-nuvem');
                """, t.nome(), chave);
        return new Desfecho("aplicado", JSON.createObjectNode().put("reenviar", true));
    }

    // ------------------------------------------------------------------ apoio

    private Desfecho conflito(String motivo) {
        return new Desfecho("conflito", JSON.createObjectNode().put("motivo", motivo));
    }

    private Object valorParaLoja(JsonNode col, String campo, ColunaSync tipo) {
        String texto = textoOuNulo(col.path(campo));
        Object v = ConversorValor.decodificar(texto, tipo.tipo());
        if (v instanceof String s && col.path("cifrada").asBoolean(false)) return segredos.protect(s); // chave da loja
        return v;
    }

    private static String textoOuNulo(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static List<String> nomes(JsonNode payload) {
        List<String> r = new ArrayList<>();
        for (JsonNode c : payload.path("colunas")) r.add(c.path("nome").asText());
        if (r.isEmpty()) throw new IllegalArgumentException("Comando sem colunas.");
        return r;
    }

    private static String onde(List<String> pk) {
        return String.join(" AND ", pk.stream().map(c -> "[" + c + "] = ?").toList());
    }

    /** Tipos (pelo JDBC) das colunas pedidas; recusa coluna inexistente ou que seja chave (quando {@code pk} vem). */
    private Map<String, ColunaSync> tipos(TabelaSincronizada t, List<String> nomes, List<String> pk) {
        Set<String> validas = colunasPorTabela.get(t.nome());
        for (String n : nomes) {
            if (!validas.contains(n)) throw new IllegalArgumentException("Coluna inexistente: " + t.nome() + "." + n);
            if (pk.contains(n)) throw new IllegalArgumentException("Coluna de chave nao se altera: " + t.nome() + "." + n);
        }
        List<String> todas = new ArrayList<>(nomes);
        for (String p : chavesPorTabela.get(t.nome())) if (!todas.contains(p)) todas.add(p);
        List<ColunaSync> lista = jdbc.query("SELECT TOP 0 " + String.join(", ", todas.stream().map(n -> "[" + n + "]").toList())
                + " FROM dbo.[" + t.nome() + "]", rs -> {
            return LeitorLinhas.tipos(rs.getMetaData(), todas, 0);
        });
        Map<String, ColunaSync> m = new HashMap<>();
        for (ColunaSync c : lista) m.put(c.nome(), c);
        return m;
    }

    private int ligarChave(java.sql.PreparedStatement ps, int primeiro, TabelaSincronizada t, List<String> pk,
                           List<String> chave) throws java.sql.SQLException {
        Map<String, ColunaSync> tipos = tipos(t, List.of(), List.of());
        for (int i = 0; i < pk.size(); i++) {
            ColunaSync tp = tipos.get(pk.get(i));
            ConversorValor.bind(ps, primeiro + i, ConversorValor.decodificar(chave.get(i), tp.tipo()), tp.tipo());
        }
        return primeiro + pk.size();
    }

    private boolean existe(TabelaSincronizada t, List<String> pk, List<String> chave) {
        Integer n = jdbc.query("SELECT COUNT(*) FROM dbo.[" + t.nome() + "] WHERE " + onde(pk),
                ps -> ligarChave(ps, 1, t, pk, chave), rs -> rs.next() ? rs.getInt(1) : 0);
        return n != null && n > 0;
    }

    private String lerColuna(TabelaSincronizada t, List<String> pk, List<String> chave, String coluna, ColunaSync tipo) {
        return jdbc.query("SELECT [" + coluna + "] FROM dbo.[" + t.nome() + "] WHERE " + onde(pk),
                ps -> ligarChave(ps, 1, t, pk, chave), rs -> rs.next() ? LeitorLinhas.ler(rs, 1, tipo.tipo()) : null);
    }

    private static JsonNode lerJson(String s) {
        try {
            return s == null ? JSON.createObjectNode() : JSON.readTree(s);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static String truncar(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    private void carregarEsquema() {
        if (colunasPorTabela != null) return;
        EsquemaJdbc esquema = new EsquemaJdbc(jdbc);
        chavesPorTabela = esquema.chavesPrimarias();
        colunasPorTabela = esquema.colunas();
    }
}
