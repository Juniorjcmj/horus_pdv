package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Janela com rolagem (formularioRolavel): o texto de opções e caixas de marcar não pode sair branco sobre o fundo branco
 * (aconteceu no "Novo inventário": as opções apareciam sem texto).
 */
class ModalTextoVisivelTest {
    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    @Test
    void opcoesECaixasComTextoEscuro() throws Exception {
        Color[] cores = FxTestSupport.naFx(() -> {
            Stage dono = new Stage();
            dono.setScene(new Scene(new StackPane(), 300, 200));
            dono.getScene().getStylesheets().setAll(Fx.stylesheets());
            dono.setX(-20000);
            dono.show();
            RadioButton r = new RadioButton("Loja toda");
            CheckBox c = new CheckBox("Só com saldo");
            Modal m = Modal.formularioRolavel(dono, "Teste", null, new VBox(8, r, c), "OK", 400, 300, () -> true);
            m.mostrar();
            m.stage().getScene().getRoot().applyCss();
            m.stage().getScene().getRoot().layout();
            Color cr = (Color) ((Text) r.lookup(".text")).getFill();
            Color cc = (Color) ((Text) c.lookup(".text")).getFill();
            m.stage().hide();
            dono.hide();
            return new Color[] {cr, cc};
        });
        for (Color c : cores) {
            assertTrue(c.getBrightness() < 0.5, "texto legível (escuro), veio " + c);
        }
    }
}
