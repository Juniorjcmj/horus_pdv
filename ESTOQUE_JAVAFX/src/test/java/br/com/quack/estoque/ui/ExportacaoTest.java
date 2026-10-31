package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.ProdutoApi.Movimentacao;
import br.com.quack.estoque.domain.Csv;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CSV das movimentações e de cada relatório (colunas da tela, formato brasileiro), filtro por período e etiquetas da entrada. */
class ExportacaoTest {

    private static Movimentacao mov(String tipo, String qtd, String criadoEmUtc, String motivo) {
        return new Movimentacao("m", "p1", "Café Pilão", "1001", tipo, new BigDecimal(qtd), new BigDecimal("12"), new BigDecimal("10"), motivo,
                "Ana", criadoEmUtc);
    }

    @Test
    void movimentacoesFiltradasPorPeriodoEExportadas() {
        List<Movimentacao> todas = List.of(mov("perda", "2", "2026-10-15T14:00:00", "embalagem avariada"),
                mov("perda", "1", "2026-09-30T23:30:00", "vencido"), mov("entrada", "5", "2026-10-20T12:00:00", "reposição"));
        // 30/09 23:30 UTC = 30/09 20:30 em Brasília: fica fora de outubro
        List<Movimentacao> outubro = MovimentacoesView.filtrarPeriodo(todas, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertEquals(2, outubro.size());
        List<Movimentacao> soPerdas = outubro.stream().filter(m -> m.tipo().equals("perda")).toList();
        assertEquals("1 movimentações · quantidade total 2", MovimentacoesView.resumo(soPerdas));
        String csv = Csv.build(MovimentacoesView.cabecalhoCsv(), MovimentacoesView.linhasCsv(soPerdas));
        String[] linhas = csv.split("\r\n");
        assertEquals("Data;Código;Produto;Tipo;Quantidade;Saldo anterior;Saldo novo;Motivo;Usuário", linhas[0]);
        assertEquals("15/10/2026 11:00;1001;Café Pilão;Perda;2;12;10;embalagem avariada;Ana", linhas[1]);
    }

    private static JsonNode json(String s) throws Exception {
        return JsonFiles.MAPPER.readTree(s);
    }

    @Test
    void csvDaCurvaAbcUsaAsColunasDaTela() throws Exception {
        JsonNode itens = json("[{\"classe\":\"A\",\"codigo\":\"1001\",\"nome\":\"Café\",\"quantidade\":100,\"faturamento\":1234.5,"
                + "\"participacao\":61.54,\"participacaoAcumulada\":61.54,\"saldo\":8,\"coberturaDias\":2.5}]");
        var colunas = RelatoriosView.colunasCurva();
        String csv = Csv.build(colunas.stream().map(RelatoriosView.Col::titulo).toList(), RelatoriosView.linhas(colunas, itens));
        String[] l = csv.split("\r\n");
        assertTrue(l[0].startsWith("Classe;Código;Produto;Qtd. vendida;Faturamento (R$)"));
        assertEquals("A;1001;Café;100;1.234,50;61,54;61,54;8;2,5", l[1]);
        String total = RelatoriosView.totalCurva(json("{\"faturamentoTotal\":1300,\"classes\":[{\"classe\":\"A\",\"produtos\":2,\"participacao\":80.5}]}"));
        assertEquals("Faturamento do período R$ 1.300,00 · A: 2 produtos (80,50%)", total);
    }

    @Test
    void csvDeCriticoParadoValidadeEPerdas() throws Exception {
        var critico = RelatoriosView.colunasCritico(true);
        JsonNode c = json("[{\"codigo\":\"1\",\"nome\":\"Leite\",\"fornecedor\":\"Sem fornecedor\",\"saldo\":2,\"minimo\":10,\"faltam\":8,"
                + "\"situacao\":\"abaixo do mínimo\",\"custoReposicao\":12.8}]");
        assertEquals(List.of("1", "Leite", "Sem fornecedor", "2", "10", "8", "abaixo do mínimo", "12,80"), RelatoriosView.linhas(critico, c).get(0));
        // sem valores (estoquista): a coluna de custo some
        assertEquals(critico.size() - 1, RelatoriosView.colunasCritico(false).size());

        var parado = RelatoriosView.colunasParado();
        JsonNode p = json("[{\"codigo\":\"7\",\"nome\":\"Sal\",\"saldo\":20,\"ultimaVenda\":\"2026-08-17T10:00:00-03:00\",\"diasSemVenda\":75,"
                + "\"nuncaVendido\":false,\"ultimaEntrada\":\"2026-07-01T10:00:00+00:00\",\"valorParado\":100.0},"
                + "{\"codigo\":\"8\",\"nome\":\"Novo\",\"saldo\":4,\"ultimaVenda\":null,\"diasSemVenda\":null,\"nuncaVendido\":true,"
                + "\"ultimaEntrada\":null,\"valorParado\":12}]");
        var linhasParado = RelatoriosView.linhas(parado, p);
        assertEquals("75", linhasParado.get(0).get(4));
        assertEquals("100,00", linhasParado.get(0).get(6));
        assertEquals("nunca vendido", linhasParado.get(1).get(3));
        assertEquals("—", linhasParado.get(1).get(4));

        var validade = RelatoriosView.colunasValidade(true);
        JsonNode v = json("[{\"codigo\":\"9\",\"nome\":\"Iogurte\",\"numeroLote\":\"L1\",\"dataValidade\":\"2026-11-05T00:00:00\",\"diasRestantes\":5,"
                + "\"quantidade\":3,\"vencido\":false,\"valor\":9.0},{\"codigo\":\"9\",\"nome\":\"Iogurte\",\"numeroLote\":null,"
                + "\"dataValidade\":\"2026-10-20T00:00:00\",\"diasRestantes\":-11,\"quantidade\":1,\"vencido\":true,\"valor\":3.0}]");
        var lv = RelatoriosView.linhas(validade, v);
        assertEquals("05/11/2026", lv.get(0).get(3));
        assertEquals("VENCIDO (-11)", lv.get(1).get(4));

        var perdas = RelatoriosView.colunasPerdas();
        JsonNode pe = json("[{\"data\":\"2026-10-31T11:20:00\",\"codigo\":\"1\",\"nome\":\"Café\",\"motivo\":\"avaria; caixa molhada\",\"usuario\":\"Ana\","
                + "\"quantidade\":2,\"valor\":4.0}]");
        String csv = Csv.build(perdas.stream().map(RelatoriosView.Col::titulo).toList(), RelatoriosView.linhas(perdas, pe));
        assertEquals("31/10/2026 11:20;1;Café;\"avaria; caixa molhada\";Ana;2;4,00", csv.split("\r\n")[1]);
    }

    @Test
    void etiquetasDaEntradaUmaPorProdutoOuPelaQuantidade() {
        var itens = List.of(new EtiquetaOferta.Recebido("p1", "A", new BigDecimal("12")), new EtiquetaOferta.Recebido("p2", "B", new BigDecimal("2.5")),
                new EtiquetaOferta.Recebido("p1", "A", new BigDecimal("3")), new EtiquetaOferta.Recebido("p3", "C", new BigDecimal("0.2")));
        var g = EtiquetaOferta.agrupar(itens);
        assertEquals(3, g.size(), "o mesmo produto em dois itens vira uma linha");
        assertEquals(1, EtiquetaOferta.copias(g.get("p1"), false));
        assertEquals(15, EtiquetaOferta.copias(g.get("p1"), true));
        assertEquals(3, EtiquetaOferta.copias(g.get("p2"), true), "2,5 arredonda para cima");
        assertEquals(1, EtiquetaOferta.copias(g.get("p3"), true), "no mínimo 1");
    }

    @Test
    void ordenaDepartamentosComSuasSubcategorias() {
        var pai = new br.com.quack.estoque.api.CadastroApi.Categoria("a", "Bebidas", null, null, true, 0);
        var filha = new br.com.quack.estoque.api.CadastroApi.Categoria("b", "Refri", "a", "Bebidas", true, 0);
        var outro = new br.com.quack.estoque.api.CadastroApi.Categoria("c", "Açougue", null, null, true, 0);
        var ordenadas = CadastrosView.ordenar(List.of(filha, pai, outro));
        assertEquals(List.of("Açougue", "Bebidas", "Refri"), ordenadas.stream().map(c -> c.nome()).toList());
        assertThrows(br.com.quack.estoque.domain.DomainException.class, () -> CadastrosView.exigirDoisNiveis(filha));
    }
}
