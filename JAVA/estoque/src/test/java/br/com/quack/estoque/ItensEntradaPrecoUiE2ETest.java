package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.domain.Fmt;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.ItensEntradaDialog;
import br.com.quack.estoque.ui.Ui;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Itens da entrada: preço de venda, lucro e markup editáveis, sobre o custo da nota. Produto com custo 3,00 e preço
 * 5,00 recebido por 4,00 na entrada manual.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class ItensEntradaPrecoUiE2ETest {
    @TempDir
    static Path home;

    @SuppressWarnings("unchecked")
    private static TableView<Object> tabela(Node raiz) {
        if (raiz instanceof TableView<?> t) {
            return (TableView<Object>) t;
        }
        if (raiz instanceof javafx.scene.Parent p) {
            for (Node n : p.getChildrenUnmodifiable()) {
                TableView<Object> t = tabela(n);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    /** Tabela do modal "Itens da entrada" (a janela dona não tem tabela). */
    private static TableView<Object> tabelaDoModal(Stage dono) {
        for (Window w : Window.getWindows()) {
            if (w != dono && w.getScene() != null) {
                TableView<Object> t = tabela(w.getScene().getRoot());
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static TableColumn<Object, ?> coluna(TableView<Object> t, String titulo) {
        return t.getColumns().stream().filter(c -> titulo.equals(Ui.titulo(c))).findFirst().orElseThrow();
    }

    private static String celula(TableView<Object> t, String titulo) {
        return String.valueOf(coluna(t, titulo).getCellData(t.getItems().get(0)));
    }

    private static void editar(TableView<Object> t, String titulo, String valor) {
        t.layout();
        t.edit(0, coluna(t, titulo));
        TextField f = (TextField) t.lookup(".rg-celula-campo");
        assertNotNull(f, "campo de " + titulo + " aberto");
        f.setText(valor);
        f.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    private static String[] linha(TableView<Object> t) {
        return new String[] {celula(t, "Preço venda"), celula(t, "Lucro R$"), celula(t, "Markup")};
    }

    @Test
    void precoLucroEMarkupSaoEditaveisSobreOCustoDaNota() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        Produto p = e.criarProduto("Itens Entrada", "IEP", "2", "3,00", "5,00", null, "0", "0");

        ObjectNode req = JsonFiles.MAPPER.createObjectNode();
        req.put("operacaoId", UUID.randomUUID().toString());
        req.put("dataCompra", java.time.LocalDate.now().toString());
        req.put("tipoDocumento", "cupom");
        req.put("numeroDocumento", "IEP-" + e.sufixo);
        ArrayNode itens = req.putArray("itens");
        ObjectNode i = itens.addObject();
        i.put("numeroItem", 1);
        i.put("produtoExistenteId", p.id());
        i.put("produtoExistenteNome", p.nome());
        i.put("productCode", p.codigo());
        i.put("productName", p.nome());
        i.put("unidadeComercial", p.unidade());
        i.put("quantidadeCompra", Fmt.qtyApi(new BigDecimal("3")));
        i.put("unidadeCompra", "UN");
        i.put("fatorConversao", Fmt.qtyApi(BigDecimal.ONE));
        i.put("custoCompra", Fmt.cost4Api(new BigDecimal("4")));
        i.put("quantidade", Fmt.qtyApi(new BigDecimal("3")));
        i.put("precoCusto", Fmt.cost4Api(new BigDecimal("4")));
        i.put("alterarPrecoVenda", false);
        i.put("precoVenda", Fmt.moneyApi(p.preco()));
        e.ctx.produtos.entradaManual(req);
        JsonNode resumo = e.ctx.entradas.listarNotas("IEP-" + e.sufixo, 1, 5).path("notas").get(0);
        assertNotNull(resumo, "entrada na lista");

        Stage[] dono = new Stage[1];
        FxTestSupport.naFx(() -> {
            Stage st = new Stage();
            st.setScene(new Scene(new StackPane(), 400, 300));
            st.setX(-20000);
            st.show();
            dono[0] = st;
            ItensEntradaDialog.abrir(e.ctx, st, resumo);
            return null;
        });
        Thread.sleep(4000);

        String[] inicial = FxTestSupport.naFx(() -> linha(tabelaDoModal(dono[0])));
        log("inicial: preço " + inicial[0] + " · lucro " + inicial[1] + " · markup " + inicial[2]);
        assertArrayEquals(new String[] {"5,00", "1,00", "25,00%"}, inicial, "sobre o custo da nota (4,00), não o antigo (3,00)");

        // markup 50% sobre 4,00 -> 6,00
        FxTestSupport.naFx(() -> {
            editar(tabelaDoModal(dono[0]), "Markup", "50");
            return null;
        });
        Thread.sleep(2500);
        assertEquals(0, new BigDecimal("6.00").compareTo(e.produto(p.id()).preco()), "markup 50% -> R$ 6,00");
        assertArrayEquals(new String[] {"6,00", "2,00", "50,00%"}, FxTestSupport.naFx(() -> linha(tabelaDoModal(dono[0]))));

        // lucro R$ 3,00 -> 7,00
        FxTestSupport.naFx(() -> {
            editar(tabelaDoModal(dono[0]), "Lucro R$", "3");
            return null;
        });
        Thread.sleep(2500);
        assertEquals(0, new BigDecimal("7.00").compareTo(e.produto(p.id()).preco()), "lucro R$ 3 -> R$ 7,00");

        // preço direto 8,00 (usa a versão nova do preço: não dá conflito depois das duas gravações)
        FxTestSupport.naFx(() -> {
            editar(tabelaDoModal(dono[0]), "Preço venda", "8");
            return null;
        });
        Thread.sleep(2500);
        assertEquals(0, new BigDecimal("8.00").compareTo(e.produto(p.id()).preco()), "preço R$ 8,00");
        String[] fim = FxTestSupport.naFx(() -> {
            String[] r = linha(tabelaDoModal(dono[0]));
            for (Window w : java.util.List.copyOf(Window.getWindows())) {
                w.hide();
            }
            return r;
        });
        log("fim: preço " + fim[0] + " · lucro " + fim[1] + " · markup " + fim[2]);
        assertArrayEquals(new String[] {"8,00", "4,00", "100,00%"}, fim);
    }
}
