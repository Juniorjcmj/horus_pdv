package br.com.quack.caixa.service;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.dto.VendaPayload;
import br.com.quack.caixa.domain.Company;
import br.com.quack.caixa.domain.SaleOutcome;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Comprovante na térmica de 80 mm: precisa caber na largura imprimível (~72 mm = 204 pt) sem cortar a direita. */
class ReceiptPrintLayoutTest {
    /** Largura imprimível típica de uma térmica 80 mm (72 mm em pontos). */
    private static final double PRINTABLE_80MM = 72 / 25.4 * 72;

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
    void escalaSoReduz() {
        assertEquals(1.0, ReceiptPrinter.fitScale(200, 204));
        assertEquals(204.0 / 288, ReceiptPrinter.fitScale(288, 204), 1e-9);
        assertEquals(1.0, ReceiptPrinter.fitScale(288, 0));
    }

    @Test
    void paginasViramNoFimDeUmaLinha() {
        List<double[]> pages = ReceiptPrinter.pageRanges(List.of(30.0, 70.0, 110.0, 150.0, 190.0), 0, 200, 100);
        assertEquals(3, pages.size());
        assertArrayEquals(new double[] {0, 70}, pages.get(0));
        assertArrayEquals(new double[] {70, 150}, pages.get(1));
        assertArrayEquals(new double[] {150, 200}, pages.get(2));
        assertEquals(1, ReceiptPrinter.pageRanges(List.of(10.0), 0, 90, 100).size());
        // Sem ponto de quebra: corta no limite da página.
        assertArrayEquals(new double[] {0, 100}, ReceiptPrinter.pageRanges(List.of(), 0, 250, 100).get(0));
    }

    @Test
    void cupomReduzidoCabeNa80mm() throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Node receipt = ReceiptPrinter.buildCupom(sale(40), company(), null);
                ReceiptPrinter.prepare(receipt);
                Bounds b = receipt.getBoundsInLocal();
                System.out.println("[CUPOM] largura " + b.getWidth() + " pt, altura " + b.getHeight() + " pt");
                // Margem padrão do JavaFX (0,75" de cada lado) deixava ~118 pt num papel de 80 mm: cortava a direita.
                double comMargemPadrao = 80 / 25.4 * 72 - 2 * 54;
                assertTrue(b.getWidth() > comMargemPadrao, "com a margem padrão o cupom não cabia");
                assertTrue(ReceiptPrinter.fitScale(b.getWidth(), comMargemPadrao) < 1);
                double scale = ReceiptPrinter.fitScale(b.getWidth(), PRINTABLE_80MM);
                double pageHeight = 297 / 25.4 * 72; // driver com papel 80 x 297 mm
                List<double[]> pages = ReceiptPrinter.pageRanges(rows(receipt), b.getMinY(), b.getMaxY(),
                        pageHeight / scale);
                assertTrue(pages.size() >= 2, "40 itens não cabem em 297 mm");
                for (double[] page : pages) {
                    ReceiptPrinter.showPage(receipt, b, scale, page);
                    Bounds onPaper = receipt.getBoundsInParent();
                    assertEquals(0, onPaper.getMinX(), 0.5);
                    assertTrue(onPaper.getMaxX() <= PRINTABLE_80MM + 0.5, "largura no papel: " + onPaper.getMaxX());
                    assertEquals(0, onPaper.getMinY(), 0.5);
                    assertTrue(onPaper.getMaxY() <= pageHeight + 0.5);
                }
                String png = System.getProperty("quack.receipt.png");
                if (png != null) {
                    ReceiptPrinter.showPage(receipt, b, scale, pages.get(0));
                    javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
                    sp.setTransform(new javafx.scene.transform.Scale(3, 3));
                    sp.setViewport(new javafx.geometry.Rectangle2D(0, 0, PRINTABLE_80MM * 3, 900));
                    var img = receipt.getParent().snapshot(sp, null);
                    var bi = new java.awt.image.BufferedImage((int) img.getWidth(), (int) img.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < bi.getHeight(); y++) {
                        for (int x = 0; x < bi.getWidth(); x++) {
                            bi.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                        }
                    }
                    javax.imageio.ImageIO.write(bi, "png", new java.io.File(png));
                }
                result.set("ok");
            } catch (Throwable t) {
                result.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (result.get() instanceof Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static List<Double> rows(Node receipt) {
        List<Double> out = new ArrayList<>();
        for (Node c : ((javafx.scene.Parent) receipt).getChildrenUnmodifiable()) {
            out.add(c.getBoundsInParent().getMaxY());
        }
        return out;
    }

    private static Company company() {
        return new Company("POP MERCADO E CONVENIENCIA", "Pop Mercado e Conveniencia LTDA", "55719388000101",
                "Estrada do Itanhangá, 1000 - Itanhangá - Rio de Janeiro/RJ", "", "Estrada do Itanhangá", "1000",
                "Itanhangá", "Rio de Janeiro", "RJ", "(21) 99999-0000", "");
    }

    private static SaleOutcome sale(int items) {
        List<VendaPayload.Item> list = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < items; i++) {
            BigDecimal v = new BigDecimal("12.90");
            list.add(new VendaPayload.Item("789601060209" + (i % 10), "MACARRAO PENNE CADORE SEMOLA 500G " + i,
                    BigDecimal.ONE, v, BigDecimal.ZERO, v, null));
            total = total.add(v);
        }
        VendaPayload p = new VendaPayload("cs1", "e1", "SALE_CREATED", "2026-10-31T13:00:00Z", "OFF-1", "h", false,
                "Consumidor", "-", "debito", total.toPlainString().replace('.', ','), "Gabriela", "op1", list,
                List.of(new VendaPayload.Payment("debito", total, null, null)));
        return new SaleOutcome(p, "5776", false, false);
    }
}
