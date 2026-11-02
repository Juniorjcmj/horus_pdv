package br.com.quack.estoque;

import static br.com.quack.estoque.E2eSupport.log;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.domain.DomainException;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import br.com.quack.estoque.ui.Tela;
import br.com.quack.estoque.ui.ValidadeView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Controle de validade → "Colocar em promoção": a promoção nasce com o produto do lote e vale até o vencimento. */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class ValidadePromocaoUiE2ETest {
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

    @Test
    void preenchimentoDosLotes() throws Exception {
        var m = JsonFiles.MAPPER;
        ObjectNode pn = m.createObjectNode();
        pn.put("id", "p1");
        pn.put("productCode", "1");
        pn.put("productName", "Iogurte");
        pn.put("productQnt", "5");
        pn.put("productUnitPrice", "2,00");
        pn.put("productSalePrice", "4,00");
        Produto iogurte = Produto.de(pn);
        JsonNode a = m.readTree("{\"produtoId\":\"p1\",\"productName\":\"Iogurte\",\"dataValidade\":\"2026-11-10\",\"diasParaVencer\":8}");
        JsonNode b = m.readTree("{\"produtoId\":\"p1\",\"productName\":\"Iogurte\",\"dataValidade\":\"2026-11-05\",\"diasParaVencer\":3}");
        JsonNode vencido = m.readTree("{\"produtoId\":\"p1\",\"productName\":\"Iogurte\",\"dataValidade\":\"2026-10-30\",\"diasParaVencer\":-2}");
        var pre = ValidadeView.preenchimento(List.of(a, b), id -> iogurte);
        assertEquals(1, pre.produtos().size(), "mesmo produto não repete");
        assertEquals(LocalDate.of(2026, 11, 5).atTime(23, 59), pre.fim(), "vale até o lote que vence primeiro");
        assertEquals("Validade: Iogurte", pre.nome());
        assertThrows(DomainException.class, () -> ValidadeView.preenchimento(List.of(vencido), id -> iogurte), "vencido não");
        assertThrows(DomainException.class, () -> ValidadeView.preenchimento(List.of(), id -> iogurte), "nada selecionado");
    }

    @Test
    @SuppressWarnings("unchecked")
    void colocarLoteEmPromocaoPelaTela() throws Exception {
        FxTestSupport.iniciar();
        E2eSupport e = new E2eSupport(home);
        e.loginAdmin();
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("productName", "Promo Validade " + e.sufixo);
        n.put("productCode", "PVA-" + e.sufixo);
        n.put("productQnt", "10");
        n.put("productUnitPrice", "2,0000");
        n.put("productSalePrice", "5,00");
        n.put("unidadeComercial", "UN");
        n.put("controlaValidade", true);
        Produto p = e.ctx.produtos.criar(n);
        LocalDate vence = LocalDate.now().plusDays(4);
        e.ctx.lotes.registrar(p.id(), vence.toString(), new BigDecimal("6"), "PV-" + e.sufixo);
        e.ctx.garantirCatalogo(true);

        Object[] ref = new Object[2];
        FxTestSupport.naFx(() -> {
            Tela.Navegador nav = new Tela.Navegador() {
                public void ir(String id) { }
                public void novaEntradaNfe() { }
                public void recarregarCatalogo() { }
            };
            ValidadeView v = new ValidadeView(e.ctx, nav);
            Stage st = new Stage();
            st.setScene(new Scene(v, 1500, 760));
            st.getScene().getStylesheets().setAll(br.com.quack.estoque.ui.Fx.stylesheets());
            st.setX(-20000);
            st.show();
            v.ativar();
            ref[0] = v;
            ref[1] = st;
            return null;
        });
        Thread.sleep(3000);
        FxTestSupport.naFx(() -> {
            ValidadeView v = (ValidadeView) ref[0];
            // a aba Alertas é a primeira; procura a tabela que tem o lote do produto
            TableView<JsonNode> t = null;
            JsonNode lote = null;
            for (TableView<?> x : todos(v, TableView.class)) {
                for (Object o : x.getItems()) {
                    if (o instanceof JsonNode j && j.path("produtoId").asText().equals(p.id())) {
                        t = (TableView<JsonNode>) x;
                        lote = j;
                    }
                }
            }
            assertNotNull(lote, "lote do produto na lista de alertas");
            t.getSelectionModel().clearSelection();
            t.getSelectionModel().select(lote);
            // botão da mesma aba da tabela
            todos(t.getParent(), Button.class).stream().filter(b -> "Colocar em promoção".equals(b.getText()) && b.isVisible())
                    .findFirst().orElseThrow().fire();
            return null;
        });
        Thread.sleep(2500);
        String[] visto = FxTestSupport.naFx(() -> {
            Window form = Window.getWindows().stream().filter(w -> w.isShowing() && w != ref[1]).reduce((x, y) -> y).orElseThrow();
            Node raiz = form.getScene().getRoot();
            ListView<Produto> lista = todos(raiz, ListView.class).stream().filter(l -> !l.getItems().isEmpty()
                    && l.getItems().get(0) instanceof Produto).findFirst().orElseThrow();
            TextField nome = todos(raiz, TextField.class).stream()
                    .filter(t -> "Ex.: Festival de Cervejas, Oferta da Semana".equals(t.getPromptText())).findFirst().orElseThrow();
            TextField pct = todos(raiz, TextField.class).stream().filter(t -> "Ex.: 10 para 10%".equals(t.getPromptText()))
                    .findFirst().orElseThrow();
            pct.setText("25");
            String r0 = nome.getText();
            String r1 = lista.getItems().get(0).id();
            todos(raiz, Button.class).stream().filter(b -> "Criar promoção".equals(b.getText())).findFirst().orElseThrow().fire();
            return new String[] {r0, r1};
        });
        assertEquals("Validade: " + p.nome(), visto[0], "nome sugerido");
        assertEquals(p.id(), visto[1], "produto do lote já na lista");
        Thread.sleep(2500);

        JsonNode criada = null;
        for (JsonNode j : e.ctx.promocoes.listar()) {
            if (j.path("nome").asText().equals("Validade: " + p.nome())) {
                criada = j;
            }
        }
        assertNotNull(criada, "promoção criada pela tela de validade");
        assertEquals(p.id(), criada.path("produtoIds").get(0).asText());
        assertEquals(0, new BigDecimal("25").compareTo(criada.path("valorDesconto").decimalValue()));
        var fim = br.com.quack.estoque.ui.PromocoesView.instante(criada.path("fimVigencia").asText())
                .atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
        assertEquals(vence.atTime(23, 59), fim, "vale até o vencimento do lote");
        log("promoção \"" + criada.path("nome").asText() + "\" até " + fim);
        e.ctx.promocoes.excluir(criada.path("id").asText());
        FxTestSupport.naFx(() -> {
            List.copyOf(Window.getWindows()).forEach(Window::hide);
            return null;
        });
    }
}
