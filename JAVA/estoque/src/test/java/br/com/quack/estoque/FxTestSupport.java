package br.com.quack.estoque;

import br.com.quack.estoque.ui.Fx;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

/** Inicia o toolkit do JavaFX uma vez, carrega as fontes embutidas e roda blocos na FX thread. */
public final class FxTestSupport {
    private static boolean iniciado;

    private FxTestSupport() {
    }

    public static synchronized void iniciar() throws Exception {
        if (iniciado) {
            return;
        }
        CountDownLatch ok = new CountDownLatch(1);
        try {
            Platform.startup(ok::countDown);
        } catch (IllegalStateException jaIniciado) {
            ok.countDown();
        }
        if (!ok.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX não iniciou");
        }
        Platform.setImplicitExit(false); // fechar a última janela de teste não pode derrubar o toolkit
        naFx(() -> {
            Fx.loadFonts();
            return null;
        });
        iniciado = true;
    }

    public static <T> T naFx(Callable<T> bloco) throws Exception {
        FutureTask<T> t = new FutureTask<>(bloco);
        Platform.runLater(t);
        return t.get(30, TimeUnit.SECONDS);
    }

    /** Desenha o nó fora da tela e devolve a imagem (para conferir pixels ou gravar PNG). */
    public static BufferedImage imagem(Node no, double escala) throws Exception {
        return naFx(() -> {
            if (no.getScene() == null) {
                Pane host = new Pane(no);
                new Scene(host);
            }
            no.applyCss();
            SnapshotParameters sp = new SnapshotParameters();
            sp.setTransform(new javafx.scene.transform.Scale(escala, escala));
            WritableImage wi = no.snapshot(sp, null);
            PixelReader pr = wi.getPixelReader();
            BufferedImage bi = new BufferedImage((int) wi.getWidth(), (int) wi.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < bi.getHeight(); y++) {
                for (int x = 0; x < bi.getWidth(); x++) {
                    bi.setRGB(x, y, pr.getArgb(x, y) | 0xFF000000);
                }
            }
            return bi;
        });
    }

    /** Grava PNG em -Dquack.test.png=<pasta> (opcional, para olhar o resultado). */
    public static void salvarSePedido(BufferedImage img, String nome) {
        String dir = System.getProperty("quack.test.png");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            File f = new File(dir, nome + ".png");
            f.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", f);
        } catch (java.io.IOException e) {
            System.err.println("PNG não gravado: " + e.getMessage());
        }
    }
}
