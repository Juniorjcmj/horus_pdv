package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Indicador de carregamento: trava os botões enquanto há consulta e só some quando a última termina. */
class CarregandoTest {
    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    @Test
    void travaBotoesEsomeSoNoFim() throws Exception {
        Button calcular = FxTestSupport.naFx(() -> new Button("Calcular"));
        Button gerar = FxTestSupport.naFx(() -> new Button("Gerar"));
        Carregando c = FxTestSupport.naFx(() -> {
            TableView<String> tabela = new TableView<>();
            tabela.setPlaceholder(new Label("Nada para mostrar."));
            Carregando car = new Carregando(tabela, calcular, gerar);
            Scene sc = new Scene(car, 600, 300);
            sc.getStylesheets().setAll(Fx.stylesheets());
            assertFalse(car.ativo());
            car.mostrar("Calculando a sugestão de compra…");
            car.mostrar("Gerando pedidos…");
            assertTrue(car.ativo());
            assertTrue(calcular.isDisabled() && gerar.isDisabled(), "botões travados enquanto carrega");
            return car;
        });
        FxTestSupport.salvarSePedido(FxTestSupport.imagem(c, 1), "carregando");
        FxTestSupport.naFx(() -> {
            c.ocultar();
            assertTrue(c.ativo(), "ainda há uma consulta pendente");
            assertTrue(calcular.isDisabled());
            c.ocultar();
            assertFalse(c.ativo());
            assertFalse(calcular.isDisabled() || gerar.isDisabled(), "botões liberados no fim");
            c.ocultar(); // a mais não quebra
            assertFalse(c.ativo());
            return null;
        });
    }
}
