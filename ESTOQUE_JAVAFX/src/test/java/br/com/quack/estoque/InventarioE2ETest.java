package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.falha;
import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.ApiClient;
import br.com.quack.estoque.api.ApiException;
import br.com.quack.estoque.api.InventarioApi;
import br.com.quack.estoque.domain.Produto;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cenários de specs/estoque/inventario/spec.md contra a API real (Docker): escopo, sobreposição, contagens somadas,
 * fora do escopo, anulação, divergências, venda durante a contagem, senha errada, aplicação dupla, cancelamento e
 * trilha. Imprime a trilha ao final.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventarioE2ETest {
    @TempDir
    static Path home;

    E2eSupport e;
    InventarioApi inv;
    String catPai;
    String catFilha;
    Produto falta;    // sistema 50, custo 4,00, contado 46
    Produto vendido;  // sistema 32, contado 30, vendeu 5 depois
    Produto fora;     // fora do escopo
    String invId;
    int invNumero;
    long mov0;

    @BeforeAll
    void preparar() {
        e = new E2eSupport(home);
        e.loginAdmin();
        inv = e.ctx.inventarios;
        for (JsonNode aberto : inv.listar("em_contagem")) { // sobras de execucoes anteriores
            inv.cancelar(aberto.get("id").asText(), "limpeza do teste", null);
        }
        catPai = e.ctx.cadastros.salvarCategoria(null, "E2E Bebidas " + e.sufixo, null, true);
        catFilha = e.ctx.cadastros.salvarCategoria(null, "E2E Refri " + e.sufixo, catPai, true);
        falta = e.criarProduto("Inv Falta", "IVF", "50", "4,00", "9,00", catFilha, "0", "0");
        vendido = e.criarProduto("Inv Vendido", "IVV", "32", "3,00", "6,00", catFilha, "0", "0");
        fora = e.criarProduto("Inv Fora", "IVO", "10", "2,00", "5,00", null, "0", "0");
        log("produtos: " + falta.codigo() + ", " + vendido.codigo() + ", " + fora.codigo());
    }

    @Test
    @Order(1)
    void abrirPorCategoriaIncluiSubcategorias() {
        JsonNode r = inv.abrir("E2E por categoria", "categorias", List.of(catPai), null, e.ctx.terminal());
        invId = r.get("id").asText();
        invNumero = r.get("numero").asInt();
        assertEquals(2, r.get("itens").asInt(), "os 2 produtos da subcategoria entram");
        assertEquals("em_contagem", r.get("status").asText());
        log("inventario #" + invNumero + " aberto (" + invId + ")");
    }

    @Test
    @Order(2)
    void escopoSobrepostoRecusadoComNumeroDoAberto() {
        ApiException ex = falha(() -> inv.abrir("loja toda", "loja", null, null, null));
        assertEquals(409, ex.status());
        assertEquals("ESCOPO_SOBREPOSTO", ex.data().get("codigo").asText());
        assertTrue(ex.data().get("detalhe").get("numeroAberto").asInt() > 0);
        ApiException ex2 = falha(() -> inv.abrir("produto", "produtos", null, List.of(falta.id()), null));
        assertEquals(409, ex2.status());
        assertEquals(invNumero, ex2.data().get("detalhe").get("numeroAberto").asInt(), "informa o numero do inventario aberto");
        // Produto fora do escopo aberto pode ter o seu inventario; cancela em seguida (libera o escopo).
        JsonNode outro = inv.abrir("so o produto fora", "produtos", null, List.of(fora.id()), null);
        inv.cancelar(outro.get("id").asText(), "teste de escopo livre", null);
        log("sobreposicao recusada: " + ex.getMessage());
    }

    @Test
    @Order(3)
    void somarLocaisEForaDoEscopo() {
        InventarioApi.Registro a = inv.contar(invId, falta.id(), null, new BigDecimal("20"), "Prateleira", e.ctx.terminal());
        assertEquals(0, new BigDecimal("20").compareTo(a.totalContado()));
        InventarioApi.Registro b = inv.contar(invId, null, falta.codigo(), new BigDecimal("26"), "Deposito", e.ctx.terminal());
        assertEquals(0, new BigDecimal("46").compareTo(b.totalContado()), "20 + 26 somam 46 por codigo interno");

        List<InventarioApi.Contagem> lancs = inv.contagens(invId, false, falta.id(), 50);
        assertEquals(2, lancs.size(), "as duas contagens ficam registradas separadamente");

        ApiException foraEscopo = falha(() -> inv.contar(invId, fora.id(), null, BigDecimal.ONE, null, null));
        assertEquals(422, foraEscopo.status());
        assertEquals("FORA_DO_ESCOPO", foraEscopo.data().get("codigo").asText());
        ApiException desconhecido = falha(() -> inv.contar(invId, null, "NAO-EXISTE-" + e.sufixo, BigDecimal.ONE, null, null));
        assertEquals(404, desconhecido.status());
        ApiException negativa = falha(() -> inv.contar(invId, falta.id(), null, new BigDecimal("-1"), null, null));
        assertEquals(400, negativa.status());
        ApiException fracao = falha(() -> inv.contar(invId, falta.id(), null, new BigDecimal("1.5"), null, null));
        assertEquals(400, fracao.status(), "UN nao aceita fracao");
    }

    @Test
    @Order(4)
    void anularContagemErradaNaoEntraNaSoma() {
        InventarioApi.Registro erro = inv.contar(invId, falta.id(), null, new BigDecimal("240"), "Deposito", null);
        assertEquals(0, new BigDecimal("286").compareTo(erro.totalContado()));
        inv.anular(invId, erro.contagemId(), "lancei 240 no lugar de 24");

        InventarioApi.Item item = item(falta.id(), null);
        assertEquals(0, new BigDecimal("46").compareTo(item.contado()), "a anulada deixa de entrar na soma");
        InventarioApi.Contagem anulada = inv.contagens(invId, false, falta.id(), 50).stream()
                .filter(c -> c.id().equals(erro.contagemId())).findFirst().orElseThrow();
        assertTrue(anulada.anulada());
        assertEquals("lancei 240 no lugar de 24", anulada.anuladaMotivo());
        assertNotNull(anulada.anuladaPor());
        ApiException dupla = falha(() -> inv.anular(invId, erro.contagemId(), "de novo"));
        assertEquals(409, dupla.status());
    }

    @Test
    @Order(5)
    void divergenciaDeFaltaComValor() {
        InventarioApi.Item item = item(falta.id(), "divergentes");
        assertEquals(0, new BigDecimal("50").compareTo(item.saldoNa1aContagem()));
        assertEquals(0, new BigDecimal("-4").compareTo(item.diferenca()));
        assertEquals(0, new BigDecimal("-16.00").compareTo(item.valorDiferenca()), "-4 un x R$ 4,00");
        // o outro produto do escopo ainda nao foi contado
        assertTrue(inv.itens(invId, "nao_contados").stream().anyMatch(i -> i.produtoId().equals(vendido.id())));
        JsonNode resumo = inv.obter(invId).get("resumo");
        assertEquals(1, resumo.get("naoContados").asInt());
        assertEquals(-16.0, resumo.get("falta").asDouble(), 0.001);
        log("divergencia: " + item);
    }

    @Test
    @Order(6)
    void recontagemAnulaAsContagensEMarcaOProduto() {
        InventarioApi.Registro c = inv.contar(invId, vendido.id(), null, new BigDecimal("31"), "Loja", null);
        assertEquals(0, new BigDecimal("31").compareTo(c.totalContado()));
        inv.recontar(invId, List.of(vendido.id()));
        assertTrue(inv.itens(invId, "recontar").stream().anyMatch(i -> i.produtoId().equals(vendido.id())));
        assertNull(item(vendido.id(), null).contado());
        // vendida a recontagem: 30 contados quando o sistema mostrava 32
        InventarioApi.Registro novo = inv.contar(invId, vendido.id(), null, new BigDecimal("30"), "Loja", null);
        assertEquals(0, new BigDecimal("30").compareTo(novo.totalContado()));
        assertTrue(inv.itens(invId, "recontar").isEmpty(), "a nova contagem tira o aviso de recontar");
        assertEquals(0, new BigDecimal("32").compareTo(item(vendido.id(), null).saldoNa1aContagem()));
    }

    @Test
    @Order(7)
    void vendaDuranteAContagemEPreservada() {
        e.vender(vendido, 5);
        assertEquals(0, new BigDecimal("27").compareTo(e.produto(vendido.id()).estoque()), "saldo atual 27");
        log("venda de 5 durante a contagem: saldo 27");
    }

    @Test
    @Order(8)
    void senhaErradaNaoAlteraEstoqueERegistraTentativa() {
        ApiException ex = falha(() -> inv.aplicar(invId, e.adminId(), "senha-errada", "acerto de fim de mes", false, e.ctx.terminal()));
        assertEquals(403, ex.status());
        assertEquals(0, new BigDecimal("50").compareTo(e.produto(falta.id()).estoque()));
        assertEquals(0, new BigDecimal("27").compareTo(e.produto(vendido.id()).estoque()));
        JsonNode trilha = inv.trilha(invId);
        boolean negada = false;
        for (JsonNode ev : trilha.get("eventos")) {
            negada |= "aplicacao_negada".equals(ev.get("tipo").asText());
        }
        assertTrue(negada, "a tentativa negada fica na trilha");
        ApiException curta = falha(() -> inv.aplicar(invId, e.adminId(), E2eSupport.ADMIN_SENHA, "ok", false, null));
        assertEquals(400, curta.status(), "justificativa curta");
    }

    @Test
    @Order(9)
    void aplicarPelaDiferencaPreservandoAVenda() {
        long antes = e.ctx.produtos.movimentacoes("inventario", 500).size();
        JsonNode r = inv.aplicar(invId, e.adminId(), E2eSupport.ADMIN_SENHA, "acerto de fim de mes", false, e.ctx.terminal());
        assertEquals(2, r.get("ajustados").asInt());
        assertEquals(46, e.produto(falta.id()).estoque().intValue(), "50 + (46 - 50)");
        assertEquals(25, e.produto(vendido.id()).estoque().intValue(), "27 + (30 - 32)");
        List<br.com.quack.estoque.api.ProdutoApi.Movimentacao> movs = e.ctx.produtos.movimentacoes(vendido.id());
        var m = movs.stream().filter(x -> "inventario".equals(x.tipo())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("27").compareTo(m.estoqueAnterior()));
        assertEquals(0, new BigDecimal("25").compareTo(m.estoqueNovo()));
        assertTrue(m.motivo().contains("acerto de fim de mes"));
        assertEquals(antes + 2, e.ctx.produtos.movimentacoes("inventario", 500).size());
        log("aplicado: " + r);
    }

    @Test
    @Order(10)
    void aplicacaoDuplaEContagemEmEncerradoRecusadas() {
        ApiException dupla = falha(() -> inv.aplicar(invId, e.adminId(), E2eSupport.ADMIN_SENHA, "tentando de novo", false, null));
        assertEquals(409, dupla.status());
        assertEquals(25, e.produto(vendido.id()).estoque().intValue(), "estoque intacto");
        ApiException encerrado = falha(() -> inv.contar(invId, falta.id(), null, BigDecimal.ONE, null, null));
        assertEquals(409, encerrado.status());
        assertEquals(1, inv.contagens(invId, true, falta.id(), 50).stream().filter(c -> c.anulada()).count());
    }

    @Test
    @Order(11)
    void cancelarLiberaOEscopoENaoMexeNoEstoque() {
        JsonNode r = inv.abrir("a cancelar", "produtos", null, List.of(fora.id()), null);
        String id = r.get("id").asText();
        inv.contar(id, fora.id(), null, new BigDecimal("3"), null, null);
        inv.cancelar(id, "contagem refeita amanha", e.ctx.terminal());
        assertEquals(10, e.produto(fora.id()).estoque().intValue());
        assertEquals("cancelado", inv.obter(id).get("inventario").get("status").asText());
        assertEquals(409, falha(() -> inv.contar(id, fora.id(), null, BigDecimal.ONE, null, null)).status());
        assertEquals(1, inv.contagens(id, true, null, 50).size(), "contagens continuam guardadas");
        inv.abrir("novo no mesmo escopo", "produtos", null, List.of(fora.id()), null); // escopo livre de novo
    }

    @Test
    @Order(12)
    void perfilCaixaNaoAbreInventario() {
        ApiClient outro = new ApiClient(System.getProperty("quack.e2e.url"));
        new br.com.quack.estoque.api.AuthApi(outro).login("operadora@teste.com", "Caixa@12345");
        ApiException ex = falha(() -> outro.send("POST", "/api/Inventario",
                java.util.Map.of("descricao", "x", "escopo", "loja"), ApiClient.CASH_TIMEOUT));
        assertEquals(403, ex.status());
        // o aplicativo recusa o login do perfil caixa
        br.com.quack.estoque.domain.DomainException de = assertThrows(br.com.quack.estoque.domain.DomainException.class,
                () -> {
                    AppContext app = new AppContext();
                    app.api.setBaseUrl(System.getProperty("quack.e2e.url"));
                    app.entrar("operadora@teste.com", "Caixa@12345");
                });
        assertTrue(de.getMessage().contains("estoque e gerência"));
    }

    @Test
    @Order(13)
    void trilhaDoInventarioAplicado() {
        JsonNode t = inv.trilha(invId);
        StringBuilder sb = new StringBuilder("\n===== TRILHA DO INVENTARIO #" + invNumero + " =====\n");
        for (JsonNode ev : t.get("eventos")) {
            sb.append(String.format("  %-18s %-22s gerente=%-16s %s%n", ev.get("tipo").asText(), ev.get("usuario").asText(),
                    ev.path("gerente").asText("-"), ev.path("descricao").asText("")));
        }
        for (JsonNode c : t.get("contagens")) {
            sb.append(String.format("  contagem %-14s qtd=%-6s por %-16s local=%-10s%s%n", c.get("codigo").asText(),
                    c.get("quantidade").asText(), c.get("usuario").asText(), c.path("local").asText("-"),
                    c.hasNonNull("anuladaPor") ? " ANULADA por " + c.get("anuladaPor").asText() + ": " + c.get("anuladaMotivo").asText() : ""));
        }
        for (JsonNode i : t.get("itens")) {
            sb.append(String.format("  item %-14s antes=%s contado=%s delta=%s depois=%s valor=%s%n", i.get("codigo").asText(),
                    i.path("saldoAntes").asText(), i.path("contado").asText(), i.path("delta").asText(),
                    i.path("saldoDepois").asText(), i.path("valorDelta").asText()));
        }
        System.out.println(sb);
        JsonNode inventario = t.get("inventario").get("inventario");
        assertEquals("aplicado", inventario.get("status").asText());
        assertEquals("acerto de fim de mes", inventario.get("justificativa").asText());
        assertFalse(inventario.get("supervisor").asText().isBlank());
        assertTrue(t.get("eventos").size() >= 3);
    }

    private InventarioApi.Item item(String produtoId, String filtro) {
        return inv.itens(invId, filtro).stream().filter(i -> i.produtoId().equals(produtoId)).findFirst().orElseThrow();
    }
}
