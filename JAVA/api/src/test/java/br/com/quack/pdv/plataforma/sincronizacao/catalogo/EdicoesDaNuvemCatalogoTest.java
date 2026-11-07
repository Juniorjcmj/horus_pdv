package br.com.quack.pdv.plataforma.sincronizacao.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.plataforma.sincronizacao.nuvem.MotorDeteccao;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Regras do catalogo usadas pela deteccao de edicoes da nuvem (grupo 4): espelho, autoria, transicoes aceitas. */
class EdicoesDaNuvemCatalogoTest {

    private static TabelaSincronizada t(String nome) {
        return CatalogoSincronizacao.tabela(nome).orElseThrow();
    }

    @Test
    void espelhoSoParaTabelasEditaveisOuComAutoriaDaNuvem() {
        assertThat(t("Produtos").usaEspelho()).isTrue();
        assertThat(t("Empresas").usaEspelho()).isTrue();
        assertThat(t("DocumentosFiscais").usaEspelho()).as("registra a origem da nota").isTrue();
        assertThat(t("FiscalSequencias").usaEspelho()).isTrue();
        assertThat(t("Vendas").usaEspelho()).isFalse();
        assertThat(t("EstoqueMovimentacoes").usaEspelho()).isFalse();
        assertThat(t("AuditLog").usaEspelho()).isFalse();
    }

    @Test
    void notaModelo55EAutoriaDaNuvemEModelo65Nao() {
        assertThat(t("DocumentosFiscais").autoriaDaNuvem(Map.of("Modelo", "55"))).isTrue();
        assertThat(t("DocumentosFiscais").autoriaDaNuvem(Map.of("Modelo", (short) 55))).isTrue();
        assertThat(t("DocumentosFiscais").autoriaDaNuvem(Map.of("Modelo", "65"))).isFalse();
        assertThat(t("Produtos").autoriaDaNuvem(Map.of("Modelo", "55"))).isFalse();
    }

    @Test
    void vendasAceitaCancelamentoVindoDaNuvem() {
        var venda = t("Vendas");
        assertThat(venda.aceiteDaNuvem(Map.of("Status", "cancelada"))).isPresent();
        assertThat(venda.aceiteDaNuvem(Map.of("Status", "estornada"))).isPresent();
        assertThat(venda.aceiteDaNuvem(Map.of("Status", "concluida"))).isEmpty();
        assertThat(venda.aceiteDaNuvem(Map.of("Status", "cancelada")).orElseThrow().colunas()).contains("Status", "CanceladoEm");
        assertThat(t("Produtos").aceiteDaNuvem(Map.of("Status", "cancelada"))).isEmpty();
    }

    @Test
    void tabelasDeOperacaoDeEstoqueAceitamInsercaoETransicoesDaNuvem() {
        assertThat(t("EstoqueMovimentacoes").insercaoDaNuvem()).isTrue();
        assertThat(t("ProdutoLotes").insercaoDaNuvem()).isTrue();
        var notas = t("NotasEntradaArquivo");
        assertThat(notas.insercaoDaNuvem()).isTrue();
        assertThat(notas.transicao("estorno")).isPresent();
        assertThat(notas.transicao("estorno").orElseThrow().destinoAtingido(Map.of("Estornada", "1"))).isTrue();
        assertThat(notas.transicao("estorno").orElseThrow().destinoAtingido(Map.of("Estornada", "0"))).isFalse();
        assertThat(notas.transicao("xml").orElseThrow().colunas()).contains("Identidade", "XmlOriginal", "Origem");
        assertThat(notas.usaEspelho()).isFalse();
        assertThat(t("Vendas").participaDeOperacaoDaNuvem()).isFalse();
    }

    @Test
    void deParaDoFornecedorEEditavel() {
        assertThat(t("MapeamentoProdutoFornecedor").classe()).isEqualTo(ClasseTabela.EDITAVEL);
        assertThat(t("MapeamentoProdutoFornecedor").usaEspelho()).isTrue();
    }

    @Test
    void chaveSerializadaEContextoDoAplicador() {
        assertThat(MotorDeteccao.partesDaChave("emp|65|1|2")).containsExactly("emp", "65", "1", "2");
        assertThat(MotorDeteccao.partesDaChave("a||b")).containsExactly("a", "", "b");
        assertThat(MotorDeteccao.ehContextoSyncLoja(new byte[] {0x53, 0x51, 0x00, 0x01})).isTrue();
        assertThat(MotorDeteccao.ehContextoSyncLoja(new byte[] {0x53, 0x51, 0x00, 0x02})).isFalse();
        assertThat(MotorDeteccao.ehContextoSyncLoja(null)).isFalse();
    }
}
