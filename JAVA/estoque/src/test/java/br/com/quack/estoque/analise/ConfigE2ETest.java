package br.com.quack.estoque.analise;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.AppContext;
import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.api.ApiException;
import br.com.quack.estoque.ui.Zoom;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Configurações da análise: muda os dias de parado de 90 para 60 e confere que os indicadores refletem; estoquista só lê. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class ConfigE2ETest {
    @TempDir
    static Path tmp;

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private record Aberto(AppContext ctx, ConfiguracoesView view, Stage stage) {
    }

    private Aberto abrir(String sub, String email, int largura, int altura, double zoom) throws Exception {
        System.setProperty("quack.home", tmp.resolve(sub).toString());
        return FxTestSupport.naFx(() -> {
            AppContext ctx = new AppContext();
            ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
            ctx.entrar(email, "Demo@12345");
            ConfiguracoesView v = new ConfiguracoesView(ctx, null);
            Stage st = new Stage(StageStyle.UNDECORATED);
            Zoom.set(zoom);
            Scene sc = new Scene(zoom == 1.0 ? v : Zoom.envolver((Parent) v), largura, altura);
            sc.getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setScene(sc);
            st.setX(-20000);
            st.show();
            v.ativar();
            return new Aberto(ctx, v, st);
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

    private void fechar(Aberto a) throws Exception {
        FxTestSupport.naFx(() -> {
            a.stage().close();
            return null;
        });
    }

    @Test
    void mudarDiasDeParadoReflete() throws Exception {
        Aberto a = abrir("gerente", "gerente.demo@teste.com", 1366, 690, 0.85);
        try {
            esperar(() -> a.view().valor("diasParado"), v -> "90".equals(v), "configuração padrão carregada");
            assertTrue(FxTestSupport.naFx(a.view()::editavel));
            int antes = a.ctx().analise.bloco("parados", new FiltrosAnalise()).path("dados").path("total").asInt();
            assertEquals(90, a.ctx().analise.bloco("parados", new FiltrosAnalise()).path("dados").path("diasParado").asInt());

            FxTestSupport.naFx(() -> {
                a.view().definir("diasParado", "60");
                a.view().salvarAgora();
                return null;
            });
            JsonNode depois = null;
            for (int i = 0; i < 40; i++) {
                depois = a.ctx().analise.bloco("parados", new FiltrosAnalise()).path("dados");
                if (depois.path("diasParado").asInt() == 60) {
                    break;
                }
                Thread.sleep(400);
            }
            assertEquals(60, depois.path("diasParado").asInt(), "parados passou a usar 60 dias");
            assertTrue(depois.path("total").asInt() >= antes, "com limite menor há ao menos os mesmos parados");
            assertEquals(60, a.ctx().analise.bloco("top-parados", new FiltrosAnalise()).path("dados").path("diasParado").asInt(), "dashboard também");
            assertEquals(60, a.ctx().analise.config().path("atual").path("diasParado").asInt());
            assertEquals(90, a.ctx().analise.config().path("padrao").path("diasParado").asInt(), "o padrão continua visível");
        } finally {
            a.ctx().analise.restaurarConfig();
        }
        assertEquals(90, a.ctx().analise.config().path("atual").path("diasParado").asInt(), "restaurar padrão volta para 90");
        assertEquals(90, a.ctx().analise.bloco("parados", new FiltrosAnalise()).path("dados").path("diasParado").asInt());
        fechar(a);
    }

    @Test
    void configInvalidaERecusada() throws Exception {
        Aberto a = abrir("gerente-invalida", "gerente.demo@teste.com", 1024, 680, 1.0);
        var ex = assertThrows(ApiException.class, () -> a.ctx().analise.salvarConfig(Map.of("abcLimiteA", 99, "abcLimiteB", 90)));
        assertEquals(400, ex.status());
        assertEquals(90, a.ctx().analise.config().path("atual").path("diasParado").asInt(), "nada foi alterado");
        fechar(a);
    }

    @Test
    void estoquistaSoLe() throws Exception {
        Aberto a = abrir("estoquista", "estoquista.demo@teste.com", 1024, 680, 1.0);
        esperar(() -> a.view().valor("diasParado"), v -> "90".equals(v), "configuração carregada");
        assertFalse(FxTestSupport.naFx(a.view()::editavel), "somente leitura");
        assertFalse(a.ctx().analise.config().path("podeEditar").asBoolean());
        var ex = assertThrows(ApiException.class, () -> a.ctx().analise.salvarConfig(Map.of("diasParado", 10)));
        assertEquals(403, ex.status(), "estoquista não salva");
        var ex2 = assertThrows(ApiException.class, () -> a.ctx().analise.restaurarConfig());
        assertEquals(403, ex2.status());
        FxTestSupport.salvarSePedido(FxTestSupport.imagem(a.stage().getScene().getRoot(), 1.0), "config-estoquista-1024x680");
        fechar(a);
    }
}
