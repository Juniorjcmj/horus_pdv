package br.com.quack.pdv.plataforma.sincronizacao.conferencia;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.conferencia.SqlConferencia.LinhaResumo;
import br.com.quack.pdv.plataforma.sincronizacao.conferencia.SqlConferencia.Resumo;
import br.com.quack.pdv.plataforma.sincronizacao.nuvem.MotorDeteccao;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Lado da nuvem da conferencia (D10): calcula, com o mesmo SQL da loja, o resumo da replica da empresa; lista as linhas
 * das faixas que divergem; exclui as sobras que a loja nao tem; guarda o resultado para a rota de situacao.
 */
@Service
@Lazy
@Profile("nuvem")
public class ServicoConferencia {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoConferencia.class);
    /** Contexto do Change Tracking do aplicador de lotes (sync-loja): o detector ignora esta exclusao. */
    private static final String COM_CONTEXTO = "WITH CHANGE_TRACKING_CONTEXT (0x53510001) ";

    /** Resumo de uma tabela, como vai no fio. */
    public record ResumoTabela(String tabela, long contagem, long xor, long soma) {}

    /** Resumo de uma faixa da chave. */
    public record ResumoFaixa(int faixa, long contagem, long xor, long soma) {}

    /**
     * Linha da replica. {@code comandoPendente}: ha comando para a loja aplicar nela; {@code quarentena}: ha entrada
     * aberta; {@code semEspelho}: tabela EDITAVEL em que a nuvem nunca viu a linha vir da loja (criada na nuvem).
     */
    public record LinhaNuvem(String chave, long soma, boolean comandoPendente, boolean quarentena, boolean semEspelho) {}

    public record ChavesDaTabela(String tabela, List<String> chaves) {}

    private final JdbcTemplate jdbc;
    private final MotorDeteccao motor;
    private final TransactionOperations tx;
    private volatile SqlConferencia sql;

    public ServicoConferencia(JdbcTemplate jdbc, MotorDeteccao motor, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.motor = motor;
        this.tx = tx;
    }

    private SqlConferencia sql() {
        SqlConferencia s = sql;
        if (s == null) sql = s = new SqlConferencia(jdbc);
        return s;
    }

    private static TabelaSincronizada tabela(String nome) {
        return SqlConferencia.tabelas().stream().filter(t -> t.nome().equals(nome)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tabela fora da conferencia: " + nome));
    }

    public List<ResumoTabela> resumos(String companyId) {
        List<ResumoTabela> r = new ArrayList<>();
        for (TabelaSincronizada t : SqlConferencia.tabelas()) {
            Resumo s = sql().resumo(jdbc, t, companyId);
            r.add(new ResumoTabela(t.nome(), s.contagem(), s.xor(), s.soma()));
        }
        return r;
    }

    public List<ResumoFaixa> faixas(String companyId, String nomeTabela) {
        List<ResumoFaixa> r = new ArrayList<>();
        sql().faixas(jdbc, tabela(nomeTabela), companyId)
                .forEach((f, s) -> r.add(new ResumoFaixa(f, s.contagem(), s.xor(), s.soma())));
        return r;
    }

    public List<LinhaNuvem> linhas(String companyId, String nomeTabela, List<Integer> faixas) {
        TabelaSincronizada t = tabela(nomeTabela);
        Set<String> comando = chaves("SELECT Chave FROM dbo.SyncComandos WHERE CompanyId = ? AND Tabela = ? AND Status = N'pendente'",
                companyId, nomeTabela);
        comando.addAll(chaves("""
                SELECT k.Chave FROM dbo.SyncComandoChaves k JOIN dbo.SyncComandos c ON c.Seq = k.Seq
                 WHERE c.CompanyId = ? AND k.Tabela = ? AND c.Status = N'pendente'
                """, companyId, nomeTabela));
        Set<String> quarentena = chaves("SELECT Chave FROM dbo.SyncQuarentena WHERE CompanyId = ? AND Tabela = ? AND ResolvidoEm IS NULL",
                companyId, nomeTabela);
        Set<String> espelho = t.classe() == ClasseTabela.EDITAVEL
                ? chaves("SELECT Chave FROM dbo.SyncEspelho WHERE CompanyId = ? AND Tabela = ?", companyId, nomeTabela) : null;
        List<LinhaNuvem> r = new ArrayList<>();
        for (LinhaResumo l : sql().linhas(jdbc, t, companyId, faixas)) {
            r.add(new LinhaNuvem(l.chave(), l.soma(), comando.contains(l.chave()), quarentena.contains(l.chave()),
                    espelho != null && !espelho.contains(l.chave())));
        }
        return r;
    }

    private Set<String> chaves(String consulta, String companyId, String tabela) {
        return new HashSet<>(jdbc.queryForList(consulta, String.class, companyId, tabela));
    }

    /**
     * Exclui da replica as linhas que a loja nao tem (D10), filhas antes dos pais. Nao exclui linha com comando pendente,
     * com quarentena aberta (a spec manda manter a linha criada na nuvem ate um administrador resolver) nem, em tabela
     * EDITAVEL, a que a nuvem nunca viu vir da loja (criada na nuvem, aguardando o comando). Devolve quantas excluiu.
     */
    public int excluir(String companyId, List<ChavesDaTabela> pedido) {
        Map<String, List<String>> porTabela = new LinkedHashMap<>();
        for (ChavesDaTabela c : pedido) porTabela.computeIfAbsent(c.tabela(), k -> new ArrayList<>()).addAll(c.chaves());
        int total = 0;
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeExclusao()) {
            List<String> chaves = porTabela.get(t.nome());
            if (chaves == null || t.classe() == ClasseTabela.ACRESCIMO) continue;
            final List<String> alvo = chaves;
            Integer n = tx.execute(s -> excluirTabela(companyId, t, alvo));
            total += n == null ? 0 : n;
        }
        return total;
    }

    private int excluirTabela(String companyId, TabelaSincronizada t, List<String> chaves) {
        Set<String> protegidas = new HashSet<>();
        protegidas.addAll(chaves("SELECT Chave FROM dbo.SyncComandos WHERE CompanyId = ? AND Tabela = ? AND Status = N'pendente'",
                companyId, t.nome()));
        protegidas.addAll(chaves("SELECT Chave FROM dbo.SyncQuarentena WHERE CompanyId = ? AND Tabela = ? AND ResolvidoEm IS NULL",
                companyId, t.nome()));
        // O7: linha de operacao de estoque da nuvem que a loja ainda nao respondeu
        protegidas.addAll(chaves("""
                SELECT k.Chave FROM dbo.SyncComandoChaves k JOIN dbo.SyncComandos c ON c.Seq = k.Seq
                 WHERE c.CompanyId = ? AND k.Tabela = ? AND c.Status = N'pendente'
                """, companyId, t.nome()));
        Set<String> espelho = t.classe() == ClasseTabela.EDITAVEL
                ? chaves("SELECT Chave FROM dbo.SyncEspelho WHERE CompanyId = ? AND Tabela = ?", companyId, t.nome()) : null;
        List<String> pk = sql().chave(t);
        String ligacao = "";
        String empresa;
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
            ligacao = " JOIN dbo.[" + mae.nome() + "] AS M ON M.[" + sql().chave(mae).getFirst() + "] = T.[" + t.colunaLigacao() + "]";
            empresa = "M.[" + mae.colunaEmpresa() + "]";
        } else {
            empresa = "T.[" + t.colunaEmpresa() + "]";
        }
        String where = String.join(" AND ", pk.stream().map(c -> "T.[" + c + "] = ?").toList());
        String regras = autoriaDaNuvem(t);
        int n = 0;
        for (String chave : chaves) {
            if (protegidas.contains(chave) || (espelho != null && !espelho.contains(chave))) continue;
            List<String> partes = MotorDeteccao.partesDaChave(chave);
            if (partes.size() != pk.size()) continue;
            List<Object> args = new ArrayList<>();
            args.add(companyId);
            args.addAll(partes);
            try {
                int linhas = jdbc.update(COM_CONTEXTO + "DELETE T FROM dbo.[" + t.nome() + "] AS T" + ligacao + " WHERE " + empresa
                        + " = ? AND " + where + regras, args.toArray());
                if (linhas > 0) {
                    n += linhas;
                    if (t.usaEspelho()) motor.apagarEspelho(jdbc, t, chave);
                }
            } catch (DataAccessException e) {
                LOG.warn("Conferencia: nao foi possivel excluir {} {}: {}", t.nome(), chave, e.getMostSpecificCause().getMessage());
            }
        }
        return n;
    }

    private static String autoriaDaNuvem(TabelaSincronizada t) {
        StringBuilder sb = new StringBuilder();
        for (TabelaSincronizada.RegraLinha r : t.regras()) {
            if (r.classeDasColunas() == br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna.NUVEM) {
                String literal = r.valor() instanceof Number ? r.valor().toString() : "N'" + r.valor().toString().replace("'", "''") + "'";
                sb.append(" AND NOT (T.[").append(r.coluna()).append("] = ").append(literal).append(")");
            }
        }
        return sb.toString();
    }

    /** Guarda o resultado da conferencia da loja (a situacao da nuvem mostra o ultimo). */
    public void registrarResultado(String companyId, String origem, String resultado, int voltas, String detalheJson) {
        jdbc.update("INSERT INTO dbo.SyncConferencias (CompanyId, Origem, Resultado, Voltas, DetalheJson) VALUES (?, ?, ?, ?, ?)",
                companyId, origem, resultado, voltas, detalheJson);
    }
}
