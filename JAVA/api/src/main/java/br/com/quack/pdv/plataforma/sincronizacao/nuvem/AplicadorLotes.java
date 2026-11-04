package br.com.quack.pdv.plataforma.sincronizacao.nuvem;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseTabela;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.BlocoTabela;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ConversorValor;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.TipoColuna;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aplica um lote na nuvem (D4). Deve rodar DENTRO da transacao do chamador: upserts na ordem do catalogo, exclusoes na
 * ordem inversa, tudo com {@code WITH CHANGE_TRACKING_CONTEXT} de {@code sync-loja} (o detector da nuvem ignora o que
 * o proprio aplicador escreveu). Escopo: toda linha precisa ser da empresa do token (ou da mae dela).
 */
@Component
@org.springframework.context.annotation.Lazy
@Profile("nuvem")
public class AplicadorLotes {

    /** Contexto do Change Tracking das escritas do aplicador de lotes ("SQ" 0001). */
    public static final String CONTEXTO_SYNC_LOJA = "0x53510001";
    private static final String COM_CONTEXTO = "WITH CHANGE_TRACKING_CONTEXT (" + CONTEXTO_SYNC_LOJA + ") ";

    public record Resumo(int upserts, int exclusoes, int sobrasRemovidas) {}

    private record Linha(List<ColunaSync> colunas, Map<String, Object> valores) {}

    private record Chave(List<String> colunas, List<Object> valores, List<TipoColuna> tipos) {
        String texto() {
            List<String> p = new ArrayList<>();
            for (Object v : valores) p.add(String.valueOf(v));
            return String.join("|", p);
        }
    }

    private final JdbcTemplate jdbc;
    private final List<DeteccaoInline> deteccao;
    private volatile Map<String, Set<String>> colunasPorTabela;
    private volatile Map<String, List<String>> chavesPorTabela;

    public AplicadorLotes(JdbcTemplate jdbc, List<DeteccaoInline> deteccao) {
        this.jdbc = jdbc;
        this.deteccao = deteccao;
    }

