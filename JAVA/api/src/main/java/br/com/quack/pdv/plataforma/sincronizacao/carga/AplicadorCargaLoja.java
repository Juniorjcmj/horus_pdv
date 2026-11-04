package br.com.quack.pdv.plataforma.sincronizacao.carga;

import br.com.quack.pdv.plataforma.sincronizacao.catalogo.CatalogoSincronizacao;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.ClasseColuna;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.EsquemaJdbc;
import br.com.quack.pdv.plataforma.sincronizacao.catalogo.TabelaSincronizada;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.BlocoTabela;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ColunaSync;
import br.com.quack.pdv.plataforma.sincronizacao.protocolo.ConversorValor;
import br.com.quack.pdv.security.SecretProtector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Grava no banco da loja o que a nuvem mandou na carga inicial e no delta (D5). Roda DENTRO da transacao do chamador
 * (pagina + progresso atomicos). Escreve por upsert (UPDATE e, se nao achou, INSERT), entao repetir uma pagina ou
 * receber no delta uma linha que ja veio na pagina nao duplica. As colunas CIFRADA chegam em claro e saem cifradas com a
 * chave da loja. O Change Tracking ainda esta desligado nesta fase, de proposito: nada disso sobe de volta.
 */
@Component
@Profile("loja")
public class AplicadorCargaLoja {

    private final JdbcTemplate jdbc;
    private final SecretProtector segredos;
    private volatile Map<String, List<String>> chavesPorTabela;
    private volatile Set<String> tabelasComIdentity;

    public AplicadorCargaLoja(JdbcTemplate jdbc, SecretProtector segredos) {
        this.jdbc = jdbc;
        this.segredos = segredos;
    }

