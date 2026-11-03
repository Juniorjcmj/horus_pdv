package br.com.quack.pdv.produto.lote.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Pendencias de baixa/estorno de lotes de venda (D13): tabela {@code LoteBaixaPendencias}, so da API Java.
 * Guarda o que falhou depois do commit da venda para a rotina {@link RetentativaBaixaLotes} tentar de novo, com
 * backoff exponencial e limite de tentativas; passado o limite, a pendencia fica em {@code revisao}.
 */
@Component
public class LotePendencias {

    public static final String TIPO_CONSUMO = "consumo";
    public static final String TIPO_ESTORNO = "estorno";
    public static final String ESTADO_PENDENTE = "pendente";
    public static final String ESTADO_CONCLUIDA = "concluida";
    public static final String ESTADO_REVISAO = "revisao";

    /** Pendencia reservada para uma tentativa. */
    public record Pendencia(String id, String companyId, String vendaId, String tipo, String itensJson,
                            int tentativas) {}

    private static final int MAX_ERRO = 500;
    private static final Duration TETO_BACKOFF = Duration.ofHours(1);
    private static final Duration RESERVA = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int maxTentativas;
    private final long baseSegundos;
    private final ObjectMapper json = new ObjectMapper();

    public LotePendencias(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${quack.lote.retentativa.max-tentativas:8}") int maxTentativas,
            @Value("${quack.lote.retentativa.base-segundos:30}") long baseSegundos) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxTentativas = maxTentativas;
        this.baseSegundos = baseSegundos;
    }

    /** Registra (ou reabre) a pendencia. Primeira nova tentativa apos o backoff base. */
    public void registrar(String companyId, String vendaId, String tipo, String itensJson, String erro) {
        OffsetDateTime proxima = agora().plus(atraso(0));
        int atualizadas = jdbc.update("""
                UPDATE LoteBaixaPendencias
                   SET Estado = N'pendente', Tentativas = 0, ItensJson = ?, ProximaTentativaEm = ?,
                       UltimoErro = ?, AtualizadoEm = SYSDATETIMEOFFSET()
                 WHERE CompanyId = ? AND VendaId = ? AND Tipo = ?;
                """, itensJson, proxima, truncar(erro), companyId, vendaId, tipo);
        if (atualizadas == 0) {
            jdbc.update("""
                    INSERT INTO LoteBaixaPendencias (Id, CompanyId, VendaId, Tipo, ItensJson, ProximaTentativaEm, UltimoErro)
                    VALUES (?, ?, ?, ?, ?, ?, ?);
                    """, "lbp-" + UUID.randomUUID().toString().replace("-", ""), companyId, vendaId, tipo, itensJson,
                    proxima, truncar(erro));
        }
    }

    /**
     * Reserva, de forma atomica, ate {@code limite} pendencias vencidas (adia a proxima tentativa por alguns minutos,
     * para duas instancias nao pegarem a mesma).
     */
    public List<Pendencia> reservarVencidas(int limite) {
        OffsetDateTime agora = agora();
        return jdbc.query("""
                UPDATE TOP (?) LoteBaixaPendencias
                   SET ProximaTentativaEm = ?, AtualizadoEm = SYSDATETIMEOFFSET()
                OUTPUT inserted.Id, inserted.CompanyId, inserted.VendaId, inserted.Tipo, inserted.ItensJson,
                       inserted.Tentativas
                 WHERE Estado = N'pendente' AND ProximaTentativaEm <= ?;
                """, (rs, n) -> new Pendencia(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6)), limite, agora.plus(RESERVA), agora);
    }

    public void concluir(String id) {
        jdbc.update("""
                UPDATE LoteBaixaPendencias
                   SET Estado = N'concluida', UltimoErro = NULL, AtualizadoEm = SYSDATETIMEOFFSET()
                 WHERE Id = ?;
                """, id);
    }

    /** Registra a falha de uma tentativa; passado o limite marca para revisao. Devolve o estado resultante. */
    public String falhar(Pendencia p, String erro) {
        int tentativas = p.tentativas() + 1;
        if (tentativas >= maxTentativas) {
            jdbc.update("""
                    UPDATE LoteBaixaPendencias
                       SET Estado = N'revisao', Tentativas = ?, UltimoErro = ?, AtualizadoEm = SYSDATETIMEOFFSET()
                     WHERE Id = ?;
                    """, tentativas, truncar(erro), p.id());
            return ESTADO_REVISAO;
        }
        jdbc.update("""
                UPDATE LoteBaixaPendencias
                   SET Tentativas = ?, ProximaTentativaEm = ?, UltimoErro = ?, AtualizadoEm = SYSDATETIMEOFFSET()
                 WHERE Id = ?;
                """, tentativas, agora().plus(atraso(tentativas)), truncar(erro), p.id());
        return ESTADO_PENDENTE;
    }

    /** Backoff exponencial: base * 2^tentativas, ate o teto de uma hora. */
    Duration atraso(int tentativas) {
        long segundos = baseSegundos * (1L << Math.min(tentativas, 20));
        Duration d = Duration.ofSeconds(segundos);
        return d.compareTo(TETO_BACKOFF) > 0 ? TETO_BACKOFF : d;
    }

    public int maxTentativas() {
        return maxTentativas;
    }

    // ---------------------------------------------------------------- itens do consumo

    public String itensParaJson(List<LoteOperacoes.ItemVenda> itens) {
        ArrayNode arr = json.createArrayNode();
        for (LoteOperacoes.ItemVenda i : itens) {
            arr.addObject().put("c", i.productCode()).put("q", i.quantity().toPlainString());
        }
        return arr.toString();
    }

    public List<LoteOperacoes.ItemVenda> itensDeJson(String texto) {
        List<LoteOperacoes.ItemVenda> itens = new ArrayList<>();
        if (texto == null || texto.isBlank()) return itens;
        try {
            for (JsonNode n : json.readTree(texto)) {
                itens.add(new LoteOperacoes.ItemVenda(n.get("c").asText(), new BigDecimal(n.get("q").asText())));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ItensJson invalido na pendencia de lote.", e);
        }
        return itens;
    }

    private OffsetDateTime agora() {
        return OffsetDateTime.now(clock);
    }

    private static String truncar(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERRO ? s : s.substring(0, MAX_ERRO);
    }
}
