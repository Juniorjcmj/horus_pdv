package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Edição com um clique: abre o campo com o valor, Enter confirma (vai para o onEditCommit), Esc cancela. */
class CelulaEditavelTest {
    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    @Test
    void enterConfirmaEscCancela() throws Exception {
        List<String> salvos = new ArrayList<>();
        TableView<String[]> t = FxTestSupport.naFx(() -> {
            TableView<String[]> tabela = new TableView<>();
            TableColumn<String[], String> preco = new TableColumn<>("Preço");
            preco.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue()[1]));
            CelulaEditavel.em(preco, l -> l[1], true, l -> true);
            preco.setOnEditCommit(e -> salvos.add(e.getRowValue()[0] + "=" + e.getNewValue()));
            tabela.getColumns().add(preco);
            tabela.setEditable(true);
            tabela.getItems().add(new String[] {"cerveja", "5,00"});
            Stage st = new Stage();
            st.setScene(new Scene(tabela, 300, 200));
            st.setX(-10000);
            st.show();
            tabela.layout();
            return tabela;
        });
        // Enter confirma
        FxTestSupport.naFx(() -> {
            t.edit(0, t.getColumns().get(0));
            TextField f = (TextField) t.lookup(".rg-celula-campo");
            assertNotNull(f, "campo aberto com um clique/edição");
            assertEquals("5,00", f.getText());
            f.setText("6,50");
            f.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        assertEquals(List.of("cerveja=6,50"), salvos);
        // Esc cancela
        FxTestSupport.naFx(() -> {
            t.edit(0, t.getColumns().get(0));
            TextField f = (TextField) t.lookup(".rg-celula-campo");
            f.setText("9,99");
            f.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertNull(t.getEditingCell(), "edição cancelada");
            t.getScene().getWindow().hide();
            return null;
        });
        assertEquals(1, salvos.size(), "Esc não salva");
    }

    @Test
    void listaGravaAoEscolher() throws Exception {
        List<String> salvos = new ArrayList<>();
        TableView<String[]> t = FxTestSupport.naFx(() -> {
            TableView<String[]> tabela = new TableView<>();
            TableColumn<String[], String> forn = new TableColumn<>("Fornecedor");
            forn.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue()[1]));
            CelulaEditavel.lista(forn, List.of("(sem fornecedor)", "Forn A", "Forn B"), l -> true);
            forn.setOnEditCommit(e -> salvos.add(e.getRowValue()[0] + "=" + e.getNewValue()));
            tabela.getColumns().add(forn);
            tabela.setEditable(true);
            tabela.getItems().add(new String[] {"cerveja", "Forn A"});
            Stage st = new Stage();
            st.setScene(new Scene(tabela, 300, 200));
            st.setX(-10000);
            st.show();
            tabela.layout();
            return tabela;
        });
        FxTestSupport.naFx(() -> {
            t.edit(0, t.getColumns().get(0));
            @SuppressWarnings("unchecked")
            javafx.scene.control.ComboBox<String> c = (javafx.scene.control.ComboBox<String>) t.lookup(".combo-box");
            assertNotNull(c, "lista aberta");
            c.setValue("Forn B");
            return null;
        });
        Thread.sleep(300);
        FxTestSupport.naFx(() -> {
            t.getScene().getWindow().hide();
            return null;
        });
        assertEquals(List.of("cerveja=Forn B"), salvos, "escolher na lista grava");
        assertNull(FxTestSupport.naFx(t::getEditingCell), "sai da edição depois de gravar");
    }
}
