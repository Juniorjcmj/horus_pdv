package br.com.quack.estoque.ui;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.estoque.FxTestSupport;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Abre o aplicativo de verdade (fora da área visível) contra a API de teste e renderiza todas as telas como administrador e
 * como estoquista: nenhuma pode lançar exceção ao montar ou carregar dados. Defina -Dquack.test.png=pasta para guardar os PNGs.
 */
@EnabledIfSystemProperty(named = "quack.e2e.url", matches = ".+")
class TelasE2ETest {
    @TempDir
    static Path tmp;

    @BeforeAll
    static void fx() throws Exception {
        FxTestSupport.iniciar();
    }

    private File pasta(String sub) {
        String dir = System.getProperty("quack.test.png");
        return dir == null || dir.isBlank() ? tmp.resolve(sub).toFile() : new File(dir, sub);
    }

    @Test
    void todasAsTelasDoAdministrador() throws Exception {
        System.setProperty("quack.home", tmp.resolve("home-admin").toString());
        List<String> ok = UiSnapshotTool.executar(pasta("admin"), System.getProperty("quack.e2e.url"), "caixa@teste.com", "Teste@12345", "");
        assertEquals(9, ok.size(), "telas renderizadas: " + ok);
    }

    @Test
    void telasDoEstoquistaSemCadastros() throws Exception {
        System.setProperty("quack.home", tmp.resolve("home-estoquista").toString());
        // o usuário estoquista é criado pelos outros testes E2E (E2eSupport.comoEstoquista); cria aqui também se faltar
        br.com.quack.estoque.E2eSupportAcesso.garantirEstoquista(tmp.resolve("home-prep"));
        List<String> ok = UiSnapshotTool.executar(pasta("estoquista"), System.getProperty("quack.e2e.url"), "estoquista@teste.com", "Estoq@12345", "");
        assertTrue(ok.size() >= 8, "telas renderizadas: " + ok);
        assertFalse(ok.contains("cadastros"), "estoquista não tem Cadastros");
    }
}
