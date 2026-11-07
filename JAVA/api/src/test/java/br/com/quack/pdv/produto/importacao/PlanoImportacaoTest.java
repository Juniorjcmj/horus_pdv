package br.com.quack.pdv.produto.importacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.produto.importacao.PlanoImportacao.EstadoBanco;
import br.com.quack.pdv.produto.importacao.PlanoImportacao.Item;
import br.com.quack.pdv.produto.importacao.PlanoImportacao.ProdutoExistente;
import br.com.quack.pdv.produto.importacao.RelatorioImportacao.LinhaRelato;
import br.com.quack.pdv.produto.importacao.ResolvedorCategorias.CategoriaExistente;
import br.com.quack.pdv.produto.importacao.ResolvedorCategorias.CategoriaNova;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanoImportacaoTest {

    private static final EstadoBanco VAZIO = new EstadoBanco(List.of(), List.of(), Set.of());

    private static PlanoImportacao planejar(EstadoBanco estado) {
        return PlanoImportacao.montar(LeitorCsvProdutos.ler(PlanilhaSintetica.amostra()).linhas(), estado, "hash");
    }

    private static LinhaRelato recusa(PlanoImportacao plano, String codigo) {
        return plano.relatorio().recusados().stream()
                .filter(r -> r.codigo().equals(codigo))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void amostraEmBancoVazioTemTotaisEsperados() {
        PlanoImportacao plano = planejar(VAZIO);
        RelatorioImportacao.Totais t = plano.relatorio().totais();

        assertThat(t.lidas()).isEqualTo(41);
        assertThat(t.novos()).isEqualTo(36);
        assertThat(t.atualizados()).isZero();
        assertThat(t.recusados()).isEqualTo(5);
        assertThat(t.categoriasNovas()).isEqualTo(8);
        assertThat(t.produtosComSaldo()).isEqualTo(26);
        assertThat(t.quantidadeTotal()).isEqualByComparingTo("603.25");
    }

    @Test
    void gtinRepetidoRecusaASegundaLinha() {
        LinhaRelato r = recusa(planejar(VAZIO), "9004");

        assertThat(r.motivo()).isEqualTo("GTIN já usado pelo código 9002");
    }

    @Test
    void gtinUsadoPorOutroCodigoNoBancoRecusa() {
        EstadoBanco estado = new EstadoBanco(
                List.of(new ProdutoExistente("pr-x", "OUTRO", "7890000000017", null)), List.of(), Set.of());

        LinhaRelato r = recusa(planejar(estado), "9002");

        assertThat(r.motivo()).contains("OUTRO");
    }

    @Test
    void codigoRepetidoNaPlanilhaRecusaASegunda() {
        List<LinhaRelato> recusados = planejar(VAZIO).relatorio().recusados();

        assertThat(recusados).anyMatch(r -> r.codigo().equals("9015") && r.motivo().contains("repetido"));
        assertThat(recusados).anyMatch(r -> r.codigo().isEmpty());
    }

    @Test
    void linhaRecusadaNaoBloqueiaAsDemais() {
        PlanoImportacao plano = planejar(VAZIO);

        assertThat(plano.itens()).hasSize(36);
        assertThat(plano.itens()).extracting(i -> i.produto().codigo()).contains("9001", "9013").doesNotContain("9017");
    }

    @Test
    void resumoFiscalAgrupaPorCstCsosnCfop() {
        List<RelatorioImportacao.ResumoFiscal> resumo = planejar(VAZIO).relatorio().resumoFiscal();

        assertThat(resumo.stream().mapToInt(RelatorioImportacao.ResumoFiscal::quantidade).sum()).isEqualTo(36);
        assertThat(resumo.getFirst().cst()).isEqualTo("060");
        assertThat(resumo.getFirst().csosn()).isEqualTo("500");
        assertThat(resumo.getFirst().cfop()).isEqualTo("5405");
        assertThat(resumo).anyMatch(r -> r.cst().equals("000") && r.csosn().equals("102") && r.cfop().equals("5102"));
    }

    @Test
    void categoriaCriadaUmaVezMesmoComNomesEscritosDiferente() {
        PlanoImportacao plano = planejar(VAZIO);

        List<CategoriaNova> raizes = plano.categoriasNovas().stream().filter(c -> c.paiId() == null).toList();
        assertThat(raizes).extracting(CategoriaNova::nome).containsExactly("CLASSE TESTE A", "CLASSE TESTE B");
        Item a = item(plano, "9001");
        Item igual = item(plano, "9023");
        assertThat(igual.categoriaId()).isEqualTo(a.categoriaId());
    }

    @Test
    void mesmoNomeDeSubGrupoEmPaisDiferentesViraDuasCategorias() {
        PlanoImportacao plano = planejar(VAZIO);

        List<CategoriaNova> acessorios = plano.categoriasNovas().stream()
                .filter(c -> c.nome().equals("ACESSORIOS"))
                .toList();
        assertThat(acessorios).hasSize(2);
        assertThat(acessorios.get(0).paiId()).isNotEqualTo(acessorios.get(1).paiId());
        assertThat(item(plano, "9019").categoriaId()).isNotEqualTo(item(plano, "9020").categoriaId());
    }

    @Test
    void semClasseFicaSemCategoriaComAviso() {
        PlanoImportacao plano = planejar(VAZIO);

        assertThat(item(plano, "9009").categoriaId()).isNull();
        assertThat(plano.relatorio().avisos())
                .anyMatch(a -> a.codigo().equals("9009") && a.motivo().contains("sem Classe"));
    }

    @Test
    void subGrupoSemGrupoFicaNaClasseComAviso() {
        PlanoImportacao plano = planejar(VAZIO);

        assertThat(item(plano, "9022").categoriaId()).isNotNull();
        assertThat(plano.relatorio().avisos())
                .anyMatch(a -> a.codigo().equals("9022") && a.motivo().contains("Sub-Grupo sem Grupo"));
    }

    @Test
    void categoriaExistenteEReaproveitadaSemAcentoNemMaiuscula() {
        EstadoBanco estado = new EstadoBanco(
                List.of(),
                List.of(new CategoriaExistente("cat-1", "classe teste a", null, 5)),
                Set.of());

        PlanoImportacao plano = planejar(estado);

        assertThat(plano.categoriasNovas()).noneMatch(c -> c.nome().equals("CLASSE TESTE A"));
        assertThat(plano.categoriasNovas().getFirst().ordem()).isEqualTo(6);
        assertThat(plano.categoriasNovas()).anyMatch(c -> "cat-1".equals(c.paiId()));
    }

    @Test
    void produtoExistenteEAtualizadoENaoLancaSaldo() {
        EstadoBanco estado = new EstadoBanco(
                List.of(new ProdutoExistente("pr-9", "9001", "SEM GTIN", null)), List.of(), Set.of());

        PlanoImportacao plano = planejar(estado);

        Item i = item(plano, "9001");
        assertThat(i.novo()).isFalse();
        assertThat(i.produtoIdExistente()).isEqualTo("pr-9");
        assertThat(i.lancaSaldo()).isFalse();
        assertThat(plano.relatorio().totais().atualizados()).isEqualTo(1);
        assertThat(plano.relatorio().totais().novos()).isEqualTo(35);
        assertThat(plano.relatorio().totais().produtosComSaldo()).isEqualTo(25);
    }

    @Test
    void codigoJaImportadoNaoLancaSaldoDeNovo() {
        EstadoBanco estado = new EstadoBanco(List.of(), List.of(), Set.of("9001"));

        PlanoImportacao plano = planejar(estado);

        assertThat(item(plano, "9001").novo()).isTrue();
        assertThat(item(plano, "9001").lancaSaldo()).isFalse();
    }

    @Test
    void simularDuasVezesDaOMesmoRelatorioSalvoIdsDeCategoria() {
        List<Object> a = new ArrayList<>(List.of(planejar(VAZIO).relatorio().totais()));
        List<Object> b = new ArrayList<>(List.of(planejar(VAZIO).relatorio().totais()));

        assertThat(a).isEqualTo(b);
    }

    private static Item item(PlanoImportacao plano, String codigo) {
        return plano.itens().stream().filter(i -> i.produto().codigo().equals(codigo)).findFirst().orElseThrow();
    }
}
