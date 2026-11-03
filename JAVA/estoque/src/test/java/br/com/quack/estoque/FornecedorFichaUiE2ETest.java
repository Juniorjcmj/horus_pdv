package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.ProdutosView;
import br.com.quack.estoque.ui.Tela;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Ficha do produto pela TELA: escolher outro fornecedor na lista e Salvar grava de verdade. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class FornecedorFichaUiE2ETest {
    @TempDir
    static Path home;

    private static CadastroApi.Fornecedor fornecedor(E2eSupport e, String nome) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyName", nome + " Ltda");
        f.put("fantasyName", nome);
        for (String k : new String[] {"cnpj", "telephone", "email", "cep", "city", "state", "address", "neighborhood",
            "streetComplement", "number", "referencePoint", "cellphone"}) {
            f.put(k, "");
        }
        return e.ctx.cadastros.salvarFornecedor(null, f);
    }

    private static List<Node> todos(Node raiz, Class<?> tipo) {
        List<Node> r = new java.util.ArrayList<>();
        if (tipo.isInstance(raiz)) {
            r.add(raiz);
        }
        if (raiz instanceof javafx.scene.Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) {
                r.addAll(todos(n, tipo));
            }
        }
        if (raiz instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            r.addAll(todos(sp.getContent(), tipo));
        }
        return r;
    }

    @Test
    void escolherOutroFornecedorNaFicha() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        CadastroApi.Fornecedor a = fornecedor(e, "Ficha A " + e.sufixo);
        CadastroApi.Fornecedor b = fornecedor(e, "Ficha B " + e.sufixo);
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", "Ficha UI " + e.sufixo);
        n.put("productCode", "FUI-" + e.sufixo);
        n.put("productQnt", "3");
        n.put("productUnitPrice", "1,0000");
        n.put("productSalePrice", "2,00");
        n.put("unidadeComercial", "UN");
        n.put("productSupplier", a.nome());
        Produto p = e.ctx.produtos.criar(n);
        e.ctx.garantirCatalogo(true);
        Produto doCatalogo = e.ctx.catalogo.porId(p.id());

        Stage[] st = new Stage[1];
        FxTestSupport.naFx(() -> {
            Tela.Navegador nav = new Tela.Navegador() {
                public void ir(String id) { }
                public void novaEntradaNfe() { }
                public void recarregarCatalogo() { }
            };
            ProdutosView v = new ProdutosView(e.ctx, nav);
            st[0] = new Stage();
            st[0].setScene(new Scene(v, 1200, 760));
            st[0].getScene().getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st[0].setX(-20000);
            st[0].show();
            var m = ProdutosView.class.getDeclaredMethod("abrirFicha", Produto.class);
            m.setAccessible(true);
            m.invoke(v, doCatalogo);
            return null;
        });
        Thread.sleep(3500); // listas de categoria/fornecedor chegam por chamada assíncrona

        String escolhido = FxTestSupport.naFx(() -> {
            Window ficha = Window.getWindows().stream().filter(w -> w != st[0] && w.isShowing()).reduce((x, y) -> y).orElseThrow();
            @SuppressWarnings("unchecked")
            ComboBox<String> forn = (ComboBox<String>) todos(ficha.getScene().getRoot(), ComboBox.class).stream()
                    .map(c -> (ComboBox<?>) c).filter(c -> c.getItems().contains(b.nome())).findFirst().orElseThrow();
            log("valor inicial na lista: '" + forn.getValue() + "' (itens: " + forn.getItems().size() + ")");
            forn.getSelectionModel().select(b.nome());
            Button salvar = (Button) todos(ficha.getScene().getRoot(), Button.class).stream()
                    .filter(x -> "Salvar".equals(((Button) x).getText())).findFirst().orElseThrow();
            salvar.fire();
            return forn.getValue();
        });
        Thread.sleep(3500);
        String gravado = e.produto(p.id()).fornecedor();
        log("escolhido: '" + escolhido + "' · gravado na API: '" + gravado + "'");
        FxTestSupport.naFx(() -> {
            List.copyOf(Window.getWindows()).forEach(Window::hide);
            return null;
        });
        assertEquals(b.nome(), gravado, "fornecedor escolhido na ficha foi gravado");
    }

    @Test
    @SuppressWarnings("unchecked")
    void escolherFornecedorNaColunaDaTabela() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        CadastroApi.Fornecedor a = fornecedor(e, "Coluna A " + e.sufixo);
        CadastroApi.Fornecedor b = fornecedor(e, "Coluna B " + e.sufixo);
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", "Coluna Forn " + e.sufixo);
        n.put("productCode", "CFO-" + e.sufixo);
        n.put("productQnt", "3");
        n.put("productUnitPrice", "1,0000");
        n.put("productSalePrice", "2,00");
        n.put("unidadeComercial", "UN");
        n.put("productSupplier", a.nome());
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
            st.setScene(new Scene(v, 1600, 760));
            st.getScene().getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setX(-20000);
            st.show();
            v.ativar();
            ref[0] = v;
            ref[1] = st;
            return null;
        });
        Thread.sleep(5000); // catálogo, categorias e fornecedores chegam por chamadas assíncronas

        FxTestSupport.naFx(() -> {
            ProdutosView v = (ProdutosView) ref[0];
            javafx.scene.control.TableView<Produto> t = (javafx.scene.control.TableView<Produto>) todos(v, javafx.scene.control.TableView.class).get(0);
            int linha = -1;
            for (int i = 0; i < t.getItems().size(); i++) {
                if (t.getItems().get(i).id().equals(p.id())) {
                    linha = i;
                }
            }
            assertTrue(linha >= 0, "produto na tabela");
            var col = t.getColumns().stream().filter(c -> "Fornecedor".equals(br.com.quack.estoque.ui.Ui.titulo(c))).findFirst().orElseThrow();
            assertTrue(col.isVisible(), "coluna Fornecedor visível");
            t.scrollTo(linha);
            t.layout();
            t.edit(linha, (javafx.scene.control.TableColumn<Produto, ?>) col);
            ComboBox<String> lista = (ComboBox<String>) todos(t, ComboBox.class).stream()
                    .map(c -> (ComboBox<String>) c).filter(c -> c.getItems().contains(b.nome())).findFirst().orElseThrow();
            log("coluna Fornecedor: valor atual '" + lista.getValue() + "', " + lista.getItems().size() + " opções");
            lista.setValue(b.nome()); // dispara o onAction -> grava
            return null;
        });
        Thread.sleep(3500);
        String gravado = e.produto(p.id()).fornecedor();
        String naTela = FxTestSupport.naFx(() -> {
            ProdutosView v = (ProdutosView) ref[0];
            javafx.scene.control.TableView<Produto> t = (javafx.scene.control.TableView<Produto>) todos(v, javafx.scene.control.TableView.class).get(0);
            String f = t.getItems().stream().filter(x -> x.id().equals(p.id())).findFirst().orElseThrow().fornecedor();
            ((Stage) ref[1]).hide();
            return f;
        });
        log("coluna: gravado na API '" + gravado + "', na tabela '" + naTela + "'");
        assertEquals(b.nome(), gravado, "fornecedor escolhido na coluna foi gravado");
        assertEquals(b.nome(), naTela, "tabela mostra o fornecedor salvo");
    }
}
