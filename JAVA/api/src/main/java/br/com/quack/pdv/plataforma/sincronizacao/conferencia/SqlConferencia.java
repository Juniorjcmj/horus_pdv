package br.com.quack.pdv.plataforma.sincronizacao.conferencia;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * O SQL da conferencia (D10), o MESMO nas duas pontas: contagem e soma de verificacao do conteudo das linhas da empresa,
 * por tabela e por faixa da chave. Compara so as colunas {@code LOJA} e {@code EDITAVEL} (sem {@code LOCAL},
 * {@code CIFRADA} e {@code NUVEM}) mais a chave. Linhas de autoria da nuvem (regra por linha, ex.: NF-e modelo 55) ficam
 * de fora. {@code AuditLog} nao entra: as duas pontas inserem nele e os Ids sao proprios (ver notas do grupo 6).
 *
 * <p>Soma de verificacao: {@code CHECKSUM_AGG(BINARY_CHECKSUM(...))} como o design pede, mais a soma dos checksums das
 * linhas em {@code BIGINT}. O {@code CHECKSUM_AGG} sozinho e um XOR: duas linhas iguais que mudam juntas se anulam.
 */
public final class SqlConferencia {

    /** Numero de faixas da chave usadas para localizar as linhas diferentes. */
    public static final int FAIXAS = 64;

    /** Resultado do resumo de uma tabela (ou de uma faixa). */
    public record Resumo(long contagem, long xor, long soma) {
        public boolean igual(Resumo o) {
            return contagem == o.contagem && xor == o.xor && soma == o.soma;
        }
    }

    /** Uma linha: chave (valores separados por {@code |}) e o checksum do conteudo. */
    public record LinhaResumo(String chave, long soma) {}

    private final Map<String, List<String>> chaves;
    private final Map<String, Set<String>> colunas;

    public SqlConferencia(JdbcTemplate jdbc) {
        EsquemaJdbc esquema = new EsquemaJdbc(jdbc);
        this.chaves = esquema.chavesPrimarias();
        this.colunas = esquema.colunas();
    }

    /** Tabelas conferidas, na ordem de aplicacao (pais antes de filhos). */
    public static List<TabelaSincronizada> tabelas() {
        return CatalogoSincronizacao.ordemDeAplicacao().stream().filter(t -> t.classe() != ClasseTabela.ACRESCIMO).toList();
    }

    public List<String> chave(TabelaSincronizada t) { return chaves.get(t.nome()); }

    private List<String> comparaveis(TabelaSincronizada t) {
        List<String> r = new ArrayList<>(chave(t));
        for (String c : colunas.get(t.nome())) {
            if (r.contains(c)) continue;
            ClasseColuna cl = t.classeDaColuna(c, null);
            if (cl == ClasseColuna.LOJA || cl == ClasseColuna.EDITAVEL) r.add(c);
        }
        return r;
    }

    private String checksum(TabelaSincronizada t) {
        return "BINARY_CHECKSUM(" + String.join(", ", comparaveis(t).stream().map(c -> "T.[" + c + "]").toList()) + ")";
    }

    private String faixaExpr(TabelaSincronizada t) {
        return "(CHECKSUM(" + String.join(", ", chave(t).stream().map(c -> "T.[" + c + "]").toList()) + ") & " + (FAIXAS - 1) + ")";
    }

    private String chaveExpr(TabelaSincronizada t) {
        return String.join(" + N'|' + ", chave(t).stream().map(c -> "CAST(T.[" + c + "] AS NVARCHAR(200))").toList());
    }

    /** {@code FROM ... WHERE empresa = ? [AND NOT autoria da nuvem]}; o unico parametro e a empresa. */
    private String origem(TabelaSincronizada t) {
        StringBuilder sb = new StringBuilder(" FROM dbo.[").append(t.nome()).append("] AS T");
        String empresaExpr;
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
            sb.append(" JOIN dbo.[").append(mae.nome()).append("] AS M ON M.[").append(chave(mae).getFirst()).append("] = T.[")
                    .append(t.colunaLigacao()).append("]");
            empresaExpr = "M.[" + mae.colunaEmpresa() + "]";
        } else {
            empresaExpr = "T.[" + t.colunaEmpresa() + "]";
        }
        sb.append(" WHERE ").append(empresaExpr).append(" = ?");
        for (TabelaSincronizada.RegraLinha r : t.regras()) {
            if (r.classeDasColunas() == ClasseColuna.NUVEM) {
                String literal = r.valor() instanceof Number ? r.valor().toString() : "N'" + r.valor().toString().replace("'", "''") + "'";
                sb.append(" AND NOT (T.[").append(r.coluna()).append("] = ").append(literal).append(")");
            }
        }
        return sb.toString();
    }

    private static final String AGREGADO = "COUNT_BIG(*), ISNULL(CHECKSUM_AGG(%1$s), 0), ISNULL(SUM(CAST(%1$s AS BIGINT)), 0)";

    public Resumo resumo(JdbcTemplate j, TabelaSincronizada t, String companyId) {
        String sql = "SELECT " + AGREGADO.formatted(checksum(t)) + origem(t);
        return j.queryForObject(sql, (rs, i) -> new Resumo(rs.getLong(1), rs.getLong(2), rs.getLong(3)), companyId);
    }

    /** Resumo por faixa da chave (so as faixas que tem linha). */
    public Map<Integer, Resumo> faixas(JdbcTemplate j, TabelaSincronizada t, String companyId) {
        String sql = "SELECT " + faixaExpr(t) + ", " + AGREGADO.formatted(checksum(t)) + origem(t) + " GROUP BY " + faixaExpr(t);
        Map<Integer, Resumo> r = new LinkedHashMap<>();
        j.query(sql, rs -> {
            r.put(rs.getInt(1), new Resumo(rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        }, companyId);
        return r;
    }

    /** Chave e checksum de cada linha das faixas indicadas. */
    public List<LinhaResumo> linhas(JdbcTemplate j, TabelaSincronizada t, String companyId, List<Integer> faixas) {
        if (faixas.isEmpty()) return List.of();
        String lista = String.join(", ", faixas.stream().map(f -> String.valueOf(f & (FAIXAS - 1))).toList());
        String sql = "SELECT " + chaveExpr(t) + ", CAST(" + checksum(t) + " AS BIGINT)" + origem(t) + " AND " + faixaExpr(t)
                + " IN (" + lista + ")";
        return j.query(sql, (rs, i) -> new LinhaResumo(rs.getString(1), rs.getLong(2)), companyId);
    }
}
