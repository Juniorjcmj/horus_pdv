package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Guardas do catalogo (D2) provadas com esquemas ficticios, sem banco. */
class GuardaCatalogoTest {

    /** Esquema ficticio de duas tabelas: Pai(Id, CompanyId) e Filho(Id, PaiId). */
    private static class Ficticio implements EsquemaBanco {
        final Map<String, Set<String>> colunas = new LinkedHashMap<>();
        final Map<String, List<String>> pks = new LinkedHashMap<>();
        final List<String[]> fks = new ArrayList<>();

        Ficticio() {
            colunas.put("Pai", Set.of("Id", "CompanyId", "Valor"));
            pks.put("Pai", List.of("Id"));
            colunas.put("Filho", Set.of("Id", "PaiId"));
            pks.put("Filho", List.of("Id"));
            fks.add(new String[] {"Filho", "Pai"});
        }

        @Override public Map<String, Set<String>> colunas() { return colunas; }
        @Override public Map<String, List<String>> chavesPrimarias() { return pks; }
        @Override public List<String[]> chavesEstrangeiras() { return fks; }
    }

    private static Map<String, TabelaSincronizada> catalogoBom() {
        Map<String, TabelaSincronizada> c = new LinkedHashMap<>();
        c.put("Pai", new TabelaSincronizada("Pai", ClasseTabela.EDITAVEL, "CompanyId", null, null,
                Map.of("Valor", ClasseColuna.LOJA), List.of()));
        c.put("Filho", new TabelaSincronizada("Filho", ClasseTabela.LOJA, null, "Pai", "PaiId", Map.of(), List.of()));
        return c;
    }

    @Test
    void catalogoCoerenteNaoTemProblemas() {
        assertThat(GuardaCatalogo.verificar(catalogoBom(), List.of("Pai", "Filho"), new Ficticio())).isEmpty();
    }

    @Test
    void tabelaDoEsquemaSemClassificacaoFalha() {
        Ficticio e = new Ficticio();
        e.colunas.put("Nova", Set.of("Id", "CompanyId"));
        e.pks.put("Nova", List.of("Id"));
        assertThat(GuardaCatalogo.verificar(catalogoBom(), List.of("Pai", "Filho"), e))
                .anyMatch(p -> p.contains("sem classificacao") && p.contains("Nova"));
    }

    @Test
    void tabelasSyncEFlywayNaoPrecisamDeClassificacao() {
        Ficticio e = new Ficticio();
        e.colunas.put("SyncEstado", Set.of("Id"));
        e.colunas.put("flyway_schema_history", Set.of("version"));
        assertThat(GuardaCatalogo.verificar(catalogoBom(), List.of("Pai", "Filho"), e)).isEmpty();
    }

    @Test
    void colunaClassificadaInexistenteFalha() {
        Map<String, TabelaSincronizada> c = catalogoBom();
        c.put("Pai", new TabelaSincronizada("Pai", ClasseTabela.EDITAVEL, "CompanyId", null, null,
                Map.of("Fantasma", ClasseColuna.LOJA), List.of()));
        assertThat(GuardaCatalogo.verificar(c, List.of("Pai", "Filho"), new Ficticio()))
                .anyMatch(p -> p.contains("Pai.Fantasma"));
    }

    @Test
    void tabelaSincronizadaSemChavePrimariaFalha() {
        Ficticio e = new Ficticio();
        e.pks.remove("Filho");
        assertThat(GuardaCatalogo.verificar(catalogoBom(), List.of("Pai", "Filho"), e))
                .anyMatch(p -> p.contains("sem chave primaria") && p.contains("Filho"));
    }

    @Test
    void tabelaForaSemChavePrimariaNaoFalha() {
        Ficticio e = new Ficticio();
        e.pks.remove("Filho");
        Map<String, TabelaSincronizada> c = catalogoBom();
        c.put("Filho", new TabelaSincronizada("Filho", ClasseTabela.FORA, null, null, null, Map.of(), List.of()));
        assertThat(GuardaCatalogo.verificar(c, List.of("Pai"), e)).isEmpty();
    }