    public Resumo aplicar(String companyId, List<BlocoTabela> blocos, boolean completo) {
        carregarEsquema();
        Map<String, List<Linha>> upserts = new LinkedHashMap<>();
        Map<String, List<List<Object>>> exclusoes = new LinkedHashMap<>();
        Map<String, List<ColunaSync>> colunasChave = new LinkedHashMap<>();
        for (BlocoTabela b : blocos) {
            TabelaSincronizada t = CatalogoSincronizacao.tabela(b.tabela()).filter(TabelaSincronizada::sincroniza)
                    .orElseThrow(() -> new LoteRecusadoException("Tabela fora da sincronizacao: " + b.tabela()));
            for (List<String> valores : b.linhas()) {
                upserts.computeIfAbsent(t.nome(), k -> new ArrayList<>()).add(linha(t, b.colunas(), valores));
            }
            for (List<String> chave : b.exclusoes()) {
                List<Object> v = new ArrayList<>();
                for (int i = 0; i < chave.size(); i++) v.add(ConversorValor.decodificar(chave.get(i), b.colunasChave().get(i).tipo()));
                exclusoes.computeIfAbsent(t.nome(), k -> new ArrayList<>()).add(v);
                colunasChave.put(t.nome(), b.colunasChave());
            }
        }
        int nUpserts = 0;
        int nExclusoes = 0;
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            for (Linha l : upserts.getOrDefault(t.nome(), List.of())) {
                if (upsert(companyId, t, l)) nUpserts++;
            }
        }
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeExclusao()) {
            List<List<Object>> chaves = exclusoes.getOrDefault(t.nome(), List.of());
            for (List<Object> valores : chaves) {
                if (excluir(companyId, t, colunasChave.get(t.nome()), valores)) nExclusoes++;
            }
        }
        int sobras = 0;
        if (completo) {
            for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeExclusao()) {
                sobras += removerSobras(companyId, t, upserts.getOrDefault(t.nome(), List.of()));
            }
        }
        return new Resumo(nUpserts, nExclusoes, sobras);
    }

    // ------------------------------------------------------------------ linhas

    private Linha linha(TabelaSincronizada t, List<ColunaSync> colunas, List<String> valores) {
        Set<String> validas = colunasPorTabela.get(t.nome());
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < colunas.size(); i++) {
            ColunaSync c = colunas.get(i);
            if (!validas.contains(c.nome())) {
                throw new LoteRecusadoException("Coluna inexistente: " + t.nome() + "." + c.nome());
            }
            mapa.put(c.nome(), ConversorValor.decodificar(valores.get(i), c.tipo()));
        }
        return new Linha(colunas, mapa);
    }

    private boolean upsert(String companyId, TabelaSincronizada t, Linha l) {
        if (t.classe() == ClasseTabela.ACRESCIMO) return inserirAuditoria(companyId, t, l);
        List<String> pk = chavesPorTabela.get(t.nome());
        Chave chave = chaveDaLinha(t, pk, l);
        String doLote = empresaDaLinha(t, l);
        if (!companyId.equals(doLote)) {
            throw new LoteRecusadoException("Linha de outra empresa recusada em " + t.nome() + " (" + chave.texto() + ").");
        }
        List<String> existente = donoExistente(t, chave);
        boolean existe = !existente.isEmpty();
        if (existe && !companyId.equals(existente.getFirst())) {
            throw new LoteRecusadoException("Linha existente de outra empresa em " + t.nome() + " (" + chave.texto() + ").");
        }
        deteccao.forEach(d -> d.antes(companyId, t, l.valores(), existe));
        if (existe) atualizar(t, l, pk, chave); else inserir(t, l);
        deteccao.forEach(d -> d.depois(companyId, t, l.valores()));
        return true;
    }

    private void inserir(TabelaSincronizada t, Linha l) {
        List<ColunaSync> cols = new ArrayList<>();
        for (ColunaSync c : l.colunas()) {
            // CIFRADA fica vazia ate a nuvem preencher (D4); a loja nem a envia
            if (t.classeDaColuna(c.nome(), l.valores()) != ClasseColuna.CIFRADA) cols.add(c);
        }
        String sql = COM_CONTEXTO + "INSERT INTO dbo.[" + t.nome() + "] (" + juntar(cols, "[%s]", ", ") + ") VALUES ("
                + "?, ".repeat(cols.size() - 1) + "?)";
        executar(sql, cols, l.valores());
    }

    private void atualizar(TabelaSincronizada t, Linha l, List<String> pk, Chave chave) {
        List<ColunaSync> sets = new ArrayList<>();
        for (ColunaSync c : l.colunas()) {
            if (pk.contains(c.nome())) continue;
            ClasseColuna cl = t.classeDaColuna(c.nome(), l.valores());
            if (cl == ClasseColuna.LOJA || cl == ClasseColuna.EDITAVEL) sets.add(c); // NUVEM, CIFRADA e LOCAL so no INSERT
        }
        if (sets.isEmpty()) return;
        List<Object> args = new ArrayList<>();
        List<TipoColuna> tipos = new ArrayList<>();
        for (ColunaSync c : sets) { args.add(l.valores().get(c.nome())); tipos.add(c.tipo()); }
        args.addAll(chave.valores());
        tipos.addAll(chave.tipos());
        String sql = COM_CONTEXTO + "UPDATE dbo.[" + t.nome() + "] SET " + juntar(sets, "[%s] = ?", ", ") + " WHERE "
                + String.join(" AND ", pk.stream().map(c -> "[" + c + "] = ?").toList());
        executarArgs(sql, args, tipos);
    }

    private boolean excluir(String companyId, TabelaSincronizada t, List<ColunaSync> cols, List<Object> valores) {
        if (t.classe() == ClasseTabela.ACRESCIMO) return false; // historico de auditoria nao se apaga
        List<String> pk = chavesPorTabela.get(t.nome());
        Chave chave = new Chave(pk, valores, cols.stream().map(ColunaSync::tipo).toList());
        List<String> dono = donoExistente(t, chave);
        if (dono.isEmpty()) return false;
        if (!companyId.equals(dono.getFirst())) {
            throw new LoteRecusadoException("Exclusao de linha de outra empresa recusada em " + t.nome() + ".");
        }
        String sql = COM_CONTEXTO + "DELETE FROM dbo.[" + t.nome() + "] WHERE "
                + String.join(" AND ", pk.stream().map(c -> "[" + c + "] = ?").toList());
        executarArgs(sql, chave.valores(), chave.tipos());
        return true;
    }

    /** AuditLog (D4): insere com o IDENTITY da nuvem e guarda o mapa; linha ja mapeada e ignorada. */
    private boolean inserirAuditoria(String companyId, TabelaSincronizada t, Linha l) {
        Object doLote = l.valores().get("CompanyId");
        if (!companyId.equals(doLote)) throw new LoteRecusadoException("AuditLog de outra empresa recusado.");
        Object idLoja = l.valores().get("Id");
        if (idLoja == null) throw new LoteRecusadoException("AuditLog sem Id.");
        Integer ja = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.SyncAuditoriaMapa WHERE CompanyId = ? AND IdLoja = ?",
                Integer.class, companyId, idLoja);
        if (ja != null && ja > 0) return false;
        List<ColunaSync> cols = l.colunas().stream().filter(c -> !c.nome().equals("Id")).toList();
        String sql = COM_CONTEXTO + "INSERT INTO dbo.[AuditLog] (" + juntar(cols, "[%s]", ", ")
                + ") OUTPUT INSERTED.Id VALUES (" + "?, ".repeat(cols.size() - 1) + "?)";
        Long idNuvem = jdbc.query(sql, ps -> bindTudo(ps, cols, l.valores()), rs -> rs.next() ? rs.getLong(1) : null);
        jdbc.update("INSERT INTO dbo.SyncAuditoriaMapa (CompanyId, IdLoja, IdNuvem) VALUES (?, ?, ?)",
                companyId, idLoja, idNuvem);
        return true;
    }

    // ------------------------------------------------------------------ escopo

    private String empresaDaLinha(TabelaSincronizada t, Linha l) {
        if (!t.filhaSemEmpresa()) {
            Object v = l.valores().get(t.colunaEmpresa());
            return v == null ? null : v.toString();
        }
        TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
        String maePk = chavesPorTabela.get(mae.nome()).getFirst();
        Object ligacao = l.valores().get(t.colunaLigacao());
        List<String> r = jdbc.query("SELECT [" + mae.colunaEmpresa() + "] FROM dbo.[" + mae.nome() + "] WHERE [" + maePk + "] = ?",
                (rs, i) -> rs.getString(1), ligacao);
        if (r.isEmpty()) throw new LoteRecusadoException("Linha mae ausente para " + t.nome() + " (" + ligacao + ").");
        return r.getFirst();
    }

    /** Lista vazia = a linha nao existe; senao a empresa dela (pode ser nula). */
    private List<String> donoExistente(TabelaSincronizada t, Chave chave) {
        List<String> pk = chave.colunas();
        String where = String.join(" AND ", pk.stream().map(c -> "f.[" + c + "] = ?").toList());
        String sql;
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
            String maePk = chavesPorTabela.get(mae.nome()).getFirst();
            sql = "SELECT m.[" + mae.colunaEmpresa() + "] FROM dbo.[" + t.nome() + "] f JOIN dbo.[" + mae.nome()
                    + "] m ON m.[" + maePk + "] = f.[" + t.colunaLigacao() + "] WHERE " + where;
        } else {
            sql = "SELECT f.[" + t.colunaEmpresa() + "] FROM dbo.[" + t.nome() + "] f WHERE " + where;
        }
        return jdbc.query(sql, ps -> bindArgs(ps, chave.valores(), chave.tipos()), (rs, i) -> rs.getString(1));
    }

    private Chave chaveDaLinha(TabelaSincronizada t, List<String> pk, Linha l) {
        List<Object> valores = new ArrayList<>();
        List<TipoColuna> tipos = new ArrayList<>();
        for (String c : pk) {
            ColunaSync col = l.colunas().stream().filter(x -> x.nome().equals(c)).findFirst()
                    .orElseThrow(() -> new LoteRecusadoException("Chave ausente na linha de " + t.nome() + ": " + c));
            valores.add(l.valores().get(c));
            tipos.add(col.tipo());
        }
        return new Chave(pk, valores, tipos);
    }

    // ------------------------------------------------------------------ retrato completo (D5)

    /** Exclui da empresa, em tabela LOJA, o que o retrato nao trouxe. Linhas de autoria da nuvem (regra) ficam. */
    private int removerSobras(String companyId, TabelaSincronizada t, List<Linha> recebidas) {
        if (t.classe() != ClasseTabela.LOJA) return 0; // EDITAVEL: linha so da nuvem pode ser comando pendente (grupo 4/6)
        List<String> pk = chavesPorTabela.get(t.nome());
        Set<String> trazidas = new HashSet<>();
        for (Linha l : recebidas) trazidas.add(chaveDaLinha(t, pk, l).texto());
        List<String> regraCols = t.regras().stream().map(r -> r.coluna()).distinct().toList();
        List<String> select = new ArrayList<>(pk);
        select.addAll(regraCols);
        String sql;
        if (t.filhaSemEmpresa()) {
            TabelaSincronizada mae = CatalogoSincronizacao.tabela(t.mae()).orElseThrow();
            String maePk = chavesPorTabela.get(mae.nome()).getFirst();
            sql = "SELECT " + juntarNomes(select, "f.[%s]") + " FROM dbo.[" + t.nome() + "] f JOIN dbo.[" + mae.nome()
                    + "] m ON m.[" + maePk + "] = f.[" + t.colunaLigacao() + "] WHERE m.[" + mae.colunaEmpresa() + "] = ?";
        } else {
            sql = "SELECT " + juntarNomes(select, "f.[%s]") + " FROM dbo.[" + t.nome() + "] f WHERE f.[" + t.colunaEmpresa() + "] = ?";
        }
        List<Map<String, Object>> existentes = jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int c = 0; c < select.size(); c++) m.put(select.get(c), rs.getObject(c + 1));
            return m;
        }, companyId);
        int removidas = 0;
        for (Map<String, Object> e : existentes) {
            List<String> partes = new ArrayList<>();
            List<Object> valores = new ArrayList<>();
            for (String c : pk) { partes.add(String.valueOf(e.get(c))); valores.add(e.get(c)); }
            if (trazidas.contains(String.join("|", partes))) continue;
            boolean autoriaDaNuvem = t.regras().stream().anyMatch(r -> e.get(r.coluna()) != null
                    && r.valor().toString().equals(e.get(r.coluna()).toString()));
            if (autoriaDaNuvem) continue;
            String del = COM_CONTEXTO + "DELETE FROM dbo.[" + t.nome() + "] WHERE "
                    + String.join(" AND ", pk.stream().map(c -> "[" + c + "] = ?").toList());
            jdbc.update(del, valores.toArray());
            removidas++;
        }
        return removidas;
    }

    // ------------------------------------------------------------------ SQL

    private void executar(String sql, List<ColunaSync> cols, Map<String, Object> valores) {
        jdbc.update(sql, ps -> bindTudo(ps, cols, valores));
    }

    private void executarArgs(String sql, List<Object> args, List<TipoColuna> tipos) {
        jdbc.update(sql, ps -> bindArgs(ps, args, tipos));
    }

    private static void bindTudo(PreparedStatement ps, List<ColunaSync> cols, Map<String, Object> valores) throws SQLException {
        for (int i = 0; i < cols.size(); i++) bind(ps, i + 1, valores.get(cols.get(i).nome()), cols.get(i).tipo());
    }

    private static void bindArgs(PreparedStatement ps, List<Object> args, List<TipoColuna> tipos) throws SQLException {
        for (int i = 0; i < args.size(); i++) bind(ps, i + 1, args.get(i), tipos.get(i));
    }

    private static void bind(PreparedStatement ps, int i, Object v, TipoColuna tipo) throws SQLException {
        if (v == null) ps.setNull(i, tipoNulo(tipo)); else ps.setObject(i, v);
    }

    private static int tipoNulo(TipoColuna t) {
        return switch (t) {
            case TEXTO -> Types.NVARCHAR;
            case INTEIRO -> Types.BIGINT;
            case DECIMAL -> Types.DECIMAL;
            case REAL -> Types.DOUBLE;
            case BOOLEANO -> Types.BIT;
            case DATA -> Types.DATE;
            case HORA -> Types.TIME;
            case DATAHORA -> Types.TIMESTAMP;
            case DATAHORA_OFFSET -> ConversorValor.SQL_DATETIMEOFFSET;
            case BINARIO -> Types.VARBINARY;
        };
    }

    private static String juntar(List<ColunaSync> cols, String formato, String sep) {
        return String.join(sep, cols.stream().map(c -> formato.formatted(c.nome())).toList());
    }

    private static String juntarNomes(List<String> nomes, String formato) {
        return String.join(", ", nomes.stream().map(n -> formato.formatted(n)).toList());
    }

    private void carregarEsquema() {
        if (colunasPorTabela != null) return;
        EsquemaJdbc esquema = new EsquemaJdbc(jdbc);
        chavesPorTabela = esquema.chavesPrimarias();
        colunasPorTabela = esquema.colunas();
    }
}
