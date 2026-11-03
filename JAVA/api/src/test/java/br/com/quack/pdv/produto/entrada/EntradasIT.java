package br.com.quack.pdv.produto.entrada;

import static org.assertj.core.api.Assertions.*;

import br.com.quack.pdv.BancoIT;
import br.com.quack.pdv.contrato.EstadoDoBanco;
import br.com.quack.pdv.produto.entrada.app.EntradaManualService;
import br.com.quack.pdv.produto.entrada.app.NotaEntradaArquivoService;
import br.com.quack.pdv.produto.entrada.web.NfeImportRequests.EntradaManualRequest;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

@SpringBootTest
class EntradasIT extends BancoIT {
    @Autowired EntradaManualService manual;
    @Autowired NotaEntradaArquivoService arquivo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void seed() {
        EstadoDoBanco.por(urlDoBanco("quack"), SQL.getUsername(), SQL.getPassword()).restaurar();
    }

    private EntradaManualRequest pedido(String nome) throws Exception {
        var cenarios =
                mapper.readTree(
                        getClass().getResourceAsStream("/contrato/cenarios/entrada-manual.json"));
        for (var c : cenarios)
            if (c.path("nome").asText().equals(nome))
                return mapper.treeToValue(
                        c.path("requisicao").path("corpo"), EntradaManualRequest.class);
        throw new AssertionError("Cenário não encontrado: " + nome);
    }

    @Test
    void replayDaEntradaDotNetNaoAlteraSaldoNemMovimentacoes() throws Exception {
        BigDecimal antes = saldo();
        int movimentos = movimentos();
        var res =
                manual.confirmar(
                        "empresa-principal",
                        pedido("manual-reenvio-da-operacao-do-seed"),
                        "usr-ct-gerente",
                        "Gil Gerente");
        assertThat(res.notaEntradaId).isEqualTo("ne-ct-manual");
        assertThat(res.produtosAtualizados).isEqualTo(1);
        assertThat(saldo()).isEqualByComparingTo(antes);
        assertThat(movimentos()).isEqualTo(movimentos);
    }

    @Test
    void replayDaNovaEntradaNaoDuplicaEstoqueOuLotes() throws Exception {
        var primeira =
                manual.confirmar(
                        "empresa-principal",
                        pedido("manual-existente-e-novo-sem-codigo"),
                        "usr-ct-gerente",
                        "Gil Gerente");
        BigDecimal saldo = saldo();
        int movimentos = movimentos();
        int lotes = jdbc.queryForObject("SELECT COUNT(*) FROM ProdutoLotes", Integer.class);
        var segunda =
                manual.confirmar(
                        "empresa-principal",
                        pedido("manual-existente-e-novo-sem-codigo"),
                        "usr-ct-gerente",
                        "Gil Gerente");
        assertThat(segunda.notaEntradaId).isEqualTo(primeira.notaEntradaId);
        assertThat(saldo()).isEqualByComparingTo(saldo);
        assertThat(movimentos()).isEqualTo(movimentos);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ProdutoLotes", Integer.class))
                .isEqualTo(lotes);
    }

