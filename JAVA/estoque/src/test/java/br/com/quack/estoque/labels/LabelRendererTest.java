package br.com.quack.estoque.labels;

import static br.com.quack.estoque.TestData.ean;
import static br.com.quack.estoque.TestData.produto;
import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.domain.Produto;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.text.Text;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LabelRendererTest {
    static final String GTIN_VALIDO = ean("789100010010");

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    static LabelData dados(String codigo, String nome, String gtin, String preco) {
        Produto p = produto("p-" + codigo, codigo, nome, gtin, preco, "UN");
        return LabelData.de(p);
    }

    /** Lê o código de barras de volta com o ZXing a partir da matriz que a etiqueta usa (com a zona de silêncio). */
    static String decodificar(LabelRenderer.Barras b) throws Exception {
        BitMatrix m = b.matriz();
        int modulo = 4;
        int q = LabelRenderer.ZONA_SILENCIO_MODULOS;
        int w = (m.getWidth() + 2 * q) * modulo;
        int h = 60;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        for (int x = 0; x < m.getWidth(); x++) {
            if (m.get(x, 0)) {
                for (int dx = 0; dx < modulo; dx++) {
                    for (int y = 0; y < h; y++) {
                        px[y * w + (q + x) * modulo + dx] = 0xFF000000;
                    }
                }
            }
        }
        BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, px)));
        return new MultiFormatReader().decode(bmp).getText();
    }

    @Test
    void gtinValidoUsaEan13() throws Exception {
        LabelRenderer.Barras b = LabelRenderer.codigoDeBarras(dados("1001", "Café", GTIN_VALIDO, "18,90"));
        assertEquals(BarcodeFormat.EAN_13, b.formato());
        assertEquals(GTIN_VALIDO, b.conteudo());
        assertNull(b.aviso());
        assertEquals(95, b.matriz().getWidth(), "EAN-13 tem 95 módulos");
        assertEquals(GTIN_VALIDO, decodificar(b), "o leitor lê o mesmo número");
    }

    @Test
    void gtinInvalidoOuAusenteUsaCode128DoCodigoInterno() throws Exception {
        String invalido = GTIN_VALIDO.substring(0, 12) + ((GTIN_VALIDO.charAt(12) - '0' + 1) % 10);
        LabelRenderer.Barras b = LabelRenderer.codigoDeBarras(dados("A-1001", "Café", invalido, "18,90"));
        assertEquals(BarcodeFormat.CODE_128, b.formato());
        assertEquals("A-1001", b.conteudo());
        assertNotNull(b.aviso());
        assertTrue(b.aviso().contains("inválido"));
        assertEquals("A-1001", decodificar(b));

        LabelRenderer.Barras sem = LabelRenderer.codigoDeBarras(dados("1001", "Café", "", "18,90"));
        assertEquals(BarcodeFormat.CODE_128, sem.formato());
        assertNull(sem.aviso(), "sem GTIN não é erro, só usa o código interno");
        assertEquals("1001", decodificar(sem));
        // GTIN de 14 dígitos ou com letras também cai no código interno
        assertEquals(BarcodeFormat.CODE_128, LabelRenderer.codigoDeBarras(dados("77", "x", "ABC", "1,00")).formato());
    }

    @Test
    void nomeLongoNaoInvadeOPrecoEUsaDuasLinhas() throws Exception {
        String nome = "Biscoito Recheado Sabor Chocolate com Morango e Cobertura Especial Pacote Família 600 gramas";
        for (LabelModel m : LabelModel.padroes()) {
            LabelRenderer.Resultado r = FxTestSupport.naFx(() -> LabelRenderer.render(m, dados("1001", nome, GTIN_VALIDO, "12,90")));
            Text tNome = (Text) r.no().getChildren().get(0);
            assertTrue(tNome.getText().split("\n").length <= 2, m.nome() + ": no máximo 2 linhas");
            if (tNome.getText().split("\n").length == 2) {
                assertTrue(tNome.getText().endsWith("…"), m.nome() + ": reticências no corte");
            }
            double fundo = FxTestSupport.naFx(() -> tNome.getBoundsInParent().getMaxY());
            assertTrue(fundo <= r.precoTop() + 0.5, m.nome() + ": o nome (" + fundo + ") não pode passar do preço (" + r.precoTop() + ")");
            double largura = FxTestSupport.naFx(() -> tNome.getBoundsInParent().getMaxX());
            assertTrue(largura <= m.larguraPt() + 0.5, m.nome() + ": o nome cabe na largura");
            assertTrue(r.precoBottom() <= r.barrasTop() + 0.5, m.nome() + ": o preço não invade as barras");
        }
    }

    @Test
    void tudoFicaDentroDaEtiquetaEOModuloEInteiroEmDots() throws Exception {
        for (LabelModel m : LabelModel.padroes()) {
            LabelRenderer.Resultado r = FxTestSupport.naFx(() -> LabelRenderer.render(m, dados("1001", "Arroz Tipo 1 5kg", GTIN_VALIDO, "27,90")));
            double w = m.larguraPt();
            double h = m.alturaPt();
            FxTestSupport.naFx(() -> {
                for (Node n : r.no().getChildren()) {
                    Bounds b = n.getBoundsInParent();
                    assertTrue(b.getMinX() >= -0.5 && b.getMaxX() <= w + 0.5, m.nome() + ": " + n + " sai pela lateral " + b);
                    assertTrue(b.getMinY() >= -0.5 && b.getMaxY() <= h + 0.5, m.nome() + ": " + n + " sai por cima/baixo " + b);
                }
                return null;
            });
            double dots = r.moduloPt() * LabelRenderer.DPI_TERMICA / 72.0;
            assertEquals(Math.rint(dots), dots, 1e-9, m.nome() + ": módulo com número inteiro de dots");
            assertTrue(dots >= 2 - 1e-9, m.nome() + ": módulo de pelo menos 2 dots a 203 dpi");
            assertTrue(r.aviso().isEmpty(), m.nome() + ": " + r.aviso());
        }
    }

    @Test
    void promocaoMostraPrecoPromocionalEPeriodo() throws Exception {
        Produto p = produto("p1", "1001", "Café Pilão 500g", GTIN_VALIDO, "18,90", "UN");
        LabelData promo = LabelData.promocao(p, new BigDecimal("15.90"), LabelRenderer.periodo("2026-10-01", "2026-11-30T23:59:00"));
        assertTrue(promo.emPromocao());
        assertEquals(0, new BigDecimal("15.90").compareTo(promo.precoFinal()));
        assertEquals("até 30/11", promo.periodoPromocao());
        LabelRenderer.Resultado r = FxTestSupport.naFx(() -> LabelRenderer.render(LabelModel.R60X40, promo));
        boolean temPrecoPromo = r.no().getChildren().stream().anyMatch(n -> n instanceof Text t && t.getText().equals("15,90"));
        boolean temDe = r.no().getChildren().stream().anyMatch(n -> n instanceof Text t && t.getText().startsWith("de R$ 18,90"));
        assertTrue(temPrecoPromo, "preço promocional em destaque");
        assertTrue(temDe, "preço normal (riscado) com o período");
        assertFalse(LabelData.promocao(p, new BigDecimal("20.00"), "").emPromocao(), "promoção acima do normal é ignorada");
    }

    @Test
    void snapshotOpcionalEmPng() throws Exception {
        for (LabelModel m : new LabelModel[] {LabelModel.R60X40, LabelModel.R40X25}) {
            LabelRenderer.Resultado r = FxTestSupport.naFx(() -> LabelRenderer.render(m, dados("1001", "Café Pilão Tradicional 500g", GTIN_VALIDO, "18,90")));
            BufferedImage img = FxTestSupport.imagem(r.no(), 3);
            assertTrue(img.getWidth() > 100);
            FxTestSupport.salvarSePedido(img, "etiqueta-" + m.id());
            // há pixels pretos (texto e barras)
            boolean preto = false;
            for (int x = 0; x < img.getWidth() && !preto; x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    if ((img.getRGB(x, y) & 0xFFFFFF) == 0) {
                        preto = true;
                        break;
                    }
                }
            }
            assertTrue(preto);
        }
    }
}
