package br.com.quack.estoque.analise;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.ui.Zoom;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Curva ABC (clique na matriz filtra a tabela) e Reposição (tela e rascunhos de pedido por fornecedor) contra a empresa de
 * demonstração. Os rascunhos criados são cancelados ao final para não alterar o "em trânsito" dos outros testes.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class CurvaReposicaoE2ETest {
    @TempDir
    static Path tmp;

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private record Aberto<V extends Region>(AppContext ctx, V view, Stage stage) {
    }

    private <V extends Region> Aberto<V> abrir(String sub, String email, int largura, int altura, double zoom,
            java.util.function.BiFunction<AppContext, br.com.quack.estoque.ui.Tela.Navegador, V> fabrica) throws Exception {
        System.setProperty("quack.home", tmp.resolve(sub).toString());
        return FxTestSupport.naFx(() -> {
            AppContext ctx = new AppContext();
            ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
            ctx.entrar(email, "Demo@12345");
            V v = fabrica.apply(ctx, null);
            Stage st = new Stage(StageStyle.UNDECORATED);
            Zoom.set(zoom);
            Scene sc = new Scene(zoom == 1.0 ? v : Zoom.envolver((javafx.scene.Parent) v), largura, altura);
            sc.getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setScene(sc);
            st.setX(-20000);
            st.show();
            ((br.com.quack.estoque.ui.Tela) v).ativar();
            return new Aberto<>(ctx, v, st);
        });
    }

    private static <T> T esperar(Supplier<T> leitura, java.util.function.Predicate<T> pronto, String o) throws Exception {
        for (int i = 0; i < 80; i++) {
            T v = FxTestSupport.naFx(leitura::get);
            if (pronto.test(v)) {
                return v;
            }
            Thread.sleep(400);
        }
        fail("Não carregou: " + o);
        return null;
    }

    private void foto(Aberto<?> a, String nome) throws Exception {
        Thread.sleep(1500);
        FxTestSupport.salvarSePedido(FxTestSupport.imagem(a.stage().getScene().getRoot(), 1.0), nome);
    }

    private void fechar(Aberto<?> a) throws Exception {
        FxTestSupport.naFx(() -> {
            a.stage().close();
            return null;
        });
    }

    @Test
    void cliqueNaMatrizFiltraATabela() throws Exception {
        Aberto<CurvaAbcView> a = abrir("abc", "gerente.demo@teste.com", 1366, 690, 0.85, CurvaAbcView::new);
        var todos = esperar(a.view()::codigosNaTabela, l -> l.size() > 50, "tabela da curva");
        JsonNode api = a.ctx().analise.abc(new FiltrosAnalise(), null, null).path("dados");
        assertEquals(Math.min(1000, api.path("itens").size()), todos.size(), "a tabela tem os mesmos itens da API");
        assertTrue(api.path("resumo").size() == 3, "resumo com as 3 classes");

        FxTestSupport.naFx(() -> {
            a.view().clicarCelula("A", "F");
            return null;
        });
        var filtrados = esperar(a.view()::codigosNaTabela, l -> !l.isEmpty() && l.size() < todos.size(), "tabela filtrada");
        assertEquals(java.util.List.of("AF"), FxTestSupport.naFx(a.view()::classesFsnNaTabela), "só classe A e FSN F");
        JsonNode apiAf = a.ctx().analise.abc(new FiltrosAnalise(), "A", "F").path("dados").path("itens");
        assertEquals(apiAf.size(), filtrados.size());
        foto(a, "abc-gerente-1366x690-zoom085");

        FxTestSupport.naFx(() -> {
            a.view().clicarCelula("A", "F"); // segundo clique limpa o filtro
            return null;
        });
        esperar(a.view()::codigosNaTabela, l -> l.size() == todos.size(), "filtro limpo");
        fechar(a);
    }

    @Test
    void reposicaoMostraUrgenciasEGeraPedidosPorFornecedor() throws Exception {
        Aberto<ReposicaoView> a = abrir("reposicao", "gerente.demo@teste.com", 1366, 690, 0.85, ReposicaoView::new);
        esperar(a.view()::totalDeLinhas, n -> n > 0, "linhas da reposição");
        JsonNode api = a.ctx().analise.reposicao(new FiltrosAnalise()).path("dados");
        assertEquals(api.path("itens").size(), FxTestSupport.naFx(a.view()::totalDeLinhas), "mesmas linhas da API");
        assertEquals("ruptura", FxTestSupport.naFx(a.view()::urgenciaDaPrimeiraLinha), "ruptura vem primeiro");
        assertEquals(String.valueOf(api.path("resumo").path("itens").asInt()), FxTestSupport.naFx(a.view()::valoresDosCartoes).get(0));
        foto(a, "reposicao-gerente-1366x690-zoom085");

        // rascunhos por fornecedor: escolhe 3 produtos de fornecedores diferentes
        Map<String, BigDecimal> escolhidos = new LinkedHashMap<>();
        Map<String, String> fornecedorDoProduto = new LinkedHashMap<>();
        java.util.Set<String> vistos = new java.util.HashSet<>();
        for (JsonNode i : api.path("itens")) {
            if (!i.path("fornecedorId").isNull() && vistos.add(i.path("fornecedorId").asText()) && escolhidos.size() < 3) {
                escolhidos.put(i.path("produtoId").asText(), BigDecimal.valueOf(Math.max(1, i.path("sugestao").asDouble())));
                fornecedorDoProduto.put(i.path("produtoId").asText(), i.path("fornecedor").asText());
            }
        }
        assertEquals(3, escolhidos.size(), "a demo tem vários fornecedores");
        JsonNode resp = a.ctx().analise.gerarPedidos(escolhidos, "teste e2e reposição");
        try {
            assertEquals(3, resp.path("pedidos").size(), "um rascunho por fornecedor");
            assertEquals(0, resp.path("ignorados").size());
            JsonNode lista = a.ctx().compras.listar();
            for (JsonNode p : resp.path("pedidos")) {
                String numero = p.path("numero").asText();
                JsonNode oc = null;
                for (JsonNode o : lista) {
                    if (o.path("orderNumber").asText().equals(numero) && o.path("note").asText().contains("teste e2e")) {
                        oc = o;
                    }
                }
                assertNotNull(oc, "pedido " + numero + " aparece em GET /api/OrdemCompra");
                assertEquals(p.path("fornecedor").asText(), oc.path("supplierName").asText());
                assertEquals(0, oc.path("status").asInt(), "rascunho = pendente");
            }
        } finally {
            for (JsonNode p : resp.path("pedidos")) {
                a.ctx().api.send("POST", "/api/OrdemCompra/" + p.path("numero").asText() + "/cancelar",
                        java.util.Map.of("motivoCancelamento", "teste e2e"), br.com.quack.estoque.api.ApiClient.LIST_TIMEOUT);
            }
        }
        fechar(a);
    }

    @Test
    void estoquistaVeReposicaoSemValoresENaoGeraPedido() throws Exception {
        Aberto<ReposicaoView> a = abrir("reposicao-estoquista", "estoquista.demo@teste.com", 1024, 680, 1.0, ReposicaoView::new);
        esperar(a.view()::totalDeLinhas, n -> n > 0, "linhas da reposição");
        assertTrue(FxTestSupport.naFx(a.view()::valoresDosCartoes).stream().noneMatch(v -> v.contains("R$")), "sem R$ nos cartões");
        foto(a, "reposicao-estoquista-1024x680");
        fechar(a);
        var ex = assertThrows(br.com.quack.estoque.api.ApiException.class, () -> a.ctx().analise.gerarPedidos(Map.of("x", BigDecimal.ONE), "x"));
        assertEquals(403, ex.status(), "estoquista não gera pedido");
    }
}
