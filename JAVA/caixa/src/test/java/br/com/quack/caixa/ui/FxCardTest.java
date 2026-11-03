package br.com.quack.caixa.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** O fundo escurecido do modal some ao fechar, mesmo se o modal usar setOnHidden (bug da pré-visualização). */
class FxCardTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ok = new CountDownLatch(1);
        try {
            Platform.startup(ok::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ok.countDown();
        }
        assertTrue(ok.await(10, TimeUnit.SECONDS));
    }

    @Test
    void fecharModalTiraOEscurecimento() throws Exception {
        AtomicReference<Object> during = new AtomicReference<>();
        AtomicReference<Object> after = new AtomicReference<>("não rodou");
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Stage owner = new Stage(StageStyle.UNDECORATED);
                StackPane ownerRoot = new StackPane();
                owner.setScene(new Scene(ownerRoot, 200, 200));
                owner.setX(-10000);
                owner.show();

                Stage modal = Fx.card(owner, new VBox());
                modal.setOnHidden(e -> { }); // como a pré-visualização fazia: não pode apagar a restauração
                modal.setX(-10000);
                modal.show();
                during.set(ownerRoot.getEffect());
                modal.close();
                after.set(ownerRoot.getEffect());
                owner.close();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNotNull(during.get(), "com o modal aberto o fundo fica escurecido");
        assertNull(after.get(), "ao fechar o modal o fundo volta ao normal");
    }
}
