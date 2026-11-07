package br.com.quack.pdv.produto.importacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.quack.pdv.produto.importacao.LeitorCsvProdutos.LinhaCsv;
import br.com.quack.pdv.produto.importacao.LeitorCsvProdutos.Planilha;
import br.com.quack.pdv.shared.NegocioException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class LeitorCsvProdutosTest {

    @Test
    void amostraTem114ColunasEmTodasAsLinhas() {
        Planilha p = LeitorCsvProdutos.ler(PlanilhaSintetica.amostra());

        assertThat(p.colunas()).hasSize(114);
        assertThat(p.linhas()).hasSize(41);
        assertThat(p.linhas()).allSatisfy(l -> assertThat(l.valores()).hasSize(114));
        assertThat(p.linhas().getFirst().numero()).isEqualTo(2);
    }

    @Test
    void amostraNaoTemDadosDoClienteReal() {
        String texto = PlanilhaSintetica.amostraTexto();

        // sem CNPJ (14 digitos seguidos formatados ou nao) na coluna de CNPJ/CPF do fornecedor
        assertThat(LeitorCsvProdutos.ler(PlanilhaSintetica.amostra()).linhas())
                .allSatisfy(l -> assertThat(l.valor("CNPJ/CPF Fornecedor")).isEmpty());
        assertThat(texto).contains("PRODUTO TESTE");
    }

    @Test
    void colunasForaDeOrdemDaoOMesmoResultado() {
        String[] linhas = PlanilhaSintetica.amostraTexto().split("\r\n");
        List<List<String>> tabela = new ArrayList<>();
        for (String l : linhas) {
            tabela.add(new ArrayList<>(List.of(l.split(";", -1))));
        }
        List<Integer> ordem = new ArrayList<>();
        for (int i = 0; i < 114; i++) {
            ordem.add(i);
        }
        Collections.reverse(ordem);
        StringBuilder sb = new StringBuilder();
        for (List<String> linha : tabela) {
            List<String> nova = new ArrayList<>();
            ordem.forEach(i -> nova.add(linha.get(i)));
            sb.append(String.join(";", nova)).append("\r\n");
        }

        Planilha original = LeitorCsvProdutos.ler(PlanilhaSintetica.amostra());
        Planilha trocada = LeitorCsvProdutos.ler(sb.toString().getBytes(StandardCharsets.ISO_8859_1));

        assertThat(trocada.linhas()).hasSameSizeAs(original.linhas());
        for (int i = 0; i < original.linhas().size(); i++) {
            assertThat(trocada.linhas().get(i).valores()).isEqualTo(original.linhas().get(i).valores());
        }
    }

    @Test
    void colunaObrigatoriaAusenteRecusaOArquivoCitandoAColuna() {
        String csv = PlanilhaSintetica.amostraTexto().replace("Preço Venda Tab.3", "Outra Coluna");

        assertThatThrownBy(() -> LeitorCsvProdutos.ler(csv.getBytes(StandardCharsets.ISO_8859_1)))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("Preço Venda Tab.3")
                .satisfies(e -> assertThat(((NegocioException) e).getStatus().value()).isEqualTo(400));
    }

    @Test
    void acentosDoIso88591SaoPreservados() {
        Planilha p = LeitorCsvProdutos.ler(PlanilhaSintetica.amostra());

        LinhaCsv louca = p.linhas().stream()
                .filter(l -> l.valor("Código").equals("9013"))
                .findFirst()
                .orElseThrow();
        assertThat(louca.valor("Descrição Completa")).isEqualTo("BOCAL RECEPTACULO DE LOUÇA E-27");
    }

    @Test
    void utf8ComBomEAceito() {
        String csv = PlanilhaSintetica.amostraTexto();
        byte[] corpo = csv.getBytes(StandardCharsets.UTF_8);
        byte[] comBom = new byte[corpo.length + 3];
        comBom[0] = (byte) 0xEF;
        comBom[1] = (byte) 0xBB;
        comBom[2] = (byte) 0xBF;
        System.arraycopy(corpo, 0, comBom, 3, corpo.length);

        Planilha p = LeitorCsvProdutos.ler(comBom);

        assertThat(p.colunas().getFirst()).isEqualTo("Código");
        assertThat(p.linhas().stream().filter(l -> l.valor("Código").equals("9013")).findFirst().orElseThrow()
                        .valor("Descrição Completa"))
                .isEqualTo("BOCAL RECEPTACULO DE LOUÇA E-27");
    }

    @Test
    void aspasDuplasSaoInterpretadas() {
        String csv = "Código;Descrição Completa;Unidade Venda;Código NCM;Preço Venda Tab.3\r\n"
                + "1;\"PARAFUSO \"\"X\"\"; 3 POL\";UN;12345678;1,00\r\n";

        Planilha p = LeitorCsvProdutos.ler(csv.getBytes(StandardCharsets.ISO_8859_1));

        assertThat(p.linhas().getFirst().valor("Descrição Completa")).isEqualTo("PARAFUSO \"X\"; 3 POL");
    }

    @Test
    void arquivoVazioOuGrandeDemaisRecusa() {
        assertThatThrownBy(() -> LeitorCsvProdutos.ler(new byte[0])).isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> LeitorCsvProdutos.ler(new byte[LeitorCsvProdutos.MAX_BYTES + 1]))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("15 MB");
    }

    @Test
    void maisDeCinquentaMilLinhasRecusa() {
        StringBuilder sb = new StringBuilder("Código;Descrição Completa;Unidade Venda;Código NCM;Preço Venda Tab.3\n");
        for (int i = 0; i <= LeitorCsvProdutos.MAX_LINHAS; i++) {
            sb.append("1;A;UN;12345678;1\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> LeitorCsvProdutos.ler(bytes))
                .isInstanceOf(NegocioException.class)
                .hasMessageContaining("50000");
    }
}
