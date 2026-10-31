package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.falha;
import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.ApiException;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Relatórios de compra contra a API real, com vendas antigas inseridas por SQL (docker exec): reposição por giro,
 * mínimo sem venda, curva ABC, parado, perdas e restrição de perfil.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RelatoriosE2ETest {
    @TempDir
    static Path home;
    @TempDir
    static Path homeEstoquista;

    E2eSupport e;
    String companyId;
    String catId;
    Produto giro;       // vendeu 60 em 30 dias, saldo 6, min 10, max 40
    Produto abaixoMin;  // sem venda, saldo 2, min 10
    Produto parado;     // 20 un, custo 5,00, ultima venda ha 75 dias
    Produto nunca;      // com estoque, nunca vendido
    Produto abcX;       // faturamento 800
    Produto abcY;       // 150
    Produto abcZ;       // 50

    @BeforeAll
    void preparar() {
        e = new E2eSupport(home);
        e.loginAdmin();
        companyId = e.ctx.session.user().companyId();
        catId = e.ctx.cadastros.salvarCategoria(null, "E2E Relatorios " + e.sufixo, null, true);
        giro = e.criarProduto("Rel Giro", "RGI", "6", "2,00", "5,00", catId, "10", "40");
        abaixoMin = e.criarProduto("Rel Abaixo", "RAB", "2", "1,50", "3,00", catId, "10", "0");
        parado = e.criarProduto("Rel Parado", "RPA", "20", "5,00", "9,00", catId, "0", "0");
        nunca = e.criarProduto("Rel Nunca", "RNU", "3", "3,00", "6,00", catId, "0", "0");
        abcX = e.criarProduto("Rel AbcX", "RAX", "100", "1,00", "8,00", catId, "0", "0");
        abcY = e.criarProduto("Rel AbcY", "RAY", "100", "1,00", "1,50", catId, "0", "0");
        abcZ = e.criarProduto("Rel AbcZ", "RAZ", "100", "1,00", "0,50", catId, "0", "0");
        e.ctx.produtos.ajustar(nunca.id(), "entrada", BigDecimal.ONE, "recebimento", null, null);
        venda(giro, 40, new BigDecimal("200.00"), 10);
        venda(giro, 20, new BigDecimal("100.00"), 3);
        venda(parado, 1, new BigDecimal("9.00"), 75);
        venda(abcX, 100, new BigDecimal("800.00"), 5);
        venda(abcY, 100, new BigDecimal("150.00"), 6);
        venda(abcZ, 100, new BigDecimal("50.00"), 7);
        // Venda cancelada nao conta (nem para o giro nem para a curva).
        venda(abcZ, 500, new BigDecimal("9999.00"), 4, "cancelada");
    }

    private void venda(Produto p, int qtd, BigDecimal total, int diasAtras) {
        venda(p, qtd, total, diasAtras, "finalizada");
    }

    private void venda(Produto p, int qtd, BigDecimal total, int diasAtras, String status) {
        String id = "e2e-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        E2eSupport.sql("INSERT INTO Vendas (Id, CompanyId, SaleNumber, CustomerName, CustomerCpf, PaymentType, OperatorName, "
                + "SaleDate, TotalAmount, Status) VALUES ('" + id + "', '" + companyId + "', 'E2E-" + id.substring(4, 20)
                + "', N'Consumidor', N'-', N'Dinheiro', N'Teste', DATEADD(day, -" + diasAtras
                + ", SYSDATETIMEOFFSET()), " + total.toPlainString() + ", '" + status + "');"
                + " INSERT INTO VendaItens (Id, VendaId, ProductCode, ProductName, Quantity, UnitPrice, ItemTotal, Desconto) VALUES ('"
                + id + "i', '" + id + "', '" + p.codigo() + "', N'" + p.nome() + "', " + qtd + ", "
                + total.divide(BigDecimal.valueOf(qtd), 4, java.math.RoundingMode.HALF_UP).toPlainString() + ", "
                + total.toPlainString() + ", 0);");
    }

    private JsonNode itemDe(JsonNode itens, Produto p) {
        for (JsonNode i : itens) {
            if (i.get("produtoId").asText().equals(p.id())) {
                return i;
            }
        }
        return null;
    }

    @Test
    @Order(1)
    void reposicaoPorGiro() {
        JsonNode r = e.ctx.relatorios.reposicao(30, 15, catId);
        JsonNode i = itemDe(r.get("itens"), giro);
        assertNotNull(i, "produto com giro entra na sugestao");
        assertEquals(24.0, i.get("sugerido").asDouble(), 0.001, "2/dia x 15 = 30, menos 6 em estoque");
        assertEquals(3.0, i.get("coberturaDias").asDouble(), 0.05);
        assertEquals(2.0, i.get("mediaDiaria").asDouble(), 0.001);
        assertEquals(48.0, i.get("custoEstimado").asDouble(), 0.001);
        log("giro: " + i);
    }

    @Test
    @Order(2)
    void abaixoDoMinimoSemVendaCompletaOMinimo() {
        JsonNode i = itemDe(e.ctx.relatorios.reposicao(30, 15, catId).get("itens"), abaixoMin);
        assertNotNull(i);
        assertEquals(8.0, i.get("sugerido").asDouble(), 0.001);
        assertEquals("abaixo do mínimo", i.get("motivo").asText());
        assertEquals("Sem fornecedor", i.get("fornecedor").asText());
        assertTrue(i.get("fornecedorId").isNull());
        // produto sem venda e acima do minimo nao entra
        assertNull(itemDe(e.ctx.relatorios.reposicao(30, 15, catId).get("itens"), nunca));
    }

    @Test
    @Order(3)
    void curvaAbcDoDepartamento() {
        JsonNode r = e.ctx.relatorios.curvaAbc(LocalDate.now().minusDays(30), LocalDate.now(), catId);
        double total = r.get("faturamentoTotal").asDouble();
        // giro (300) tambem vende na categoria; X/Y/Z somam 1000; a cancelada (9999) e a venda de 75 dias ficam de fora.
        assertEquals(1300.0, total, 0.01, "venda cancelada e a venda antiga ficam de fora");
        JsonNode x = itemDe(r.get("itens"), abcX);
        JsonNode y = itemDe(r.get("itens"), abcY);
        JsonNode z = itemDe(r.get("itens"), abcZ);
        assertEquals("A", x.get("classe").asText());
        assertEquals(800.0, x.get("faturamento").asDouble(), 0.01);
        assertEquals(61.54, x.get("participacao").asDouble(), 0.01);
        assertEquals(r.get("itens").get(0).get("produtoId").asText(), abcX.id(), "ordenado do maior para o menor");
        assertEquals("C", z.get("classe").asText());
        assertTrue(r.get("classes").size() == 3);
        // cobertura: 100 vendidos em 31 dias, saldo 100 -> ~31 dias
        assertTrue(x.get("coberturaDias").asDouble() > 0);
        log("curva: X=" + x.get("classe") + " Y=" + y.get("classe") + " Z=" + z.get("classe"));
        ApiException periodo = falha(() -> e.ctx.relatorios.curvaAbc(LocalDate.now().minusDays(400), LocalDate.now(), catId));
        assertEquals(400, periodo.status());
        // outro departamento: percentuais sobre o proprio faturamento
        String outra = e.ctx.cadastros.salvarCategoria(null, "E2E Outra " + e.sufixo, null, true);
        Produto so = e.criarProduto("Rel So", "RSO", "10", "1,00", "2,00", outra, "0", "0");
        venda(so, 10, new BigDecimal("20.00"), 2);
        JsonNode r2 = e.ctx.relatorios.curvaAbc(LocalDate.now().minusDays(30), LocalDate.now(), outra);
        assertEquals(100.0, itemDe(r2.get("itens"), so).get("participacao").asDouble(), 0.01);
    }

    @Test
    @Order(4)
    void estoqueParadoEValor() {
        JsonNode r = e.ctx.relatorios.parado(60);
        JsonNode p = itemDe(r, parado);
        assertNotNull(p);
        assertTrue(p.get("diasSemVenda").asInt() >= 74 && p.get("diasSemVenda").asInt() <= 76);
        assertEquals(100.0, p.get("valorParado").asDouble(), 0.001, "20 un x R$ 5,00");
        JsonNode n = itemDe(r, nunca);
        assertNotNull(n);
        assertTrue(n.get("nuncaVendido").asBoolean());
        assertTrue(n.get("diasSemVenda").isNull());
        assertFalse(n.get("ultimaEntrada").isNull(), "mostra a data da ultima entrada");
        assertNull(itemDe(r, giro), "vendeu ha poucos dias: nao esta parado");
    }

    @Test
    @Order(5)
    void criticoEValidade() {
        JsonNode c = e.ctx.relatorios.critico();
        JsonNode i = itemDe(c, abaixoMin);
        assertNotNull(i);
        assertEquals("abaixo do mínimo", i.get("situacao").asText());
        assertEquals(8.0, i.get("faltam").asDouble(), 0.001);
        // lote vencendo em 5 dias aparece em validade
        e.ctx.produtos.ajustar(nunca.id(), "entrada", new BigDecimal("3"), "lote de teste", LocalDate.now().plusDays(5).toString(), "L1");
        JsonNode v = e.ctx.relatorios.validade(30);
        JsonNode lote = itemDe(v, nunca);
        assertNotNull(lote, "lotes a vencer em 30 dias");
        assertEquals(5, lote.get("diasRestantes").asInt());
    }

    @Test
    @Order(6)
    void perdasDoMes() {
        e.ctx.produtos.ajustar(giro.id(), "perda", new BigDecimal("2"), "embalagem avariada", null, null);
        JsonNode r = e.ctx.relatorios.perdas(LocalDate.now().withDayOfMonth(1), LocalDate.now());
        JsonNode p = itemDe(r.get("itens"), giro);
        assertNotNull(p);
        assertEquals(2.0, p.get("quantidade").asDouble(), 0.001);
        assertEquals(4.0, p.get("valor").asDouble(), 0.001, "2 un x R$ 2,00");
        assertEquals("embalagem avariada", p.get("motivo").asText());
        assertFalse(p.get("usuario").asText().isBlank());
        assertTrue(r.get("valorTotal").asDouble() >= 4.0);
    }

    @Test
    @Order(7)
    void estoquistaSoVeQuantidades() {
        AppContext est = e.comoEstoquista(homeEstoquista);
        JsonNode rep = est.relatorios.reposicao(30, 15, catId);
        JsonNode i = itemDe(rep.get("itens"), abaixoMin);
        assertNotNull(i);
        assertTrue(i.get("custoUnitario").isNull() && i.get("custoEstimado").isNull(), "sem campos de valor");
        assertTrue(rep.get("custoTotal").isNull());
        assertTrue(itemDe(est.relatorios.critico(), abaixoMin).get("custoReposicao").isNull());
        assertEquals(403, falha(() -> est.relatorios.curvaAbc(LocalDate.now().minusDays(30), LocalDate.now(), catId)).status());
        assertEquals(403, falha(() -> est.relatorios.parado(60)).status());
        assertEquals(403, falha(() -> est.relatorios.perdas(LocalDate.now().minusDays(30), LocalDate.now())).status());
        assertEquals(403, falha(() -> est.compras.listar()).status(), "estoquista nao gera pedido");
    }

    @Test
    @Order(8)
    void relatoriosNaTelaWeb() {
        JsonNode r = e.api.send("POST", "/api/Relatorio/Gerar",
                java.util.Map.of("reportId", "reposicao-giro", "filters", java.util.Map.of("dias", "30", "categoriaId", catId)),
                br.com.quack.estoque.api.ApiClient.LONG_TIMEOUT);
        assertTrue(r.get("rows").size() >= 2);
        JsonNode abc = e.api.send("POST", "/api/Relatorio/Gerar",
                java.util.Map.of("reportId", "curva-abc", "filters", java.util.Map.of("categoriaId", catId)),
                br.com.quack.estoque.api.ApiClient.LONG_TIMEOUT);
        assertTrue(abc.get("rows").size() >= 3);
        for (String id : new String[] {"estoque-parado", "perdas"}) {
            JsonNode x = e.api.send("POST", "/api/Relatorio/Gerar", java.util.Map.of("reportId", id, "filters", java.util.Map.of()),
                    br.com.quack.estoque.api.ApiClient.LONG_TIMEOUT);
            assertTrue(x.has("columns"));
        }
    }
}
