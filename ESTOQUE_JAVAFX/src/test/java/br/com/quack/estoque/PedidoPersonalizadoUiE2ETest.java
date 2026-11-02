package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.api.CadastroApi;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.PedidoEditorDialog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** "Novo pedido" (pedido personalizado, como a Nova Ordem da web) montado pela tela e conferido na API. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class PedidoPersonalizadoUiE2ETest {
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
        if (raiz instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            r.addAll(todos(sp.getContent(), tipo));
        }
        return r;
    }

    private static Produto produto(E2eSupport e, String nome, String codigo, String custo) {
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", nome + " " + e.sufixo);
        n.put("productCode", codigo + "-" + e.sufixo);
        n.put("productQnt", "1");
        n.put("productUnitPrice", custo);
        n.put("productSalePrice", "9,00");
        n.put("unidadeComercial", "UN");
        return e.ctx.produtos.criar(n);
    }

    @Test
    @SuppressWarnings("unchecked")
    void montarPedidoPersonalizadoPelaTela() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        Map<String, String> f = new LinkedHashMap<>();
        f.put("companyName", "Pers " + e.sufixo + " Ltda");
        f.put("fantasyName", "Pers " + e.sufixo);
        for (String k : new String[] {"cnpj", "telephone", "email", "cep", "city", "state", "address", "neighborhood",
            "streetComplement", "number", "referencePoint", "cellphone"}) {
            f.put(k, "");
        }
        CadastroApi.Fornecedor forn = e.ctx.cadastros.salvarFornecedor(null, f);
        Produto a = produto(e, "Pers Arroz", "PRA", "4,0000");
        Produto b = produto(e, "Pers Feijao", "PRB", "6,5000");
        e.ctx.garantirCatalogo(true);
        List<CadastroApi.Fornecedor> fornecedores = e.ctx.cadastros.fornecedores();

        AtomicReference<String> criado = new AtomicReference<>();
        Stage[] dono = new Stage[1];
        FxTestSupport.naFx(() -> {
            dono[0] = new Stage();
            dono[0].setScene(new Scene(new javafx.scene.layout.StackPane(), 400, 300));
            dono[0].setX(-20000);
            dono[0].show();
            PedidoEditorDialog.novo(dono[0], e.ctx, fornecedores, criado::set);
            return null;
        });
        Window[] ficha = new Window[1];
        FxTestSupport.naFx(() -> {
            ficha[0] = Window.getWindows().stream().filter(w -> w != dono[0] && w.isShowing()).reduce((x, y) -> y).orElseThrow();
            Node raiz = ficha[0].getScene().getRoot();
            ComboBox<String> fornCombo = (ComboBox<String>) todos(raiz, ComboBox.class).stream()
                    .filter(c -> c.getItems().contains(forn.nome())).findFirst().orElseThrow();
            fornCombo.setValue(forn.nome());
            todos(raiz, DatePicker.class).get(0).setValue(LocalDate.of(2026, 12, 15));
            List<TextField> campos = todos(raiz, TextField.class);
            campos.stream().filter(t -> "Ex.: À vista, 30 dias, 30/60".equals(t.getPromptText())).findFirst().orElseThrow()
                    .setText("30 dias");
            TextField busca = campos.stream().filter(t -> t.getPromptText() != null && t.getPromptText().startsWith("Digite 2 letras"))
                    .findFirst().orElseThrow();
            busca.setText(a.codigo());
            busca.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        Thread.sleep(1500);
        FxTestSupport.naFx(() -> {
            Node raiz = ficha[0].getScene().getRoot();
            TextField busca = todos(raiz, TextField.class).stream()
                    .filter(t -> t.getPromptText() != null && t.getPromptText().startsWith("Digite 2 letras")).findFirst().orElseThrow();
            busca.setText(b.codigo());
            busca.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        Thread.sleep(1500);
        FxTestSupport.naFx(() -> {
            Node raiz = ficha[0].getScene().getRoot();
            TableView<?> t = todos(raiz, TableView.class).get(0);
            assertEquals(2, t.getItems().size(), "dois produtos no pedido");
            // quantidade do primeiro: 12
            var col = t.getColumns().stream().filter(c -> "Quantidade".equals(c.getText())).findFirst().orElseThrow();
            t.edit(0, (javafx.scene.control.TableColumn) col);
            TextField q = (TextField) t.lookup(".rg-celula-campo");
            q.setText("12");
            q.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            todos(raiz, Button.class).stream().filter(x -> "Criar pedido".equals(x.getText())).findFirst().orElseThrow().fire();
            return null;
        });
        for (int i = 0; i < 30 && criado.get() == null; i++) {
            Thread.sleep(300);
        }
        assertNotNull(criado.get(), "pedido criado");
        JsonNode oc = e.ctx.compras.obter(criado.get());
        log("pedido " + criado.get() + ": " + oc.path("supplierName").asText() + " · " + oc.path("condicaoPagamento").asText()
                + " · previsão " + oc.path("previsaoEntrega").asText() + " · itens " + oc.path("itens").size());
        assertEquals(forn.id(), oc.path("supplierId").asText());
        assertEquals("30 dias", oc.path("condicaoPagamento").asText());
        assertEquals("2026-12-15", oc.path("previsaoEntrega").asText().substring(0, 10));
        assertEquals(2, oc.path("itens").size());
        JsonNode itemA = null;
        for (JsonNode it : oc.path("itens")) {
            if (it.path("productCode").asText().equals(a.codigo())) {
                itemA = it;
            }
        }
        assertNotNull(itemA);
        assertEquals(0, new BigDecimal("12").compareTo(itemA.path("quantity").decimalValue()), "quantidade editada");
        assertEquals(0, new BigDecimal("4").compareTo(itemA.path("unitCost").decimalValue()), "custo do cadastro");
        FxTestSupport.naFx(() -> {
            List.copyOf(Window.getWindows()).forEach(Window::hide);
            return null;
        });
    }
}