    @Test
    void ordemQueViolaChaveEstrangeiraFalha() {
        assertThat(GuardaCatalogo.verificar(catalogoBom(), List.of("Filho", "Pai"), new Ficticio()))
                .anyMatch(p -> p.contains("Filho -> Pai"));
    }

    @Test
    void filhaComMaeOuLigacaoInvalidaFalha() {
        Map<String, TabelaSincronizada> c = catalogoBom();
        c.put("Filho", new TabelaSincronizada("Filho", ClasseTabela.LOJA, null, "Pai", "NaoExiste", Map.of(), List.of()));
        assertThat(GuardaCatalogo.verificar(c, List.of("Pai", "Filho"), new Ficticio()))
                .anyMatch(p -> p.contains("Filho.NaoExiste"));
    }

    @Test
    void regraPorLinhaTrocaAClasseDasColunas() {
        TabelaSincronizada t = CatalogoSincronizacao.tabela("DocumentosFiscais").orElseThrow();
        assertThat(t.classeDaColuna("NumeroNf", Map.of("Modelo", 65))).isEqualTo(ClasseColuna.LOJA);
        assertThat(t.classeDaColuna("NumeroNf", Map.of("Modelo", 55))).isEqualTo(ClasseColuna.NUVEM);
        assertThat(t.classeDaColuna("Status", Map.of("Modelo", 65))).isEqualTo(ClasseColuna.NUVEM);
        TabelaSincronizada seq = CatalogoSincronizacao.tabela("FiscalSequencias").orElseThrow();
        assertThat(seq.classeDaColuna("ProximoNumero", Map.of("Modelo", (short) 55))).isEqualTo(ClasseColuna.NUVEM);
        assertThat(seq.classeDaColuna("ProximoNumero", Map.of("Modelo", 65))).isEqualTo(ClasseColuna.LOJA);
    }

    @Test
    void classificacaoDeColunasForaDoPadrao() {
        var produtos = CatalogoSincronizacao.tabela("Produtos").orElseThrow();
        assertThat(produtos.classeDaColuna("ProductQnt", null)).isEqualTo(ClasseColuna.LOJA);
        assertThat(produtos.classeDaColuna("CustoMedio", null)).isEqualTo(ClasseColuna.EDITAVEL);
        assertThat(CatalogoSincronizacao.tabela("Clientes").orElseThrow().classeDaColuna("SaldoDevedor", null))
                .isEqualTo(ClasseColuna.LOJA);
        assertThat(CatalogoSincronizacao.tabela("RegrasEmissaoNfce").orElseThrow().classeDaColuna("ContadorVendas", null))
                .isEqualTo(ClasseColuna.LOJA);
        assertThat(CatalogoSincronizacao.tabela("Usuarios").orElseThrow().classeDaColuna("LockoutEnd", null))
                .isEqualTo(ClasseColuna.LOCAL);
        assertThat(CatalogoSincronizacao.tabela("Empresas").orElseThrow().classeDaColuna("CscCifrado", null))
                .isEqualTo(ClasseColuna.CIFRADA);
    }

    @Test
    void filhasSemEmpresaDeclaramAMae() {
        for (String n : List.of("VendaItens", "PedidoItens", "OrdemCompraItens", "PromocaoProdutos")) {
            var t = CatalogoSincronizacao.tabela(n).orElseThrow();
            assertThat(t.mae()).as(n).isNotNull();
            assertThat(t.colunaLigacao()).as(n).isNotNull();
            assertThat(t.colunaEmpresa()).as(n).isNull();
        }
    }

    @Test
    void ordemDeExclusaoEAInversaDaDeAplicacao() {
        var ap = CatalogoSincronizacao.ordemDeAplicacao();
        var ex = CatalogoSincronizacao.ordemDeExclusao();
        assertThat(ex).hasSameSizeAs(ap);
        assertThat(ex.get(0)).isEqualTo(ap.get(ap.size() - 1));
    }
}
