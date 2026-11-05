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
    private volatile Set<String> protegidas = Set.of();

    /**
     * Recuperacao: linhas ("Tabela|chave") que a loja alterou depois da ultima versao que a nuvem confirmou. A nuvem nao as viu,
     * entao nelas as colunas LOJA (a loja e a autoridade) ficam como estao no banco restaurado; as demais vem da nuvem.
     */
    public void definirProtegidas(Set<String> protegidas) {
        this.protegidas = protegidas == null ? Set.of() : Set.copyOf(protegidas);
    }

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
        return aplicarLinhas(nomeTabela, colunas, linhas, false);
    }

    /**
     * Com {@code recuperacao} o banco ja tem dados (backup restaurado): a nuvem vence nas colunas dela, mas as colunas
     * LOCAL nao sao tocadas, os contadores so crescem e o AuditLog entra sem repetir (ver {@link #upsertRecuperacao}).
     */
    public int aplicarLinhas(String nomeTabela, List<ColunaSync> colunas, List<List<String>> linhas, boolean recuperacao) {
        carregarEsquema();
        TabelaSincronizada t = ReferenciasGlobais.daCarga(nomeTabela)
                .orElseThrow(() -> new IllegalArgumentException("Tabela fora da sincronizacao: " + nomeTabela));
        if (linhas.isEmpty()) return 0;
        boolean identity = tabelasComIdentity.contains(t.nome()) && !recuperacao; // AuditLog na recuperacao: Id novo
        if (identity) jdbc.execute("SET IDENTITY_INSERT dbo.[" + t.nome() + "] ON");
        try {
            for (List<String> linha : linhas) upsert(t, colunas, linha, recuperacao);
        } finally {
            if (identity) jdbc.execute("SET IDENTITY_INSERT dbo.[" + t.nome() + "] OFF");
        }
        return linhas.size();
    }

    /** Delta: upserts na ordem de aplicacao do catalogo e exclusoes na ordem inversa. */
    public void aplicarDelta(List<BlocoTabela> blocos) {
        aplicarDelta(blocos, false);
    }

    public void aplicarDelta(List<BlocoTabela> blocos, boolean recuperacao) {
        carregarEsquema();
        Map<String, BlocoTabela> porTabela = new HashMap<>();
        for (BlocoTabela b : blocos) porTabela.put(b.tabela(), b);
        for (TabelaSincronizada t : CatalogoSincronizacao.ordemDeAplicacao()) {
            BlocoTabela b = porTabela.get(t.nome());
            if (b != null) aplicarLinhas(t.nome(), b.colunas(), b.linhas(), recuperacao);
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

    private void upsert(TabelaSincronizada t, List<ColunaSync> colunas, List<String> linha, boolean recuperacao) {
        List<String> pk = chavesPorTabela.get(t.nome());
        List<Object> valores = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) {
            Object v = ConversorValor.decodificar(linha.get(i), colunas.get(i).tipo());
            if (v instanceof String s && t.classeDaColuna(colunas.get(i).nome(), null) == ClasseColuna.CIFRADA) {
                v = segredos.protect(s); // a chave da loja, nao a da nuvem
            }
            valores.add(v);
        }
        if (recuperacao && "AuditLog".equals(t.nome())) {
            inserirAuditoriaSeNova(colunas, valores);
            return;
        }
        boolean protegida = recuperacao && !protegidas.isEmpty() && protegidas.contains(t.nome() + "|" + chaveTexto(pk, colunas, linha));
        List<Integer> indiceSets = new ArrayList<>();
        for (int i = 0; i < colunas.size(); i++) {
            String c = colunas.get(i).nome();
            if (pk.contains(c)) continue;
            // recuperacao: a coluna LOCAL do banco restaurado e a que vale (ponta propria), so entra na insercao
            if (recuperacao && t.classeDaColuna(c, null) == ClasseColuna.LOCAL) continue;
            if (protegida && t.classeDaColuna(c, null) == ClasseColuna.LOJA) continue;
            indiceSets.add(i);
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
            List<String> sets = new ArrayList<>();
            List<Integer> ordem = new ArrayList<>();
            for (int i : indiceSets) {
                String c = colunas.get(i).nome();
                if (recuperacao && contadorMonotonico(t.nome(), c)) {
                    // numeracao: nunca volta atras (um numero ja reservado na loja nao pode ser reusado)
                    sets.add("[" + c + "] = CASE WHEN [" + c + "] >= ? THEN [" + c + "] ELSE ? END");
                    ordem.add(i);
                } else {
                    sets.add("[" + c + "] = ?");
                }
                ordem.add(i);
            }
            String sql = "UPDATE dbo.[" + t.nome() + "] SET " + String.join(", ", sets) + " WHERE " + where;
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

    private static String chaveTexto(List<String> pk, List<ColunaSync> colunas, List<String> linha) {
        List<String> v = new ArrayList<>();
        for (String c : pk) {
            int i = indexOf(colunas, c);
            v.add(i < 0 ? "" : String.valueOf(linha.get(i)));
        }
        return String.join("|", v);
    }

    /** Colunas que guardam o proximo numero a usar: na recuperacao vale o maior entre a loja e a nuvem. */
static boolean contadorMonotonico(String tabela, String coluna) {
    return ("FiscalSequencias".equals(tabela) && "ProximoNumero".equals(coluna))
            || ("RegrasEmissaoNfce".equals(tabela) && "ContadorVendas".equals(coluna));
}

/**
 * AuditLog na recuperacao: o Id da nuvem nao e o da loja (a nuvem numera por conta propria), entao a linha entra com
 * Id novo, so se ainda nao houver uma igual (mesmo conteudo). Repetir a recuperacao nao duplica.
 */
private void inserirAuditoriaSeNova(List<ColunaSync> colunas, List<Object> valores) {
    List<Integer> dados = new ArrayList<>();
    for (int i = 0; i < colunas.size(); i++) if (!"Id".equals(colunas.get(i).nome())) dados.add(i);
    List<String> iguais = new ArrayList<>();
    List<Integer> ordem = new ArrayList<>(dados);
    for (int i : dados) {
        String c = "[" + colunas.get(i).nome() + "]";
        iguais.add("(A." + c + " = ? OR (A." + c + " IS NULL AND ? IS NULL))");
        ordem.add(i);
        ordem.add(i);
    }
    String sql = "INSERT INTO dbo.AuditLog (" + String.join(", ", dados.stream().map(i -> "[" + colunas.get(i).nome() + "]").toList())
            + ") SELECT " + String.join(", ", dados.stream().map(i -> "?").toList())
            + " WHERE NOT EXISTS (SELECT 1 FROM dbo.AuditLog A WHERE " + String.join(" AND ", iguais) + ")";
    jdbc.update(sql, ps -> bind(ps, ordem, colunas, valores));
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
