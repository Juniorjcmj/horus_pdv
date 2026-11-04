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
    void chaveSerializadaEContextoDoAplicador() {
        assertThat(MotorDeteccao.partesDaChave("emp|65|1|2")).containsExactly("emp", "65", "1", "2");
        assertThat(MotorDeteccao.partesDaChave("a||b")).containsExactly("a", "", "b");
        assertThat(MotorDeteccao.ehContextoSyncLoja(new byte[] {0x53, 0x51, 0x00, 0x01})).isTrue();
        assertThat(MotorDeteccao.ehContextoSyncLoja(new byte[] {0x53, 0x51, 0x00, 0x02})).isFalse();
        assertThat(MotorDeteccao.ehContextoSyncLoja(null)).isFalse();
    }
}
