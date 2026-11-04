package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.security.SecretProtector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Fila de comandos nuvem -> loja (D6): entrega os comandos pendentes da empresa e registra os resultados. Comando que
 * falha {@link #MAX_FALHAS} vezes fica {@code parado} com alerta e nao trava os seguintes.
 */
@Service
@org.springframework.context.annotation.Lazy
@Profile("nuvem")
public class ServicoComandos {

    public static final int MAX_FALHAS = 5;
    public static final int LIMITE_ENTREGA = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Comando entregue a loja: o payload ja traz os segredos decifrados (a loja recifra com a chave dela). */
    public record Comando(long seq, String tipo, String tabela, String chave, JsonNode payload, int tentativas) {}

    /** Resultado devolvido pela loja: aplicado, conflito ou falhou, com o detalhe (valores, erro). */
    public record Resultado(Long seq, String resultado, JsonNode detalhe) {}

    private final JdbcTemplate jdbc;
    private final MotorDeteccao motor;
    private final SecretProtector segredos;
    private final TransactionOperations tx;

    public ServicoComandos(JdbcTemplate jdbc, MotorDeteccao motor, SecretProtector segredos, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.motor = motor;
        this.segredos = segredos;
        this.tx = tx;
    }

    /**
     * Pendentes com {@code seq > apos}, mais os que ja foram entregues e falharam (reentrega). Ordem de criacao.
     */
    public List<Comando> pendentes(String companyId, long apos) {
        return jdbc.query("SELECT TOP (" + LIMITE_ENTREGA + ") Seq, Tipo, Tabela, Chave, PayloadJson, Tentativas "
                + "FROM dbo.SyncComandos WHERE CompanyId = ? AND Status = N'pendente' AND (Seq > ? OR Tentativas > 0) "
                + "ORDER BY Seq", (rs, i) -> new Comando(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        decifrar(rs.getString(5)), rs.getInt(6)), companyId, apos);
    }

    private JsonNode decifrar(String payload) {
        try {
            JsonNode raiz = payload == null ? JSON.createObjectNode() : JSON.readTree(payload);
            for (JsonNode c : raiz.path("colunas")) {
                if (!c.path("cifrada").asBoolean(false) || !(c instanceof ObjectNode o)) continue;
                String campo = o.has("valor") ? "valor" : "novo";
                if (!o.path(campo).isNull() && o.has(campo)) o.put(campo, segredos.unprotect(o.get(campo).asText()));
            }
            return raiz;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload de comando ilegivel.", e);
        }
    }

    /** Registra os resultados (idempotente: comando ja terminal e ignorado). */
    public void registrar(ServidorLoja servidor, List<Resultado> resultados) {
        for (Resultado r : resultados) {
            if (r.seq() == null || r.resultado() == null) continue;
            tx.executeWithoutResult(s -> registrarUm(servidor.companyId(), r));
        }
    }

    private void registrarUm(String company, Resultado r) {
        List<Map<String, Object>> achados = jdbc.queryForList("""
                SELECT Tipo, Tabela, Chave, PayloadJson, Tentativas FROM dbo.SyncComandos WITH (UPDLOCK)
                 WHERE Seq = ? AND CompanyId = ? AND Status = N'pendente'
                """, r.seq(), company);
        if (achados.isEmpty()) return;
        Map<String, Object> c = achados.getFirst();
        String detalhe = r.detalhe() == null ? null : r.detalhe().toString();
        switch (r.resultado()) {
            case "aplicado" -> {
                finalizar(r.seq(), "aplicado", detalhe);
                atualizarEspelho(company, c);
            }
            case "conflito" -> {
                finalizar(r.seq(), "conflito", detalhe);
                MotorDeteccao.alertar(jdbc, "comando-conflito", company,
                        c.get("Tabela") + " " + c.get("Chave") + ": " + MotorDeteccao.truncar(detalhe, 700));
            }
            case "falhou" -> {
                int tentativas = ((Number) c.get("Tentativas")).intValue() + 1;
                if (tentativas >= MAX_FALHAS) {
                    jdbc.update("UPDATE dbo.SyncComandos SET Status = N'parado', Tentativas = ?, ResultadoJson = ?, "
                            + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE Seq = ?", tentativas, detalhe, r.seq());
                    MotorDeteccao.alertar(jdbc, "comando-parado", company, c.get("Tabela") + " " + c.get("Chave")
                            + " (seq " + r.seq() + ") parado apos " + tentativas + " falhas: "
                            + MotorDeteccao.truncar(detalhe, 700));
                } else {
                    jdbc.update("UPDATE dbo.SyncComandos SET Tentativas = ?, ResultadoJson = ?, "
                            + "AtualizadoEm = SYSDATETIMEOFFSET() WHERE Seq = ?", tentativas, detalhe, r.seq());
                }
            }
            default -> { }
        }
    }

    private void finalizar(long seq, String status, String detalhe) {
        jdbc.update("UPDATE dbo.SyncComandos SET Status = ?, ResultadoJson = ?, AtualizadoEm = SYSDATETIMEOFFSET() "
                + "WHERE Seq = ?", status, detalhe, seq);
    }

    /** Comando aplicado: o espelho passa a refletir o que a loja agora tem (nova base para as proximas edicoes). */
    private void atualizarEspelho(String company, Map<String, Object> c) {
        String tipo = (String) c.get("Tipo");
        if (!tipo.equals("alterar") && !tipo.equals("forcar")) return;
        Optional<TabelaSincronizada> t = CatalogoSincronizacao.tabela((String) c.get("Tabela")).filter(TabelaSincronizada::usaEspelho);
        if (t.isEmpty()) return;
        String chave = (String) c.get("Chave");
        Optional<Map<String, String>> esp = motor.lerEspelho(jdbc, t.get(), chave, company);
        if (esp.isEmpty()) return;
        try {
            Map<String, String> novo = new LinkedHashMap<>(esp.get());
            List<String> mudadas = new ArrayList<>();
            for (JsonNode col : JSON.readTree((String) c.get("PayloadJson")).path("colunas")) {
                String nome = col.path("nome").asText();
                if (novo.containsKey(nome)) {
                    novo.put(nome, col.path("novo").isNull() ? null : col.path("novo").asText());
                    mudadas.add(nome);
                }
            }
            if (!mudadas.isEmpty()) motor.gravarEspelho(jdbc, t.get(), chave, company, novo);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payload de comando ilegivel.", e);
        }
    }
}
