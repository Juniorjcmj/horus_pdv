package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.domain.Produto;
import br.com.quack.estoque.persistence.JsonFiles;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Busca de produto com lista enquanto digita: começa no 2º caractere; setas + Enter escolhem; código exato entra direto. */
class BuscaProdutoCampoTest {
    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private static Produto produto(String codigo, String nome) {
        ObjectNode n = JsonFiles.MAPPER.createObjectNode();
        n.put("id", "id-" + codigo);
        n.put("productCode", codigo);
        n.put("productName", nome);
        n.put("productQnt", "5");
        n.put("productUnitPrice", "2,00");
        n.put("productSalePrice", "4,00");
        n.put("unidadeComercial", "UN");
        return Produto.de(n);
    }

    private static void tecla(TextField f, KeyCode k) {
        f.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", k, false, false, false, false));
    }

    @Test
    void sugereAPartirDoSegundoCaractere() throws Exception {
        List<Produto> catalogo = List.of(produto("10", "Arroz Tio João 5 kg"), produto("11", "Arroz Camil 1 kg"),
                produto("7891234567895", "Feijão Carioca 1 kg"));
        List<Produto> escolhidos = new ArrayList<>();
        Object[] r = FxTestSupport.naFx(() -> {
            TextField f = new TextField();
            BuscaProdutoCampo b = new BuscaProdutoCampo(f,
                    t -> catalogo.stream().filter(p -> p.nome().toLowerCase().contains(t.toLowerCase()) || p.codigo().startsWith(t)).toList(),
                    t -> catalogo.stream().filter(p -> p.codigo().equals(t)).findFirst().orElse(null), escolhidos::add);
            Stage st = new Stage();
            st.setScene(new Scene(new StackPane(f), 600, 120));
            st.setX(-10000);
            st.show();
            return new Object[] {f, b, st};
        });
        TextField f = (TextField) r[0];
        BuscaProdutoCampo b = (BuscaProdutoCampo) r[1];

        // 1 caractere: nada
        FxTestSupport.naFx(() -> {
            f.setText("a");
            b.atualizar();
            assertTrue(b.sugestoes().isEmpty(), "com 1 caractere não sugere");
            assertFalse(b.listaAberta());
            return null;
        });
        // a partir do 2º caractere a lista aparece ("arr" não pega "Carioca")
        FxTestSupport.naFx(() -> {
            f.setText("arr");
            b.atualizar();
            assertEquals(2, b.sugestoes().size(), "dois arrozes");
            assertTrue(b.listaAberta(), "lista aberta abaixo do campo");
            // seta para baixo + Enter escolhe o segundo
            tecla(f, KeyCode.DOWN);
            tecla(f, KeyCode.ENTER);
            return null;
        });
        assertEquals("Arroz Camil 1 kg", escolhidos.get(0).nome());
        assertEquals("", FxTestSupport.naFx(f::getText), "campo limpo depois de escolher");

        // código de barras exato + Enter entra direto (leitor)
        FxTestSupport.naFx(() -> {
            f.setText("7891234567895");
            tecla(f, KeyCode.ENTER);
            ((Stage) r[2]).hide();
            return null;
        });
        assertEquals("Feijão Carioca 1 kg", escolhidos.get(1).nome());
    }
}
