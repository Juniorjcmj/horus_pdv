package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.ProdutosView;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tabela de produtos: editar Markup %, Lucro R$ ou Margem % recalcula e grava o preço de venda; as outras colunas
 * acompanham. Produto com custo 3,00 e preço 5,00.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class PrecificacaoTabelaUiE2ETest {
    @TempDir
    static Path home;

    @SuppressWarnings("unchecked")
    private static TableView<Produto> tabela(Node raiz) {
        if (raiz instanceof TableView<?> t) {
            return (TableView<Produto>) t;
        }
        if (raiz instanceof javafx.scene.Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) {
                TableView<Produto> t = tabela(n);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static void editar(TableView<Produto> t, String produtoId, String coluna, String valor) {
        int linha = -1;
        for (int i = 0; i < t.getItems().size(); i++) {
            if (t.getItems().get(i).id().equals(produtoId)) {
                linha = i;
            }
        }
        assertTrue(linha >= 0, "produto na tabela");
        TableColumn<Produto, ?> col = t.getColumns().stream().filter(c -> coluna.equals(Ui.titulo(c))).findFirst().orElseThrow();
        assertTrue(col.isVisible(), coluna + " visível");
        t.scrollTo(linha);
        t.layout();
        t.edit(linha, col);
        TextField f = (TextField) t.lookup(".rg-celula-campo");
        assertNotNull(f, "campo de " + coluna + " aberto");
        f.setText(valor);
        f.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    private static String celula(TableView<Produto> t, String produtoId, String coluna) {
        Produto p = t.getItems().stream().filter(x -> x.id().equals(produtoId)).findFirst().orElseThrow();
        TableColumn<Produto, ?> col = t.getColumns().stream().filter(c -> coluna.equals(Ui.titulo(c))).findFirst().orElseThrow();
        return String.valueOf(col.getCellData(p));
    }

    @Test
    void lucroMargemEMarkupRecalculamOPreco() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", "Markup Tabela " + e.sufixo);
        n.put("productCode", "MKT-" + e.sufixo);
        n.put("productQnt", "4");
        n.put("productUnitPrice", "3,0000");
        n.put("productSalePrice", "5,00");
        n.put("unidadeComercial", "UN");
        Produto p = e.ctx.produtos.criar(n);

        Object[] ref = new Object[2];
        FxTestSupport.naFx(() -> {
            Tela.Navegador nav = new Tela.Navegador() {
                public void ir(String id) { }
                public void novaEntradaNfe() { }
                public void recarregarCatalogo() { }
            };
            ProdutosView v = new ProdutosView(e.ctx, nav);
            Stage st = new Stage();
            st.setScene(new Scene(v, 1800, 760));
            st.getScene().getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setX(-20000);
            st.show();
            v.ativar();
            ref[0] = v;
            ref[1] = st;
            return null;
        });
        Thread.sleep(5000);

        String[] inicial = FxTestSupport.naFx(() -> {
            TableView<Produto> t = tabela((Node) ref[0]);
            return new String[] {celula(t, p.id(), "Lucro R$"), celula(t, p.id(), "Margem"), celula(t, p.id(), "Markup")};
        });
        log("inicial: lucro " + inicial[0] + " · margem " + inicial[1] + " · markup " + inicial[2]);
        assertArrayEquals(new String[] {"2,00", "40,00%", "66,67%"}, inicial);

        // markup 50% -> preço 4,50
        FxTestSupport.naFx(() -> {
            editar(tabela((Node) ref[0]), p.id(), "Markup", "50");
            return null;
        });
        Thread.sleep(2500);
        assertEquals(0, new BigDecimal("4.50").compareTo(e.produto(p.id()).preco()), "markup 50% -> R$ 4,50");
        String[] aposMarkup = FxTestSupport.naFx(() -> {
            TableView<Produto> t = tabela((Node) ref[0]);
            return new String[] {celula(t, p.id(), "Lucro R$"), celula(t, p.id(), "Margem"), celula(t, p.id(), "Markup")};
        });
        log("após markup 50: lucro " + aposMarkup[0] + " · margem " + aposMarkup[1] + " · markup " + aposMarkup[2]);
        assertArrayEquals(new String[] {"1,50", "33,33%", "50,00%"}, aposMarkup);

        // lucro R$ 3,00 -> preço 6,00
        FxTestSupport.naFx(() -> {
            editar(tabela((Node) ref[0]), p.id(), "Lucro R$", "3");
            return null;
        });
        Thread.sleep(2500);
        assertEquals(0, new BigDecimal("6.00").compareTo(e.produto(p.id()).preco()), "lucro R$ 3 -> R$ 6,00");

        // margem 25% -> preço 4,00
        FxTestSupport.naFx(() -> {
            editar(tabela((Node) ref[0]), p.id(), "Margem", "25");
            return null;
        });
        Thread.sleep(2500);
        assertEquals(0, new BigDecimal("4.00").compareTo(e.produto(p.id()).preco()), "margem 25% -> R$ 4,00");
        String[] fim = FxTestSupport.naFx(() -> {
            TableView<Produto> t = tabela((Node) ref[0]);
            String[] r = {celula(t, p.id(), "Preço"), celula(t, p.id(), "Lucro R$"), celula(t, p.id(), "Markup")};
            ((Stage) ref[1]).hide();
            return r;
        });
        log("após margem 25: preço " + fim[0] + " · lucro " + fim[1] + " · markup " + fim[2]);
        assertArrayEquals(new String[] {"4,00", "1,00", "33,33%"}, fim);
    }
}
