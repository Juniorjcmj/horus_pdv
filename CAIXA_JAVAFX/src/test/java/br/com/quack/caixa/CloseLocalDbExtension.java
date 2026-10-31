package br.com.quack.caixa;

import br.com.quack.caixa.persistence.LocalDb;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fecha o banco local depois de cada teste: no Windows um arquivo aberto impede o JUnit de apagar a @TempDir.
 * Registrada sozinha (META-INF/services + junit-platform.properties). As stores reabrem o banco quando preciso.
 */
public class CloseLocalDbExtension implements AfterEachCallback, AfterAllCallback {
    @Override
    public void afterEach(ExtensionContext context) {
        LocalDb.closeAll();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        LocalDb.closeAll();
    }
}