    /** Esvazia as tabelas sincronizadas (so numa carga nova): sobras de dados de referencia nao podem ficar. */
    public void esvaziarTabelas() {
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeExclusao()) {
            jdbc.update("DELETE FROM dbo.[" + t.nome() + "]");
        }
    }

    /**
     * Suspende as chaves estrangeiras que apontam para a propria tabela (ex.: Categorias.CategoriaPaiId): numa pagina
     * ordenada por chave o filho pode vir antes do pai. {@link #restaurarChavesAutorreferentes()} valida e religa.
     */
    public void suspenderChavesAutorreferentes() {
        for (String[] fk : chavesAutorreferentes()) {
            jdbc.execute("ALTER TABLE dbo.[" + fk[0] + "] NOCHECK CONSTRAINT [" + fk[1] + "]");
        }
    }

    public void restaurarChavesAutorreferentes() {
        for (String[] fk : chavesAutorreferentes()) {
            jdbc.execute("ALTER TABLE dbo.[" + fk[0] + "] WITH CHECK CHECK CONSTRAINT [" + fk[1] + "]");
        }
    }

    private List<String[]> chavesAutorreferentes() {
        return jdbc.query("""
                SELECT t.name, fk.name FROM sys.foreign_keys fk JOIN sys.tables t ON t.object_id = fk.parent_object_id
                 WHERE fk.parent_object_id = fk.referenced_object_id AND SCHEMA_NAME(t.schema_id) = 'dbo'
                """, (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
    }

    /** Upserts de uma pagina; devolve quantas linhas gravou. */
    public int aplicarLinhas(String nomeTabela, List<ColunaSync> colunas, List<List<String>> linhas) {
        carregarEsquema();
        TabelaSincronizada t = ReferenciasGlobais.daCarga(nomeTabela)
                .orElseThrow(() -> new IllegalArgumentException("Tabela fora da sincronizacao: " + nomeTabela));
        if (linhas.isEmpty()) return 0;
        boolean identity = tabelasComIdentity.contains(t.nome());
        if (identity) jdbc.execute("SET IDENTITY_INSERT dbo.[" + t.nome() + "] ON");
        try {
            for (List<String> linha : linhas) upsert(t, colunas, linha);
        } finally {
            if (identity) jdbc.execute("SET IDENTITY_INSERT dbo.[" + t.nome() + "] OFF");
        }
        return linhas.size();
    }

    /** Delta: upserts na ordem de aplicacao do catalogo e exclusoes na ordem inversa. */
    public void aplicarDelta(List<BlocoTabela> blocos) {
        carregarEsquema();
        Map<String, BlocoTabela> porTabela = new HashMap<>();
        for (BlocoTabela b : blocos) porTabela.put(b.tabela(), b);
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            BlocoTabela b = porTabela.get(t.nome());
            if (b != null) aplicarLinhas(t.nome(), b.colunas(), b.linhas());
        }
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeExclusao()) {
            BlocoTabela b = porTabela.get(t.nome());
            if (b == null) continue;
            String where = String.join(" AND ", chavesPorTabela.get(t.nome()).stream().map(c -> "[" + c + "] = ?").toList());
            for (List<String> chave : b.exclusoes()) {
                jdbc.update("DELETE FROM dbo.[" + t.nome() + "] WHERE " + where, ps -> {
                    for (int i = 0; i < chave.size(); i++) {
                        ColunaSync c = b.colunasChave().get(i);
                        ConversorValor.bind(ps, i + 1, ConversorValor.decodificar(chave.get(i), c.tipo()), c.tipo());
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ linha

    private void upsert(TabelaSincronizada t, List<ColunaSync> colunas, List<String> linha) {
        List<String> pk = chavesPorTabela.get(t.nome());
        List<Object> valores = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) {
            Object v = ConversorValor.decodificar(linha.get(i), colunas.get(i).tipo());
            if (v instanceof String s && t.classeDaColuna(colunas.get(i).nome(), null) == ClasseColuna.CIFRADA) {
                v = segredos.protect(s); // a chave da loja, nao a da nuvem
            }
            valores.add(v);
        }
        List<Integer> indiceSets = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) {
            if (!pk.contains(colunas.get(i).nome())) indiceSets.add(i);
        }
        List<Integer> indiceChave = new ArrayList<>();
        for (String c : pk) {
            int idx = indexOf(colunas, c);
            if (idx < 0) throw new IllegalArgumentException("Chave ausente na linha de " + t.nome() + ": " + c);
            indiceChave.add(idx);
        }
        String where = String.join(" AND ", pk.stream().map(c -> "[" + c + "] = ?").toList());
        int alteradas = 0;
        if (!indiceSets.isEmpty()) {
            String sql = "UPDATE dbo.[" + t.nome() + "] SET "
                    + String.join(", ", indiceSets.stream().map(i -> "[" + colunas.get(i).nome() + "] = ?").toList())
                    + " WHERE " + where;
            List<Integer> ordem = new ArrayList<>(indiceSets);
            ordem.addAll(indiceChave);
            alteradas = jdbc.update(sql, ps -> bind(ps, ordem, colunas, valores));
        } else {
            Integer existe = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.[" + t.nome() + "] WHERE " + where, Integer.class,
                    indiceChave.stream().map(valores::get).toArray());
            alteradas = existe == null ? 0 : existe;
        }
        if (alteradas > 0) return;
        String sql = "INSERT INTO dbo.[" + t.nome() + "] ("
                + String.join(", ", colunas.stream().map(c -> "[" + c.nome() + "]").toList()) + ") VALUES ("
                + "?, ".repeat(colunas.size() - 1) + "?)";
        List<Integer> todas = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) todas.add(i);
        jdbc.update(sql, ps -> bind(ps, todas, colunas, valores));
    }

    private static void bind(java.sql.PreparedStatement ps, List<Integer> ordem, List<ColunaSync> colunas, List<Object> valores)
            throws java.sql.SQLException {
        for (int p = 0; p < ordem.size(); p++) {
            int i = ordem.get(p);
            ConversorValor.bind(ps, p + 1, valores.get(i), colunas.get(i).tipo());
        }
    }

    private static int indexOf(List<ColunaSync> colunas, String nome) {
        for (int i = 0; i < colunas.size(); i++) if (colunas.get(i).nome().equals(nome)) return i;
        return -1;
    }

    private void carregarEsquema() {
        if (chavesPorTabela != null) return;
        chavesPorTabela = new EsquemaJdbc(jdbc).chavesPrimarias();
        tabelasComIdentity = new HashSet<>(jdbc.queryForList(
                "SELECT t.name FROM sys.identity_columns ic JOIN sys.tables t ON t.object_id = ic.object_id", String.class));
    }
}