    @Test
    void zipDoMesIncluiXmlOriginalECsvComBom() throws Exception {
        byte[] zip = arquivo.exportar("empresa-principal", 2026, 1);
        Map<String, byte[]> arquivos = new LinkedHashMap<>();
        try (var z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry())
                arquivos.put(e.getName(), z.readAllBytes());
        }
        assertThat(arquivos).containsKey("resumo.csv");
        assertThat(arquivos.keySet().stream().filter(n -> n.startsWith("xml/")).count())
                .isEqualTo(1);
        byte[] csv = arquivos.get("resumo.csv");
        assertThat(Arrays.copyOf(csv, 3)).containsExactly((byte) 239, (byte) 187, (byte) 191);
        assertThat(new String(csv, StandardCharsets.UTF_8))
                .contains("Data entrada;Tipo;Numero;Serie;")
                .contains("\"Entrada manual\"")
                .contains("\"NF-e\"");
        for (var e : arquivos.entrySet())
            if (e.getKey().startsWith("xml/"))
                assertThat(e.getValue()).isEqualTo(arquivo.xml("empresa-principal", "ne-ct-xml"));
        var gravacao =
                mapper.readTree(
                        getClass()
                                .getResourceAsStream(
                                        "/contrato/gravacoes/nfe-import/exportar-mes-com-notas.json"));
        var esperado = gravacao.path("corpo").path("arquivos");
        assertThat(arquivos).hasSize(esperado.size());
        for (var e : esperado) {
            assertThat(arquivos).containsKey(e.path("nome").asText());
            assertThat(arquivos.get(e.path("nome").asText()))
                    .isEqualTo(e.path("texto").asText().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void falhaPosteriorDesfazProdutosSaldoMovimentosENotaELiberaReserva() throws Exception {
        var r = pedido("manual-existente-e-novo-sem-codigo");
        var ausente = new br.com.quack.pdv.produto.entrada.web.NfeImportRequests.ManualItem();
        ausente.produtoExistenteId = "zz-inexistente";
        ausente.quantidadeCompra = "1";
        ausente.fatorConversao = "1";
        ausente.custoCompra = "2";
        r.itens.add(ausente);
        BigDecimal antes = saldo();
        int movimentos = movimentos();
        int produtos = jdbc.queryForObject("SELECT COUNT(*) FROM Produtos", Integer.class);
        assertThatThrownBy(
                        () ->
                                manual.confirmar(
                                        "empresa-principal", r, "usr-ct-gerente", "Gil Gerente"))
                .hasMessage("Produto zz-inexistente não encontrado.");
        assertThat(saldo()).isEqualByComparingTo(antes);
        assertThat(movimentos()).isEqualTo(movimentos);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM Produtos", Integer.class))
                .isEqualTo(produtos);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM NotasEntradaArquivo WHERE"
                                    + " OperacaoManualId='aaaaaaaabbbbccccdddd000000000001'",
                                Integer.class))
                .isZero();
        // Nova tentativa em outra transação consegue a mesma reserva e confirma integralmente.
        var ok =
                manual.confirmar(
                        "empresa-principal",
                        pedido("manual-existente-e-novo-sem-codigo"),
                        "usr-ct-gerente",
                        "Gil Gerente");
        assertThat(ok.notaEntradaId).startsWith("ne-");
        assertThat(saldo()).isEqualByComparingTo(antes.add(new BigDecimal("12")));
    }

    private BigDecimal saldo() {
        return jdbc.queryForObject(
                "SELECT ProductQnt FROM Produtos WHERE Id='pr-ct-2'", BigDecimal.class);
    }

    @Test
    void novoProdutoPreservaFatorFracionarioDaEntradaManual() throws Exception {
        var cenarios = mapper.readTree(getClass().getResourceAsStream("/contrato/cenarios/entrada-manual.json"));
        EntradaManualRequest r = null;
        for (var c : cenarios) {
            if (c.path("nome").asText().equals("manual-produto-novo-com-fator-fracionario")) {
                r = mapper.treeToValue(c.path("antes").get(0).path("corpo"), EntradaManualRequest.class);
            }
        }
        assertThat(r).isNotNull();
        manual.confirmar("empresa-principal", r, "usr-ct-gerente", "Gil Gerente");
        var linha = jdbc.queryForMap("SELECT ProductQnt,ProductUnitPrice,FatorConversao,QtdEmbalagem FROM Produtos WHERE CompanyId='empresa-principal' AND ProductCode='FRAC-1'");
        assertThat((BigDecimal) linha.get("ProductQnt")).isEqualByComparingTo("1");
        assertThat((BigDecimal) linha.get("ProductUnitPrice")).isEqualByComparingTo("4");
        assertThat((BigDecimal) linha.get("FatorConversao")).isEqualByComparingTo("0.5");
        assertThat((BigDecimal) linha.get("QtdEmbalagem")).isEqualByComparingTo("0.5");
    }

    private int movimentos() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM EstoqueMovimentacoes", Integer.class);
    }
}
