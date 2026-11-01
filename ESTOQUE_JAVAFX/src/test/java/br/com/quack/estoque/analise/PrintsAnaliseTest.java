package br.com.quack.estoque.analise;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import br.com.quack.estoque.ui.UiSnapshotTool;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imprime todas as telas (menu inteiro) da empresa de demonstração nos dois tamanhos de notebook, como gerente e como
 * estoquista. Só roda com -Dquack.e2e.url e -Dquack.prints=<pasta> (os PNGs ficam lá para revisão visual).
 */
@EnabledIfSystemProperty(named = "quack.prints", matches = ".+")
class PrintsAnaliseTest {
    @TempDir
    static Path tmp;

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private List<String> rodar(String sub, String email, String tamanho, String zoom, String prefixo) throws Exception {
        System.setProperty("quack.home", tmp.resolve("home-" + sub).toString());
        System.setProperty("quack.test.size", tamanho);
        if (zoom == null) {
            System.clearProperty("quack.test.zoom");
        } else {
            System.setProperty("quack.test.zoom", zoom);
        }
        return UiSnapshotTool.executar(new File(System.getProperty("quack.prints")), System.getProperty("quack.e2e.url"), email, "Demo@12345", prefixo);
    }

    @Test
    void todasAsTelasNosDoisTamanhos() throws Exception {
        List<String> g1 = rodar("g1", "gerente.demo@teste.com", "1366x690", "0.85", "gerente-1366x690-");
        assertTrue(g1.stream().filter(i -> i.startsWith("analise-")).count() == 7, "telas de análise do gerente: " + g1);
        List<String> g2 = rodar("g2", "gerente.demo@teste.com", "1024x680", null, "gerente-1024x680-");
        assertTrue(g2.containsAll(List.of("analise-dashboard", "analise-abc", "analise-reposicao", "analise-config")));
        List<String> e1 = rodar("e1", "estoquista.demo@teste.com", "1366x690", "0.85", "estoquista-1366x690-");
        List<String> e2 = rodar("e2", "estoquista.demo@teste.com", "1024x680", null, "estoquista-1024x680-");
        assertFalse(e1.contains("analise-abc"), "estoquista não vê a curva ABC (valores)");
        assertTrue(e2.contains("analise-dashboard") && e2.contains("analise-reposicao") && e2.contains("analise-config"));
        System.clearProperty("quack.test.zoom");
        System.clearProperty("quack.test.size");
    }
}
