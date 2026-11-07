package br.com.quack.pdv.plataforma.sincronizacao.comandos;

import br.com.quack.pdv.estoque.registrador.MovimentoOrigem;
import br.com.quack.pdv.estoque.registrador.RegistradorEstoque;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ConversorValor;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.LeitorLinhas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aplica na loja o comando {@code operacao} (O1, O2, O6 da change api-java-operacoes-estoque-nuvem): o que a nuvem gravou
 * numa transacao (entrada de nota, estorno, ajuste). Roda dentro da transacao do {@link AplicadorComandos}: conflitos sao
 * detectados ANTES de qualquer escrita (nada fica gravado) e qualquer excecao desfaz tudo. O saldo da nuvem nunca e
 * copiado: cada {@code movimento} soma a diferenca (EstoqueNovo - EstoqueAnterior) ao saldo da loja pelo registrador.
 */
@Component
@Profile("loja")
public class AplicadorOperacoes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOTAS = "NotasEntradaArquivo";
    private static final String MOVIMENTOS = "EstoqueMovimentacoes";

    /** Resultado da operacao: aplicado ou conflito, com o detalhe que volta a nuvem. */
    public record Resultado(String resultado, ObjectNode detalhe) {}

    private record Nota(String id, String identidade, String operacaoManualId, String numero) {}

    private final JdbcTemplate jdbc;
    private final RegistradorEstoque registrador;

    public AplicadorOperacoes(JdbcTemplate jdbc, RegistradorEstoque registrador) {
        this.jdbc = jdbc;
        this.registrador = registrador;
    }

    public Resultado aplicar(String companyId, JsonNode payload) {
        List<JsonNode> passos = new ArrayList<>();
        payload.path("passos").forEach(passos::add);
        if (passos.isEmpty()) throw new IllegalArgumentException("Operacao sem passos.");
        for (JsonNode p : passos) validar(companyId, p);
        ObjectNode conflito = conflitoPrevio(companyId, passos);
        if (conflito != null) return new Resultado("conflito", conflito);

        int inseridos = 0;
        int movimentos = 0;
        int transicoes = 0;
        int pulados = 0;
        for (JsonNode p : passos) {
            boolean aplicou = switch (p.path("tipo").asText()) {
                case "inserir" -> inserir(companyId, p);
                case "movimento" -> movimento(companyId, p);
                case "transicao" -> transicao(companyId, p);
                default -> throw new IllegalArgumentException("Passo desconhecido: " + p.path("tipo").asText());
            };
            if (!aplicou) pulados++;
            else switch (p.path("tipo").asText()) {
                case "inserir" -> inseridos++;
                case "movimento" -> movimentos++;
                default -> transicoes++;
            }
        }
        ObjectNode ok = JSON.createObjectNode().put("versaoNuvem", payload.path("versaoNuvem").asLong())
                .put("passos", passos.size()).put("inseridos", inseridos).put("movimentos", movimentos)
                .put("transicoes", transicoes).put("pulados", pulados);
        return new Resultado("aplicado", ok);
    }

    // ------------------------------------------------------------------ validacao e conflitos

    private void validar(String companyId, JsonNode p) {
        String tabela = p.path("tabela").asText();
        TabelaSincronizada t = CatalogoSincronizacao.tabela(tabela).orElseThrow(
                () -> new IllegalArgumentException("Tabela fora da sincronizacao: " + tabela));
        if (!t.participaDeOperacaoDaNuvem()) throw new IllegalArgumentException("Tabela sem operacao da nuvem: " + tabela);
        if (p.path("chave").asText().isBlank()) throw new IllegalArgumentException("Passo sem chave em " + tabela);
        String tipo = p.path("tipo").asText();
        if (tipo.equals("transicao") && t.transicao(p.path("regra").asText()).isEmpty()) {
            throw new IllegalArgumentException("Transicao nao aceita: " + tabela + "." + p.path("regra").asText());
        }
        if (!tipo.equals("transicao") && !t.insercaoDaNuvem()) {
            throw new IllegalArgumentException("Insercao da nuvem nao aceita em " + tabela);
        }
        if (!tipo.equals("transicao")) {
            String empresa = valor(p, "CompanyId", "valor");
            if (!companyId.equals(empresa)) throw new IllegalArgumentException("Linha de outra empresa em " + tabela);
        }
    }

    private ObjectNode conflitoPrevio(String companyId, List<JsonNode> passos) {
        Map<String, Nota> notasNoPasso = new LinkedHashMap<>();
        for (JsonNode p : passos) {
            if (!p.path("tipo").asText().equals("inserir") || !p.path("tabela").asText().equals(NOTAS)) continue;
            notasNoPasso.put(p.path("chave").asText(), new Nota(p.path("chave").asText(), valor(p, "Identidade", "valor"),
                    valor(p, "OperacaoManualId", "valor"), valor(p, "NumeroNota", "valor")));
        }
        for (Nota n : notasNoPasso.values()) {
            if (existe(NOTAS, n.id())) continue; // reentrega
            String outra = notaAtivaIgual(companyId, n.id(), n.identidade(), n.operacaoManualId());
            if (outra != null) return conflito("nota-ja-lancada", n.numero(), outra);
        }
        for (JsonNode p : passos) {
            String tipo = p.path("tipo").asText();
            String chave = p.path("chave").asText();
            if (tipo.equals("transicao")) {
                if (!existe(p.path("tabela").asText(), chave) && !notasNoPasso.containsKey(chave)) {
                    return conflito("linha-ausente", chave, null);
                }
                if (p.path("regra").asText().equals("xml")) {
                    String outra = notaAtivaIgual(companyId, chave, valor(p, "Identidade", "novo"), null);
                    if (outra != null) return conflito("nota-ja-lancada", valor(p, "NumeroNota", "novo"), outra);
                }
            } else if (tipo.equals("movimento") && "nota_entrada".equals(valor(p, "OrigemTipo", "valor"))
                    && !existe(MOVIMENTOS, chave)) {
                String origem = valor(p, "OrigemId", "valor");
                boolean noPasso = notasNoPasso.values().stream()
                        .anyMatch(n -> n.id().equals(origem) || origem.equals(n.operacaoManualId()));
                if (!noPasso && !notaExiste(companyId, origem)) return conflito("nota-recusada", origem, null);
            }
        }
        return null;
    }

    private ObjectNode conflito(String motivo, String numero, String idLoja) {
        ObjectNode d = JSON.createObjectNode().put("motivo", motivo);
        if (numero != null) d.put("numero", numero);
        if (idLoja != null) d.put("idLoja", idLoja);
        return d;
    }

    /** Id da outra entrada ativa da loja com a mesma identidade (ou mesma operacao manual), se houver. */
    private String notaAtivaIgual(String companyId, String idDaNuvem, String identidade, String operacaoManualId) {
        List<String> r = jdbc.queryForList("""
                SELECT TOP 1 Id FROM NotasEntradaArquivo
                 WHERE CompanyId = ? AND Id <> ? AND ISNULL(Estornada, 0) = 0
                   AND ((? IS NOT NULL AND Identidade = ?) OR (? IS NOT NULL AND OperacaoManualId = ?))
                """, String.class, companyId, idDaNuvem, identidade, identidade, operacaoManualId, operacaoManualId);
        return r.isEmpty() ? null : r.getFirst();
    }

    private boolean notaExiste(String companyId, String idOuOperacao) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE CompanyId = ? AND (Id = ? OR OperacaoManualId = ?)",
                Integer.class, companyId, idOuOperacao, idOuOperacao);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ passos

    private boolean inserir(String companyId, JsonNode p) {
        String tabela = p.path("tabela").asText();
        String chave = p.path("chave").asText();
        if (existe(tabela, chave)) return false;
        if (tabela.equals(NOTAS)) { // como a .NET (ReservarAsync): entrada estornada da mesma nota sai do caminho
            jdbc.update("DELETE FROM NotasEntradaArquivo WHERE CompanyId = ? AND Identidade = ? AND Estornada = 1 AND Id <> ?",
                    companyId, valor(p, "Identidade", "valor"), chave);
        }
        List<String> nomes = nomes(p);
        Map<String, ColunaSync> tipos = tipos(tabela, nomes);
        String sql = AplicadorComandos.COM_CONTEXTO_NUVEM + "INSERT INTO dbo.[" + tabela + "] ("
                + String.join(", ", nomes.stream().map(n -> "[" + n + "]").toList()) + ") VALUES ("
                + "?, ".repeat(nomes.size() - 1) + "?)";
        List<Object> valores = valores(p, "valor", nomes, tipos);
        jdbc.update(sql, ps -> {
            for (int i = 0; i < nomes.size(); i++) {
                ConversorValor.bind(ps, i + 1, valores.get(i), tipos.get(nomes.get(i)).tipo());
            }
        });
        return true;
    }

    private boolean movimento(String companyId, JsonNode p) {
        String id = p.path("chave").asText();
        if (existe(MOVIMENTOS, id)) return false;
        BigDecimal delta = decimal(p, "EstoqueNovo").subtract(decimal(p, "EstoqueAnterior"));
        MovimentoOrigem origem = new MovimentoOrigem(valor(p, "OrigemTipo", "valor"), valor(p, "OrigemId", "valor"),
                valor(p, "Motivo", "valor"), valor(p, "UsuarioId", "valor"), valor(p, "UsuarioNome", "valor"));
        String custo = valor(p, "CustoUnitario", "valor");
        String produtoId = valor(p, "ProdutoId", "valor");
        String deposito = valor(p, "DepositoId", "valor");
        registrador.aplicarComId(companyId, produtoId, delta, valor(p, "Tipo", "valor"),
                custo == null ? null : new BigDecimal(custo), origem, deposito, id)
                .orElseThrow(() -> new IllegalStateException("Produto " + produtoId + " inexistente na loja."));
        return true;
    }

    private boolean transicao(String companyId, JsonNode p) {
        String tabela = p.path("tabela").asText();
        TabelaSincronizada t = CatalogoSincronizacao.tabela(tabela).orElseThrow();
        TabelaSincronizada.TransicaoDaNuvem tr = t.transicao(p.path("regra").asText()).orElseThrow();
        String chave = p.path("chave").asText();
        List<Object> atual = jdbc.query("SELECT [" + tr.colunaLoja() + "] FROM dbo.[" + tabela + "] WHERE Id = ?",
                (rs, i) -> rs.getObject(1), chave);
        if (atual.isEmpty()) return false; // a nota da mesma operacao ja foi inserida; so cai aqui em corrida
        String naLoja = atual.getFirst() instanceof Boolean b ? (b ? "1" : "0") : String.valueOf(atual.getFirst());
        if (!tr.valorLoja().equals(naLoja)) return false; // a loja ja esta no destino (idempotente)
        List<String> nomes = nomes(p);
        for (String n : nomes) {
            if (!tr.colunas().contains(n)) throw new IllegalArgumentException("Coluna fora da transicao: " + tabela + "." + n);
        }
        Map<String, ColunaSync> tipos = tipos(tabela, nomes);
        List<Object> valores = valores(p, "novo", nomes, tipos);
        String sql = AplicadorComandos.COM_CONTEXTO_NUVEM + "UPDATE dbo.[" + tabela + "] SET "
                + String.join(", ", nomes.stream().map(n -> "[" + n + "] = ?").toList()) + " WHERE Id = ? AND CompanyId = ?";
        jdbc.update(sql, ps -> {
            for (int i = 0; i < nomes.size(); i++) {
                ConversorValor.bind(ps, i + 1, valores.get(i), tipos.get(nomes.get(i)).tipo());
            }
            ps.setString(nomes.size() + 1, chave);
            ps.setString(nomes.size() + 2, companyId);
        });
        return true;
    }

    // ------------------------------------------------------------------ apoio

    private boolean existe(String tabela, String chave) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.[" + tabela + "] WHERE Id = ?", Integer.class, chave);
        return n != null && n > 0;
    }

    private static List<String> nomes(JsonNode p) {
        List<String> r = new ArrayList<>();
        for (JsonNode c : p.path("colunas")) r.add(c.path("nome").asText());
        if (r.isEmpty()) throw new IllegalArgumentException("Passo sem colunas.");
        return r;
    }

    private Map<String, ColunaSync> tipos(String tabela, List<String> nomes) {
        Set<String> unicas = new HashSet<>(nomes);
        if (unicas.size() != nomes.size()) throw new IllegalArgumentException("Coluna repetida em " + tabela);
        List<ColunaSync> lista = jdbc.query("SELECT TOP 0 " + String.join(", ", nomes.stream().map(n -> "[" + n + "]").toList())
                + " FROM dbo.[" + tabela + "]", rs -> {
            return LeitorLinhas.tipos(rs.getMetaData(), nomes, 0);
        });
        Map<String, ColunaSync> m = new HashMap<>();
        for (ColunaSync c : lista) m.put(c.nome(), c);
        return m;
    }

    private static List<Object> valores(JsonNode p, String campo, List<String> nomes, Map<String, ColunaSync> tipos) {
        List<Object> r = new ArrayList<>();
        int i = 0;
        for (JsonNode c : p.path("colunas")) {
            JsonNode v = c.path(campo);
            r.add(ConversorValor.decodificar(v.isMissingNode() || v.isNull() ? null : v.asText(),
                    tipos.get(nomes.get(i++)).tipo()));
        }
        return r;
    }

    /** Valor (texto) de uma coluna do passo, ou nulo. */
    private static String valor(JsonNode p, String coluna, String campo) {
        for (JsonNode c : p.path("colunas")) {
            if (c.path("nome").asText().equals(coluna)) {
                JsonNode v = c.path(campo);
                return v.isMissingNode() || v.isNull() ? null : v.asText();
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode p, String coluna) {
        String v = valor(p, coluna, "valor");
        if (v == null) throw new IllegalArgumentException("Movimentacao sem " + coluna + ".");
        return new BigDecimal(v);
    }
}
