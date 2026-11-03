package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import java.math.BigDecimal;
import java.util.List;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Edição de pedido: a quantidade digitada vale mesmo sem apertar Enter (sair do campo ou clicar em "Salvar"). Antes,
 * com o TextFieldTableCell padrão, o valor digitado se perdia e o pedido era salvo com a quantidade antiga.
 */
class PedidoEditorCelulaTest {
    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    @Test
    void quantidadeDigitadaValeAoSairDoCampoEComEnter() throws Exception {
        PedidoEditorDialog.Linha linha = new PedidoEditorDialog.Linha("A1", "Água 500 ml", new BigDecimal("5"), new BigDecimal("2"));
        int[] mudancas = {0};
        Object[] ui = FxTestSupport.naFx(() -> {
            TableView<PedidoEditorDialog.Linha> t = new TableView<>();
            t.setEditable(true);
            PedidoEditorDialog.colunaEditavel(t, "Quantidade", l -> l.quantidade, 90, true, () -> mudancas[0]++);
            PedidoEditorDialog.colunaEditavel(t, "Custo", l -> l.custo, 90, true, () -> mudancas[0]++);
            t.getItems().add(linha);
            Button salvar = new Button("Salvar pedido");
            Stage st = new Stage();
            st.setScene(new Scene(new VBox(t, salvar), 400, 260));
            st.setX(-10000);
            st.show();
            t.layout();
            return new Object[] {t, salvar};
        });
        @SuppressWarnings("unchecked")
        TableView<PedidoEditorDialog.Linha> t = (TableView<PedidoEditorDialog.Linha>) ui[0];
        Button salvar = (Button) ui[1];

        // 1) digita 12 e clica em "Salvar" (o campo perde o foco), sem Enter
        FxTestSupport.naFx(() -> {
            t.edit(0, t.getColumns().get(0));
            TextField f = (TextField) t.lookup(".rg-celula-campo");
            assertNotNull(f, "campo de edição aberto");
            assertEquals("5", f.getText());
            f.setText("12");
            salvar.requestFocus();
            return null;
        });
        assertEquals("12", linha.quantidade.get(), "quantidade digitada vale ao sair do campo");
        assertEquals(0, new BigDecimal("12").compareTo(PedidoEditorDialog.itensValidos(List.of(linha)).get(0).quantity()),
                "vai para o PUT com a quantidade nova");

        // 2) Enter também grava (custo)
        FxTestSupport.naFx(() -> {
            t.edit(0, t.getColumns().get(1));
            TextField f = (TextField) t.lookup(".rg-celula-campo");
            f.setText("2,35");
            f.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            t.getScene().getWindow().hide();
            return null;
        });
        assertEquals("2,35", linha.custo.get());
        assertTrue(mudancas[0] >= 2, "total recalculado a cada mudança");
    }
}
