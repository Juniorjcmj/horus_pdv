package br.com.quack.pdv.produto.importacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.produto.importacao.LeitorCsvProdutos.LinhaCsv;
import br.com.quack.pdv.produto.importacao.MapeadorProdutoLegado.Resultado;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MapeadorProdutoLegadoTest {

    private static Resultado mapear(String codigo) {
        return LeitorCsvProdutos.ler(PlanilhaSintetica.amostra()).linhas().stream()
                .filter(l -> l.valor("Código").equals(codigo))
                .map(MapeadorProdutoLegado::mapear)
                .findFirst()
                .orElseThrow();
    }

    private static Resultado mapearCom(Map<String, String> valores) {
        Map<String, String> v = new HashMap<>();
        v.put(Textos.chaveColuna("Código"), "1");
        v.put(Textos.chaveColuna("Descrição Completa"), "ITEM");
        v.put(Textos.chaveColuna("Unidade Venda"), "un");
        v.put(Textos.chaveColuna("Código NCM"), "12345678");
        v.put(Textos.chaveColuna("Preço Venda Tab.3"), "R$ 1,00");
        valores.forEach((k, x) -> v.put(Textos.chaveColuna(k), x));
        return MapeadorProdutoLegado.mapear(new LinhaCsv(2, v));
    }

    @Test
    void precoComSimboloEMilharVira1234e56() {
        assertThat(mapear("9002").produto().precoVenda()).isEqualByComparingTo("1234.56");
    }

    @Test
    void precoDaTab3NaoDaBase() {
        ProdutoImportado p = mapear("9001").produto();

        assertThat(p.precoVenda()).isEqualByComparingTo("6.90");
        assertThat(p.custoCompra()).isEqualByComparingTo("1.33");
        assertThat(p.custoMedio()).isEqualByComparingTo("1.30");
        assertThat(p.custoSemImposto()).isEqualByComparingTo("0.90");
    }

    @Test
    void numeroInvalidoRecusaCitandoAColuna() {
        Resultado r = mapear("9018");

        assertThat(r.recusada()).isTrue();
        assertThat(r.recusa()).contains("Qtd.Estoque (Física)");
    }

    @Test
    void etiquetaAntigaFicaSemGtinESemAviso() {
        Resultado r = mapear("9001");

        assertThat(r.produto().codigo()).isEqualTo("9001");
        assertThat(r.produto().gtin()).isEqualTo("SEM GTIN");
        assertThat(r.avisos()).noneMatch(a -> a.contains("barras"));
    }

    @Test
    void eanValidoEGravado() {
        assertThat(mapear("9002").produto().gtin()).isEqualTo("7890000000017");
    }

    @Test
    void eanComDigitoErradoViraSemGtinComAvisoDoOriginal() {
        Resultado r = mapear("9003");

        assertThat(r.produto().gtin()).isEqualTo("SEM GTIN");
        assertThat(r.avisos()).anyMatch(a -> a.contains("7890000000018"));
    }

    @Test
    void codigoDeBarrasVazioNaoGeraAviso() {
        assertThat(mapear("9005").avisos()).noneMatch(a -> a.contains("barras"));
    }

    @Test
    void precoZeroImportaComAviso() {
        Resultado r = mapear("9012");

        assertThat(r.produto().precoVenda()).isEqualByComparingTo("0");
        assertThat(r.avisos()).contains("sem preço de venda");
    }

    @Test
    void cfopVazioAssumePeloCst() {
        Resultado cst000 = mapear("9010");
        Resultado cst060 = mapear("9011");

        assertThat(cst000.produto().cfop()).isEqualTo("5102");
        assertThat(cst000.avisos()).anyMatch(a -> a.contains("CFOP"));
        assertThat(cst060.produto().cfop()).isEqualTo("5405");
    }

    @Test
    void saldoNegativoEntraComoZeroComAviso() {
        Resultado r = mapear("9006");

        assertThat(r.produto().saldoInicial()).isEqualByComparingTo("0");
        assertThat(r.avisos()).anyMatch(a -> a.contains("negativo"));
    }

    @Test
    void saldoFracionadoEPreservado() {
        assertThat(mapear("9007").produto().saldoInicial()).isEqualByComparingTo("0.5");
        assertThat(mapear("9008").produto().saldoInicial()).isEqualByComparingTo("12.75");
    }

    @Test
    void unidadeViraMaiuscula() {
        assertThat(mapear("9007").produto().unidade()).isEqualTo("M2");
    }

    @Test
    void m2ComMtNaDescricaoGeraAviso() {
        assertThat(mapear("9014").avisos()).anyMatch(a -> a.contains("M2") && a.contains("MT"));
    }

    @Test
    void localizacaoTiraTracosSobrando() {
        assertThat(mapear("9001").produto().localizacao()).isEqualTo("A - B1 - C - D1");
    }

    @Test
    void codigoAusenteENcmCurtoRecusam() {
        Resultado semCodigo = LeitorCsvProdutos.ler(PlanilhaSintetica.amostra()).linhas().stream()
                .filter(l -> l.valor("Descrição Completa").equals("PRODUTO TESTE SEM CODIGO"))
                .map(MapeadorProdutoLegado::mapear)
                .findFirst()
                .orElseThrow();

        assertThat(semCodigo.recusada()).isTrue();
        assertThat(semCodigo.recusa()).contains("Código");
        assertThat(mapear("9017").recusa()).contains("NCM");
    }

    @Test
    void embalagemZeroOuVaziaVira1() {
        assertThat(mapearCom(Map.of("Qtd.Embalagem", "0")).produto().qtdEmbalagem()).isEqualByComparingTo("1");
        assertThat(mapearCom(Map.of()).produto().qtdEmbalagem()).isEqualByComparingTo("1");
        assertThat(mapearCom(Map.of("Qtd.Embalagem", "12,5")).produto().qtdEmbalagem()).isEqualByComparingTo("12.5");
    }

    @Test
    void markupNegativoDeProdutoAbaixoDoCustoNaoRecusa() {
        Resultado r = mapearCom(Map.of("Markup Praticado%", "-12,5"));

        assertThat(r.recusada()).isFalse();
        assertThat(r.produto().markupPraticado()).isEqualByComparingTo("-12.5");
    }

    @Test
    void pisCofinsVaziosViram99ComAviso() {
        Resultado r = mapearCom(Map.of());

        assertThat(r.produto().cstPis()).isEqualTo("99");
        assertThat(r.produto().cstCofins()).isEqualTo("99");
        assertThat(r.avisos()).anyMatch(a -> a.contains("PIS"));
    }

    @Test
    void fornecedorCaiNoPrimeiroDosRelacionados() {
        Resultado r = mapearCom(Map.of("Fornecedores Relacionados", "FORN UM, FORN DOIS"));

        assertThat(r.produto().fornecedor()).isEqualTo("FORN UM");
    }

    @ParameterizedTest
    @CsvSource({"7890000000017,true", "7890000000018,false", "12345,false", "abcdefgh,false", "96385074,true"})
    void gtinPeloDigitoVerificadorGs1(String texto, boolean esperado) {
        assertThat(Gtin.valido(texto)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource({"'R$ 1.234,56',1234.56", "'0,5',0.5", "'',0", "'  R$ 6,90 ',6.90", "'12',12"})
    void numerosNoFormatoBrasileiro(String texto, String esperado) {
        assertThat(NumerosBr.ler(texto)).isEqualByComparingTo(esperado);
    }
}
