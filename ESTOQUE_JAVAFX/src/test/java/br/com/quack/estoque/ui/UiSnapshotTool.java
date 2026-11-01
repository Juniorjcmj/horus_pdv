package br.com.quack.estoque.ui;

import br.com.quack.estoque.AppContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javax.imageio.ImageIO;

/**
 * Ferramenta de desenvolvimento: abre o aplicativo fora da área visível contra uma API real, navega pelas telas principais
 * e salva um PNG de cada uma (sem tocar em teclado ou mouse). Uso:
 * java -cp "quack-estoque.jar;test-classes" br.com.quack.estoque.ui.UiSnapshotTool pastaSaida urlApi email senha
 */
public final class UiSnapshotTool {
    private UiSnapshotTool() {
    }

    public static void main(String[] args) throws Exception {
        File saida = new File(args[0]);
        saida.mkdirs();
        System.setProperty("quack.home", new File(saida, "home").getAbsolutePath());
        Platform.startup(Fx::loadFonts);
        Platform.setImplicitExit(false);
        List<String> telas = executar(saida, args[1], args[2], args[3], "");
        System.out.println("Telas salvas: " + telas);
        Platform.exit();
    }

    /** Devolve os ids das telas que renderizaram. */
    public static List<String> executar(File saida, String url, String email, String senha, String prefixo) throws Exception {
        saida.mkdirs();
        AtomicReference<MainView> principal = new AtomicReference<>();
        AtomicReference<Stage> janela = new AtomicReference<>();
        AtomicReference<AppContext> ctxRef = new AtomicReference<>();
        CountDownLatch pronto = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Fx.loadFonts();
                AppContext ctx = new AppContext();
                ctx.api.setBaseUrl(url);
                ctx.entrar(email, senha);
                ctx.garantirCatalogo(true);
                String etq = System.getProperty("quack.test.etiqueta");
                if (etq != null) {
                    var p = ctx.catalogo.buscar(etq, 1);
                    if (!p.isEmpty()) {
                        ctx.etiquetas.adicionar(p.get(0).id(), 2);
                    }
                }
                ctxRef.set(ctx);
                MainView mv = new MainView(ctx, () -> {
                });
                Stage st = new Stage(StageStyle.UNDECORATED);
                String[] tam = System.getProperty("quack.test.size", "1360x820").split("x");
                String z = System.getProperty("quack.test.zoom");
                if (z != null) {
                    Zoom.set(Double.parseDouble(z));
                }
                Scene sc = new Scene(z == null ? mv : Zoom.envolver(mv), Double.parseDouble(tam[0]), Double.parseDouble(tam[1]));
                sc.getStylesheets().setAll(Fx.stylesheets());
                st.setScene(sc);
                st.setX(-20000);
                st.setY(0);
                st.show();
                mv.instalar(sc);
                principal.set(mv);
                janela.set(st);
            } catch (RuntimeException e) {
                e.printStackTrace();
            } finally {
                pronto.countDown();
            }
        });
        if (!pronto.await(60, TimeUnit.SECONDS) || principal.get() == null) {
            throw new IllegalStateException("O aplicativo não abriu (login falhou?)");
        }
        List<String> ids = List.of("inicio", "produtos", "entradas", "ajustes", "inventario", "etiquetas", "compras", "relatorios", "analise-dashboard", "analise-abc", "analise-reposicao", "cadastros");
        java.util.ArrayList<String> ok = new java.util.ArrayList<>();
        for (String id : ids) {
            if (!principal.get().possui(id)) {
                continue;
            }
            CountDownLatch navegou = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    principal.get().ir(id);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                } finally {
                    navegou.countDown();
                }
            });
            navegou.await(20, TimeUnit.SECONDS);
            Thread.sleep(1800); // dados chegam por chamadas assíncronas
            CountDownLatch foto = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    Scene sc = janela.get().getScene();
                    sc.getRoot().applyCss();
                    WritableImage wi = sc.getRoot().snapshot(new SnapshotParameters(), null);
                    BufferedImage bi = new BufferedImage((int) wi.getWidth(), (int) wi.getHeight(), BufferedImage.TYPE_INT_RGB);
                    PixelReader pr = wi.getPixelReader();
                    for (int y = 0; y < bi.getHeight(); y++) {
                        for (int x = 0; x < bi.getWidth(); x++) {
                            bi.setRGB(x, y, pr.getArgb(x, y) | 0xFF000000);
                        }
                    }
                    ImageIO.write(bi, "png", new File(saida, prefixo + id + ".png"));
                    ok.add(id);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    foto.countDown();
                }
            });
            foto.await(30, TimeUnit.SECONDS);
        }
        CountDownLatch fechar = new CountDownLatch(1);
        Platform.runLater(() -> {
            janela.get().close();
            fechar.countDown();
        });
        fechar.await(10, TimeUnit.SECONDS);
        Files.deleteIfExists(new File(saida, "home/config.properties").toPath());
        return ok;
    }
}
