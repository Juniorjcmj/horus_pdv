package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.ui.ProdutosView;
import br.com.quack.estoque.ui.Tela;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Primeira coluna da tabela de produtos: marcar produtos e mandar todos para a fila de etiquetas de uma vez. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class SelecaoProdutosUiE2ETest {
    @TempDir
    static Path home;

    private static <T> List<T> todos(Node raiz, Class<T> tipo) {
        List<T> r = new ArrayList<>();
        if (tipo.isInstance(raiz)) {
            r.add(tipo.cast(raiz));
        }
        if (raiz instanceof javafx.scene.Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) {
                r.addAll(todos(n, tipo));
            }
        }
        return r;
    }

    @Test
    void marcarDoisEMandarParaEtiquetas() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        Object[] ref = new Object[2];
        FxTestSupport.naFx(() -> {
            Tela.Navegador nav = new Tela.Navegador() {
                public void ir(String id) { }
                public void novaEntradaNfe() { }
                public void recarregarCatalogo() { }
            };
            ProdutosView v = new ProdutosView(e.ctx, nav);
            Stage st = new Stage();
            st.setScene(new Scene(v, 1600, 760));
            st.getScene().getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setX(-20000);
            st.show();
            v.ativar();
            ref[0] = v;
            ref[1] = st;
            return null;
        });
        Thread.sleep(5000);
        int antes = e.ctx.etiquetas.totalEtiquetas();

        String info = FxTestSupport.naFx(() -> {
            ProdutosView v = (ProdutosView) ref[0];
            // caixas das linhas (dentro de células), não a do cabeçalho
            List<CheckBox> caixas = todos(v, CheckBox.class).stream().filter(c -> c.getParent() instanceof TableCell<?, ?>).toList();
            assertTrue(caixas.size() >= 2, "caixas de seleção nas linhas");
            caixas.get(0).fire();
            caixas.get(1).fire();
            return todos(v, javafx.scene.control.Label.class).stream().map(javafx.scene.control.Label::getText)
                    .filter(t -> t != null && t.contains("marcado")).findFirst().orElse("");
        });
        log("após marcar: '" + info + "'");
        assertEquals("2 produtos marcados", info);

        FxTestSupport.naFx(() -> {
            ProdutosView v = (ProdutosView) ref[0];
            todos(v, Button.class).stream().filter(b -> "Etiqueta".equals(b.getText())).findFirst().orElseThrow().fire();
            return null;
        });
        int depois = e.ctx.etiquetas.totalEtiquetas();
        boolean desmarcou = FxTestSupport.naFx(() -> {
            ProdutosView v = (ProdutosView) ref[0];
            boolean r = todos(v, CheckBox.class).stream().filter(c -> c.getParent() instanceof TableCell<?, ?>)
                    .noneMatch(CheckBox::isSelected);
            ((Stage) ref[1]).hide();
            return r;
        });
        log("fila de etiquetas: " + antes + " -> " + depois);
        assertEquals(antes + 2, depois, "os dois marcados entraram na fila");
        assertTrue(desmarcou, "desmarca depois de mandar para a fila");
    }
}
