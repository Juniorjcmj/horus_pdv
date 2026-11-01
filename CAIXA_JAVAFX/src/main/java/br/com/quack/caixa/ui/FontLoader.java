package br.com.quack.caixa.ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.text.Font;

/**
 * Carrega as fontes da interface (Inter e Manrope, estáticas: o JavaFX não lê fontes variáveis) antes de criar qualquer
 * Scene. Fonte que não carrega não derruba o app: o texto cai na fonte do sistema ("System").
 *
 * <p>Nomes de família que o JavaFX registra (Windows, conferidos pelo log com -Dquack.debug.fontes=true):
 * "Inter" (Regular e Bold), "Inter Medium", "Inter SemiBold", "Manrope SemiBold" e "Manrope" (Bold). Os pesos
 * intermediários são escolhidos pelo nome da família no CSS, porque -fx-font-weight só distingue normal/bold.
 */
public final class FontLoader {
    /** Arquivos em /fonts (classpath), na ordem de carregamento. */
    public static final List<String> ARQUIVOS = List.of("Inter-Regular.ttf", "Inter-Medium.ttf", "Inter-SemiBold.ttf",
            "Inter-Bold.ttf", "Manrope-SemiBold.ttf", "Manrope-Bold.ttf");

    /** Resultado de uma fonte: arquivo, família e nome registrados (null quando falhou). */
    public record Carregada(String arquivo, String familia, String nome) {
        public boolean ok() {
            return familia != null;
        }
    }

    private static List<Carregada> carregadas = List.of();

    private FontLoader() {
    }

    /** Carrega todas as fontes uma vez (chamadas seguintes devolvem o mesmo resultado). */
    public static synchronized List<Carregada> carregar() {
        if (!carregadas.isEmpty()) {
            return carregadas;
        }
        boolean debug = Boolean.getBoolean("quack.debug.fontes") || Boolean.getBoolean("quack.dev");
        List<Carregada> r = new ArrayList<>();
        for (String arquivo : ARQUIVOS) {
            Font f = null;
            try (InputStream in = FontLoader.class.getResourceAsStream("/fonts/" + arquivo)) {
                if (in != null) {
                    f = Font.loadFont(in, 14);
                }
            } catch (Exception e) {
                f = null;
            }
            if (f == null) {
                System.err.println("[fontes] não carregou " + arquivo + ": o texto usa a fonte do sistema (System).");
                r.add(new Carregada(arquivo, null, null));
            } else {
                r.add(new Carregada(arquivo, f.getFamily(), f.getName()));
                if (debug) {
                    System.out.println("[fontes] " + arquivo + " -> família \"" + f.getFamily() + "\", nome \"" + f.getName() + "\"");
                }
            }
        }
        carregadas = List.copyOf(r);
        return carregadas;
    }

    /** true se todas as fontes da interface carregaram (nenhuma caiu no fallback). */
    public static boolean todasCarregadas() {
        return carregar().stream().allMatch(Carregada::ok);
    }
}
