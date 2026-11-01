package br.com.quack.estoque.analise;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.ui.MainView;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Zoom;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Etapa 5 contra a empresa de demonstração: detalhe do produto aberto pelo top de parados, acuracidade com contagem cíclica
 * (gera a lista, conta, aplica e a nova acuracidade aparece), perdas e fornecedores conferidos com a API.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class Etapa5E2ETest {
    @TempDir
    static Path tmp;

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private record Aberto(AppContext ctx, MainView main, Stage stage) {
    }

    private Aberto abrirApp(String sub, String email, int largura, int altura, double zoom) throws Exception {
        System.setProperty("quack.home", tmp.resolve(sub).toString());
        return FxTestSupport.naFx(() -> {
            AppContext ctx = new AppContext();
            ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
            ctx.entrar(email, "Demo@12345");
            ctx.garantirCatalogo(true);
            MainView mv = new MainView(ctx, () -> {
            });
            Stage st = new Stage(StageStyle.UNDECORATED);
            Zoom.set(zoom);
            Scene sc = new Scene(zoom == 1.0 ? mv : Zoom.envolver((Parent) mv), largura, altura);
            sc.getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setScene(sc);
            st.setX(-20000);
            st.show();
            mv.instalar(sc);
            return new Aberto(ctx, mv, st);
        });
    }

    private static <T> T esperar(Supplier<T> leitura, java.util.function.Predicate<T> pronto, String o) throws Exception {
        for (int i = 0; i < 90; i++) {
            T v = FxTestSupport.naFx(leitura::get);
            if (pronto.test(v)) {
                return v;
            }
            Thread.sleep(400);
        }
        fail("Não carregou: " + o);
        return null;
    }

    private void foto(Aberto a, String nome) throws Exception {
        Thread.sleep(2500);
        FxTestSupport.salvarSePedido(FxTestSupport.imagem(a.stage().getScene().getRoot(), 1.0), nome);
    }

    private void fechar(Aberto a) throws Exception {
        FxTestSupport.naFx(() -> {
            a.stage().close();
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Tela> T tela(Aberto a, String id, Class<T> tipo) throws Exception {
        return FxTestSupport.naFx(() -> {
            a.main().ir(id);
            return (T) ((javafx.scene.layout.StackPane) a.main().getCenter()).getChildren().get(0);
        });
    }

    @Test
    void detalheDoProdutoAbreAPartirDoTopDeParados() throws Exception {
        Aberto a = abrirApp("detalhe", "gerente.demo@teste.com", 1366, 690, 0.85);
        PerdasView pv = tela(a, "analise-perdas", PerdasView.class);
        esperar(pv::linhasDeParados, n -> n > 0, "tabela de parados");
        JsonNode api = a.ctx().analise.bloco("parados", new FiltrosAnalise()).path("dados");
        assertEquals(Math.min(500, api.path("itens").size()), FxTestSupport.naFx(pv::linhasDeParados), "mesmas linhas da API");
        assertEquals(String.valueOf(api.path("total").asInt()), FxTestSupport.naFx(pv::cartoesDeParados).get(0), "cartão de parados = total da API");
        JsonNode perdasApi = a.ctx().analise.bloco("perdas", new FiltrosAnalise()).path("dados");
        List<String> cartoes = esperar(pv::cartoesDePerdas, c -> c.size() >= 2, "cartões de perdas");
        assertTrue(cartoes.get(0).length() > 0 && perdasApi.path("totais").path("ocorrencias").asInt() >= 0);
        foto(a, "perdas-gerente-1366x690-zoom085");

        String id = FxTestSupport.naFx(pv::primeiroParadoId);
        FxTestSupport.naFx(() -> {
            a.main().abrirProduto(id);
            return null;
        });
        var detalhe = esperar(a.main()::detalheAberto, d -> d != null && !"Produto".equals(d.tituloMostrado()), "detalhe do produto");
        esperar(detalhe::linhasDoExtrato, n -> n > 0, "extrato do produto");
        foto(a, "detalhe-produto-1366x690-zoom085");
        fechar(a);
    }

    @Test
    void contagemCiclicaGeraListaContaEAplicaENovaAcuraciaAparece() throws Exception {
        Aberto a = abrirApp("acuracidade", "gerente.demo@teste.com", 1366, 690, 0.85);
        AcuraciaView av = tela(a, "analise-acuracidade", AcuraciaView.class);
        esperar(av::itensNaLista, n -> n > 0, "lista da contagem cíclica");
        foto(a, "acuracidade-gerente-1366x690-zoom085");
        FiltrosAnalise f = new FiltrosAnalise();
        f.aplicarAtalho("Últimos 12 meses", java.time.LocalDate.now());
        int antes = a.ctx().analise.bloco("acuracidade", f).path("dados").path("pontos").size();

        // conta 3 itens (7 unidades de cada) e registra: abre um inventário em contagem
        List<String> ids = FxTestSupport.naFx(() -> av.preencher(3, "7"));
        assertEquals(3, ids.size());
        FxTestSupport.naFx(() -> {
            av.registrarSemAplicar();
            return null;
        });
        JsonNode aberto = null;
        for (int i = 0; i < 60 && aberto == null; i++) {
            Thread.sleep(500);
            for (JsonNode inv : a.ctx().inventarios.listar("em_contagem")) {
                if (inv.path("descricao").asText().startsWith("Contagem cíclica")) {
                    aberto = inv;
                }
            }
        }
        assertNotNull(aberto, "a contagem abriu um inventário de contagem cíclica");
        String invId = aberto.path("id").asText();
        // contagens registradas: espera as 3 e aplica como gerente (senha do gerente da demo)
        for (int i = 0; i < 60 && a.ctx().inventarios.contagens(invId, false, null, 50).size() < 3; i++) {
            Thread.sleep(500);
        }
        assertEquals(3, a.ctx().inventarios.contagens(invId, false, null, 50).size(), "3 contagens lançadas");
        a.ctx().inventarios.aplicar(invId, a.ctx().session.user().id(), "Demo@12345", "Teste E2E da contagem cíclica", false, a.ctx().terminal());

        JsonNode depois = a.ctx().analise.bloco("acuracidade", f).path("dados");
        assertEquals(antes + 1, depois.path("pontos").size(), "a nova acuracidade aparece (cache invalidado ao aplicar)");
        JsonNode ultima = depois.path("pontos").get(depois.path("pontos").size() - 1);
        assertEquals(3, ultima.path("itens").asInt());
        assertTrue(ultima.path("acuracidade").asDouble() < 100, "a contagem de 7 unidades diverge do sistema");
        fechar(a);
    }

    @Test
    void fornecedoresConferemComAApi() throws Exception {
        Aberto a = abrirApp("fornecedores", "estoquista.demo@teste.com", 1024, 680, 1.0);
        FornecedoresView fv = tela(a, "analise-fornecedores", FornecedoresView.class);
        esperar(fv::linhas, n -> n > 0, "tabela de fornecedores");
        JsonNode api = a.ctx().analise.bloco("fornecedores", new FiltrosAnalise()).path("dados").path("fornecedores");
        assertEquals(api.size(), FxTestSupport.naFx(fv::linhas));
        assertEquals(api.get(0).path("nome").asText() + "|" + api.get(0).path("pedidos").asInt(), FxTestSupport.naFx(fv::primeiraLinha));
        foto(a, "fornecedores-estoquista-1024x680");
        fechar(a);
    }

    @Test
    void estoquistaVeAcuracidadeEPerdasSemValores() throws Exception {
        Aberto a = abrirApp("estoquista-perdas", "estoquista.demo@teste.com", 1024, 680, 1.0);
        PerdasView pv = tela(a, "analise-perdas", PerdasView.class);
        List<String> cartoes = esperar(pv::cartoesDePerdas, c -> c.size() >= 2, "cartões de perdas");
        assertEquals(2, cartoes.size(), "estoquista: só quantidade e ocorrências");
        assertTrue(cartoes.stream().noneMatch(c -> c.contains("R$")));
        foto(a, "perdas-estoquista-1024x680");
        fechar(a);
    }
}
