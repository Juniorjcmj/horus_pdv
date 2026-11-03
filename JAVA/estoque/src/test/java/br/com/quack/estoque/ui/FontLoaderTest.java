package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.scene.text.Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Inter + Manrope carregam com os nomes de família que o typography.css usa (nenhuma cai no fallback). */
class FontLoaderTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ok = new CountDownLatch(1);
        try {
            Platform.startup(ok::countDown);
        } catch (IllegalStateException jaIniciado) {
            ok.countDown();
        }
        assertTrue(ok.await(10, TimeUnit.SECONDS));
    }

    @Test
    void todasAsFontesCarregamComAsFamiliasEsperadas() {
        List<FontLoader.Carregada> r = FontLoader.carregar();
        assertTrue(FontLoader.todasCarregadas(), "alguma fonte caiu no fallback: " + r);
        Map<String, String> familia = r.stream().collect(Collectors.toMap(FontLoader.Carregada::arquivo, FontLoader.Carregada::familia));
        assertEquals("Inter", familia.get("Inter-Regular.ttf"));
        assertEquals("Inter Medium", familia.get("Inter-Medium.ttf"));
        assertEquals("Inter SemiBold", familia.get("Inter-SemiBold.ttf"));
        assertEquals("Inter", familia.get("Inter-Bold.ttf"));
        assertEquals("Manrope SemiBold", familia.get("Manrope-SemiBold.ttf"));
        assertEquals("Manrope", familia.get("Manrope-Bold.ttf"));
        // o CSS pede essas famílias: o JavaFX tem que achar a fonte certa (não a do sistema)
        assertEquals("Inter SemiBold", Font.font("Inter SemiBold", 14).getName());
        assertEquals("Manrope Bold", Font.font("Manrope", javafx.scene.text.FontWeight.BOLD, 14).getName());
        assertEquals("Inter Bold", Font.font("Inter", javafx.scene.text.FontWeight.BOLD, 14).getName());
    }

    @Test
    void cssDeTipografiaEntraAntesDoTema() {
        List<String> css = Fx.stylesheets();
        assertTrue(css.get(0).endsWith("/css/typography.css"), css.toString());
        assertTrue(css.get(1).endsWith("quack.css"), css.toString());
    }
}
