package br.com.quack.pdv.plataforma.sincronizacao.conferencia;

import br.com.quack.pdv.config.QuackProperties;
import br.com.quack.pdv.plataforma.sincronizacao.alertas.AlertasSync;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.conferencia.SqlConferencia.LinhaResumo;
import br.com.quack.pdv.plataforma.sincronizacao.conferencia.SqlConferencia.Resumo;
import br.com.quack.pdv.plataforma.sincronizacao.envio.ClienteJsonNuvem;
import br.com.quack.pdv.plataforma.sincronizacao.envio.EnvioSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.recuperacao.EstadoRecuperacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Conferencia loja x nuvem (D10). Para cada tabela sincronizada compara contagem e soma de verificacao da empresa com
 * a nuvem; na tabela que diverge compara por faixas da chave, depois linha a linha. As linhas diferentes ou ausentes na
 * nuvem sao reenviadas pelo envio normal ({@code SyncReenvios}); as que sobram na nuvem sao excluidas por ela. Repete
 * ate fechar, no maximo {@value #MAX_VOLTAS} voltas, e alerta se sobrar diferenca. Linhas com comando pendente, em
 * quarentena aberta ou criadas na nuvem (aguardando comando) nao sao tocadas: contam como pendencias, nao como
 * divergencia. Os saldos de estoque e de fiado so sao conferidos na loja e nunca corrigidos sozinhos.
 */
@Component
@Profile("loja")
public class ConferenciaSincronizacao {

    private static final Logger LOG = LoggerFactory.getLogger(ConferenciaSincronizacao.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int MAX_VOLTAS = 3;
    private static final int TIMEOUT_S = 120;
    private static final int LIMITE_LISTA = 50;

    /** Diferencas de uma tabela numa volta. */
    private record DiferencaTabela(String tabela, List<String> reenviar, List<String> excluir, int pendencias) {
        boolean suja() { return !reenviar.isEmpty() || !excluir.isEmpty(); }
    }

    /** Resultado gravado (e devolvido pela rota). */
    public record Relatorio(long id, String origem, String resultado, int voltas, JsonNode detalhe) {}

    private final JdbcTemplate jdbc;
    private final EnvioSincronizacao envio;
    private final ClienteJsonNuvem nuvem;
    private final QuackProperties props;
    private final Clock clock;
    private volatile EstadoRecuperacao recuperacao;

    /** Opcional: a conferencia so roda depois da recuperacao pos-restauracao (senao apagaria dados da nuvem). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void definirRecuperacao(EstadoRecuperacao recuperacao) { this.recuperacao = recuperacao; }

    public ConferenciaSincronizacao(JdbcTemplate jdbc, EnvioSincronizacao envio, ClienteJsonNuvem nuvem, QuackProperties props,
                                    Clock clock) {
        this.jdbc = jdbc;
        this.envio = envio;
        this.nuvem = nuvem;
        this.props = props;
        this.clock = clock;
    }

    /** Todo dia as 03:00 (D10); {@code quack.sync.conferencia-cron} muda o horario e {@code -} desliga. */
    @Scheduled(cron = "${quack.sync.conferencia-cron:0 0 3 * * *}")
    public void agendada() {
        if (!props.getSync().isConferenciaAtiva()) return;
        // thread propria: o agendador da API e de uma thread so e a conferencia pode levar minutos
        Thread t = new Thread(() -> {
            try {
                executar("agendada");
            } catch (RuntimeException e) {
                LOG.error("Erro inesperado na conferencia agendada.", e);
            }
        }, "conferencia-sync");
        t.setDaemon(true);
        t.start();
    }

    public synchronized Relatorio executar(String origem) {
        OffsetDateTime agora = OffsetDateTime.now(clock);
        List<Object[]> estado = jdbc.query("SELECT Ativada, CompanyId, PausadaMotivo FROM dbo.SyncEstado WHERE Id = 1",
                (rs, i) -> new Object[] {rs.getBoolean(1), rs.getString(2), rs.getString(3)});
        ObjectNode detalhe = JSON.createObjectNode();
        if (recuperacao != null && recuperacao.emAndamento()) {
            return gravar(origem, "erro", 0, detalhe.put("erro", "Servidor em recuperacao depois de uma restauracao: "
                    + "a conferencia fica parada ate a recuperacao terminar."), null, agora);
        }
        if (estado.isEmpty() || !(Boolean) estado.getFirst()[0] || estado.getFirst()[1] == null) {
            return gravar(origem, "erro", 0, detalhe.put("erro", "A loja ainda nao foi ativada."), null, agora);
        }
        String empresa = (String) estado.getFirst()[1];
        if (estado.getFirst()[2] != null) {
            return gravar(origem, "erro", 0, detalhe.put("erro", "Sincronizacao pausada: " + estado.getFirst()[2]), empresa, agora);
        }
        try {
            return conferir(origem, empresa, detalhe, agora);
        } catch (IOException e) {
            LOG.warn("Conferencia interrompida (nuvem inacessivel?): {}", e.getMessage());
            return gravar(origem, "erro", 0, detalhe.put("erro", "Nuvem inacessivel: " + e.getMessage()), empresa, agora);
        } catch (RuntimeException e) {
            LOG.error("Falha na conferencia.", e);
            return gravar(origem, "erro", 0, detalhe.put("erro", "Falha na conferencia: " + e.getMessage()), empresa, agora);
        }
    }

    private Relatorio conferir(String origem, String empresa, ObjectNode detalhe, OffsetDateTime agora) throws IOException {
        SqlConferencia sql = new SqlConferencia(jdbc);
        envio.ciclo(); // leva o que ainda nao subiu, para nao confundir atraso com divergencia
        Map<String, int[]> acumulado = new LinkedHashMap<>(); // tabela -> {reenviadas, excluidas}
        List<DiferencaTabela> diffs = List.of();
        int voltas = 0;
        boolean fechou = false;
        for (int volta = 1; volta <= MAX_VOLTAS; volta++) {
            voltas = volta;
            diffs = comparar(sql, empresa);
            if (diffs.stream().noneMatch(DiferencaTabela::suja)) { fechou = true; break; }
            corrigir(diffs, acumulado);
        }
        if (!fechou) {
            diffs = comparar(sql, empresa);
            fechou = diffs.stream().noneMatch(DiferencaTabela::suja);
        }
        ArrayNode tabelas = detalhe.putArray("tabelas");
        int pendencias = 0;
        for (Map.Entry<String, int[]> e : acumulado.entrySet()) {
            tabelas.addObject().put("tabela", e.getKey()).put("reenviadas", e.getValue()[0]).put("excluidas", e.getValue()[1]);
        }
        ArrayNode restantes = detalhe.putArray("divergentes");
        for (DiferencaTabela d : diffs) {
            pendencias += d.pendencias();
            if (d.suja()) {
                restantes.addObject().put("tabela", d.tabela()).put("aReenviar", d.reenviar().size())
                        .put("aExcluir", d.excluir().size());
            }
        }
        detalhe.put("pendencias", pendencias);
        detalhe.put("tabelasFecharam", fechou);
        ObjectNode saldos = conferirSaldos(empresa);
        detalhe.set("saldos", saldos);
        boolean saldoDivergente = saldos.path("produtos").size() > 0 || saldos.path("clientes").size() > 0;
        String resultado = fechou && !saldoDivergente ? "ok" : "divergente";
        Relatorio r = gravar(origem, resultado, voltas, detalhe, empresa, agora);
        if ("divergente".equals(resultado)) {
            AlertasSync.registrar(jdbc, agora, AlertasSync.CONFERENCIA, empresa, resumoParaAlerta(fechou, restantes, saldos));
        }
        return r;
    }

    // ------------------------------------------------------------------ comparacao

    private List<DiferencaTabela> comparar(SqlConferencia sql, String empresa) throws IOException {
        Map<String, Resumo> daNuvem = new HashMap<>();
        for (JsonNode n : nuvem.post("/api/loja-sync/conferencia/resumo", null, TIMEOUT_S)) {
            daNuvem.put(n.path("tabela").asText(), new Resumo(n.path("contagem").asLong(), n.path("xor").asLong(), n.path("soma").asLong()));
        }
        List<DiferencaTabela> r = new ArrayList<>();
        for (TabelaSincronizada t : SqlConferencia.tabelas()) {
            Resumo local = sql.resumo(jdbc, t, empresa);
            Resumo remoto = daNuvem.getOrDefault(t.nome(), new Resumo(0, 0, 0));
            if (local.igual(remoto)) continue;
            r.add(compararTabela(sql, t, empresa));
        }
        return r;
    }

    private DiferencaTabela compararTabela(SqlConferencia sql, TabelaSincronizada t, String empresa) throws IOException {
        Map<Integer, Resumo> locais = sql.faixas(jdbc, t, empresa);
        Map<Integer, Resumo> remotas = new HashMap<>();
        ObjectNode pedidoFaixas = JSON.createObjectNode().put("tabela", t.nome());
        for (JsonNode n : nuvem.post("/api/loja-sync/conferencia/faixas", pedidoFaixas, TIMEOUT_S)) {
            remotas.put(n.path("faixa").asInt(), new Resumo(n.path("contagem").asLong(), n.path("xor").asLong(), n.path("soma").asLong()));
        }
        Set<Integer> diferentes = new LinkedHashSet<>();
        Set<Integer> todas = new LinkedHashSet<>(locais.keySet());
        todas.addAll(remotas.keySet());
        for (Integer f : todas) {
            if (!locais.getOrDefault(f, new Resumo(0, 0, 0)).igual(remotas.getOrDefault(f, new Resumo(0, 0, 0)))) diferentes.add(f);
        }
        Map<String, Long> local = new LinkedHashMap<>();
        for (LinhaResumo l : sql.linhas(jdbc, t, empresa, List.copyOf(diferentes))) local.put(l.chave(), l.soma());
        ObjectNode pedido = JSON.createObjectNode().put("tabela", t.nome());
        ArrayNode fx = pedido.putArray("faixas");
        diferentes.forEach(fx::add);
        Map<String, JsonNode> remoto = new LinkedHashMap<>();
        for (JsonNode n : nuvem.post("/api/loja-sync/conferencia/linhas", pedido, TIMEOUT_S)) remoto.put(n.path("chave").asText(), n);

        List<String> reenviar = new ArrayList<>();
        List<String> excluir = new ArrayList<>();
        int pendencias = 0;
        for (Map.Entry<String, Long> e : local.entrySet()) {
            JsonNode n = remoto.get(e.getKey());
            if (n == null) reenviar.add(e.getKey());
            else if (n.path("soma").asLong() != e.getValue()) {
                if (n.path("comandoPendente").asBoolean()) pendencias++; else reenviar.add(e.getKey());
            }
        }
        for (Map.Entry<String, JsonNode> e : remoto.entrySet()) {
            if (local.containsKey(e.getKey())) continue;
            JsonNode n = e.getValue();
            if (n.path("comandoPendente").asBoolean() || n.path("quarentena").asBoolean() || n.path("semEspelho").asBoolean()) {
                pendencias++; // criada na nuvem: espera o comando ou um administrador resolver a quarentena
            } else {
                excluir.add(e.getKey());
            }
        }
        return new DiferencaTabela(t.nome(), reenviar, excluir, pendencias);
    }

    // ------------------------------------------------------------------ correcao

    private void corrigir(List<DiferencaTabela> diffs, Map<String, int[]> acumulado) throws IOException {
        ArrayNode excluir = JSON.createArrayNode();
        for (DiferencaTabela d : diffs) {
            int[] total = acumulado.computeIfAbsent(d.tabela(), k -> new int[2]);
            for (String chave : d.reenviar()) {
                jdbc.update("""
                        IF NOT EXISTS (SELECT 1 FROM dbo.SyncReenvios WHERE Tabela = ? AND Chave = ?)
                            INSERT INTO dbo.SyncReenvios (Tabela, Chave, Motivo) VALUES (?, ?, N'conferencia')
                        """, d.tabela(), chave, d.tabela(), chave);
            }
            total[0] += d.reenviar().size();
            if (!d.excluir().isEmpty()) {
                ObjectNode o = excluir.addObject().put("tabela", d.tabela());
                ArrayNode cs = o.putArray("chaves");
                d.excluir().forEach(cs::add);
                total[1] += d.excluir().size();
            }
        }
        if (!excluir.isEmpty()) {
            nuvem.post("/api/loja-sync/conferencia/excluir", JSON.createObjectNode().set("tabelas", excluir), TIMEOUT_S);
        }
        try {
            envio.ciclo(); // leva os reenvios no proximo lote
        } finally {
            jdbc.update("DELETE FROM dbo.SyncReenvios WHERE Motivo = N'conferencia'");
        }
    }

    // ------------------------------------------------------------------ saldos (so na loja, nunca corrige)

    private ObjectNode conferirSaldos(String empresa) {
        ObjectNode r = JSON.createObjectNode();
        ArrayNode produtos = r.putArray("produtos");
        // ProductQnt deve ser o EstoqueNovo do ultimo movimento (a .NET e a Java gravam o saldo novo em cada movimento);
        // empate de hora entre movimentos do mesmo produto: vale se QUALQUER um dos ultimos fecha com o saldo.
        jdbc.query("""
                SELECT TOP (?) p.Id, p.ProductName, p.ProductQnt,
                       (SELECT TOP 1 m.EstoqueNovo FROM dbo.EstoqueMovimentacoes m
                         WHERE m.CompanyId = p.CompanyId AND m.ProdutoId = p.Id ORDER BY m.CriadoEm DESC, m.Id DESC)
                  FROM dbo.Produtos p
                 WHERE p.CompanyId = ?
                   AND EXISTS (SELECT 1 FROM dbo.EstoqueMovimentacoes m WHERE m.CompanyId = p.CompanyId AND m.ProdutoId = p.Id)
                   AND NOT EXISTS (SELECT 1 FROM dbo.EstoqueMovimentacoes m
                                    WHERE m.CompanyId = p.CompanyId AND m.ProdutoId = p.Id AND m.EstoqueNovo = p.ProductQnt
                                      AND m.CriadoEm = (SELECT MAX(x.CriadoEm) FROM dbo.EstoqueMovimentacoes x
                                                         WHERE x.CompanyId = p.CompanyId AND x.ProdutoId = p.Id))
                 ORDER BY p.ProductName
                """, rs -> {
            produtos.addObject().put("id", rs.getString(1)).put("nome", rs.getString(2))
                    .put("saldoNoCadastro", rs.getBigDecimal(3).toPlainString())
                    .put("saldoDasMovimentacoes", rs.getBigDecimal(4).toPlainString());
        }, LIMITE_LISTA, empresa);
        ArrayNode clientes = r.putArray("clientes");
        jdbc.query("""
                SELECT TOP (?) c.Id, c.CustomerName, c.SaldoDevedor,
                       (SELECT TOP 1 f.SaldoAtual FROM dbo.FiadoMovimentos f
                         WHERE f.CompanyId = c.CompanyId AND f.ClienteId = c.Id ORDER BY f.CriadoEm DESC, f.Id DESC)
                  FROM dbo.Clientes c
                 WHERE c.CompanyId = ?
                   AND EXISTS (SELECT 1 FROM dbo.FiadoMovimentos f WHERE f.CompanyId = c.CompanyId AND f.ClienteId = c.Id)
                   AND NOT EXISTS (SELECT 1 FROM dbo.FiadoMovimentos f
                                    WHERE f.CompanyId = c.CompanyId AND f.ClienteId = c.Id AND f.SaldoAtual = c.SaldoDevedor
                                      AND f.CriadoEm = (SELECT MAX(x.CriadoEm) FROM dbo.FiadoMovimentos x
                                                         WHERE x.CompanyId = c.CompanyId AND x.ClienteId = c.Id))
                 ORDER BY c.CustomerName
                """, rs -> {
            clientes.addObject().put("id", rs.getString(1)).put("nome", rs.getString(2))
                    .put("saldoNoCadastro", rs.getBigDecimal(3).toPlainString())
                    .put("saldoDosMovimentos", rs.getBigDecimal(4).toPlainString());
        }, LIMITE_LISTA, empresa);
        return r;
    }

    // ------------------------------------------------------------------ registro

    private Relatorio gravar(String origem, String resultado, int voltas, ObjectNode detalhe, String empresa, OffsetDateTime agora) {
        Long id = jdbc.queryForObject("""
                INSERT INTO dbo.SyncConferencias (ExecutadaEm, Origem, Resultado, Voltas, DetalheJson)
                OUTPUT INSERTED.Id VALUES (?, ?, ?, ?, ?)
                """, Long.class, agora, origem, resultado, voltas, detalhe.toString());
        if (empresa != null) {
            try {
                ObjectNode corpo = JSON.createObjectNode().put("origem", origem).put("resultado", resultado).put("voltas", voltas);
                corpo.set("detalhe", detalhe);
                nuvem.post("/api/loja-sync/conferencia/resultado", corpo, 30);
            } catch (IOException e) {
                LOG.warn("Resultado da conferencia nao enviado a nuvem: {}", e.getMessage());
            }
        }
        LOG.info("Conferencia {} ({}): {} em {} volta(s).", id, origem, resultado, voltas);
        return new Relatorio(id == null ? 0 : id, origem, resultado, voltas, detalhe);
    }

    private static String resumoParaAlerta(boolean tabelasFecharam, ArrayNode restantes, ObjectNode saldos) {
        StringBuilder sb = new StringBuilder("A conferencia loja x nuvem encontrou diferenca.");
        if (!tabelasFecharam) sb.append("\nTabelas que nao fecharam: ").append(restantes);
        if (saldos.path("produtos").size() > 0) sb.append("\nProdutos com saldo diferente das movimentacoes: ").append(saldos.path("produtos"));
        if (saldos.path("clientes").size() > 0) sb.append("\nClientes com saldo de fiado diferente dos movimentos: ").append(saldos.path("clientes"));
        return sb.toString();
    }
}
