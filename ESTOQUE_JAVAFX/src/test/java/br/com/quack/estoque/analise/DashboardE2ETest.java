package br.com.quack.estoque.analise;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.ui.Zoom;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Abre o Dashboard de verdade contra a empresa de demonstração (seed EstoqueAnaliseSeed) e confere os números dos cartões
 * com os do endpoint, como gerente e como estoquista (sem R$). -Dquack.test.png=pasta guarda os prints (1366x690 com zoom
 * 0,85 e 1024x680).
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class DashboardE2ETest {
    @TempDir
    static Path tmp;

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private record Aberto(AppContext ctx, DashboardView view, Stage stage) {
    }

    private Aberto abrir(String sub, String email, String largura, String altura, double zoom) throws Exception {
        System.setProperty("quack.home", tmp.resolve(sub).toString());
        return FxTestSupport.naFx(() -> {
            AppContext ctx = new AppContext();
            ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
            ctx.entrar(email, "Demo@12345");
            DashboardView v = new DashboardView(ctx, null);
            Stage st = new Stage(StageStyle.UNDECORATED);
            Zoom.set(zoom);
            Scene sc = new Scene(zoom == 1.0 ? v : Zoom.envolver(v), Double.parseDouble(largura), Double.parseDouble(altura));
            sc.getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setScene(sc);
            st.setX(-20000);
            st.show();
            v.ativar();
            return new Aberto(ctx, v, st);
        });
    }

    private Map<String, String> aguardarCartoes(Aberto a, int minimo) throws Exception {
        for (int i = 0; i < 60; i++) {
            Map<String, String> m = FxTestSupport.naFx(a.view()::valoresDosCartoes);
            if (m.size() >= minimo) {
                return m;
            }
            Thread.sleep(500);
        }
        fail("Os cartões não carregaram");
        return Map.of();
    }

    private void foto(Aberto a, String nome) throws Exception {
        Thread.sleep(2500); // gráficos chegam por chamadas independentes
        FxTestSupport.salvarSePedido(FxTestSupport.imagem(a.stage().getScene().getRoot(), 1.0), nome);
    }

    @Test
    void cartoesDoGerenteConferemComAApi() throws Exception {
        Aberto a = abrir("gerente", "gerente.demo@teste.com", "1366", "690", 0.85);
        Map<String, String> cartoes = aguardarCartoes(a, 12);
        JsonNode kpis = a.ctx().analise.bloco("kpis", new FiltrosAnalise()).path("dados").path("kpis");
        assertEquals(12, kpis.size(), "o gerente recebe os 12 indicadores");
        for (JsonNode k : kpis) {
            String esperado = k.path("valor").isNull() ? "—" : AnaliseFmt.valor(k.path("unidade").asText(), k.path("valor"));
            assertEquals(esperado, cartoes.get(k.path("chave").asText()), "cartão " + k.path("chave").asText());
        }
        assertTrue(cartoes.get("valorEstoque").startsWith("R$ "), "valor em estoque em reais: " + cartoes.get("valorEstoque"));
        foto(a, "dashboard-gerente-1366x690-zoom085");
        FxTestSupport.naFx(() -> {
            a.stage().close();
            return null;
        });
    }

    @Test
    void estoquistaNaoVeValores() throws Exception {
        Aberto a = abrir("estoquista", "estoquista.demo@teste.com", "1024", "680", 1.0);
        Map<String, String> cartoes = aguardarCartoes(a, 8);
        assertFalse(cartoes.containsKey("valorEstoque"), "estoquista sem valor em estoque");
        assertFalse(cartoes.containsKey("cmv"));
        assertFalse(cartoes.containsKey("valorExcesso"));
        cartoes.values().forEach(v -> assertFalse(v.contains("R$"), "sem R$ para o estoquista: " + v));
        assertTrue(cartoes.containsKey("giroAnualizado") && cartoes.containsKey("emRupturaAgoraPercentual"));
        foto(a, "dashboard-estoquista-1024x680");
        FxTestSupport.naFx(() -> {
            a.stage().close();
            return null;
        });
    }

    @Test
    void telaCompletaParaRevisaoVisual() throws Exception {
        Aberto a = abrir("completo", "gerente.demo@teste.com", "1366", "1750", 1.0);
        aguardarCartoes(a, 12);
        foto(a, "dashboard-gerente-completo");
        FxTestSupport.naFx(() -> {
            a.stage().close();
            return null;
        });
    }
}
