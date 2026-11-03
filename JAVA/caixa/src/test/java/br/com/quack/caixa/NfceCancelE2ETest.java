package br.com.quack.caixa;

import static org.junit.jupiter.api.Assertions.*;

import br.com.quack.caixa.api.ApiException;
import br.com.quack.caixa.api.AuthApi;
import br.com.quack.caixa.api.FiscalApi;
import br.com.quack.caixa.api.PriceAuthApi;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cancelamento/devolução de NFC-e contra a API real (-Dquack.e2e.url=... -Dquack.e2e.nfce=<venda autorizada>).
 * Sem certificado digital o servidor para na SEFAZ; o teste confere busca, senha do gerente e o envio.
 */
@EnabledIfSystemProperty(named = "quack.e2e.nfce", matches = ".+")
class NfceCancelE2ETest {
    @TempDir
    static Path home;

    @Test
    void cancelarEDevolver() {
        System.setProperty("quack.home", home.toString());
        String senha = System.getProperty("quack.e2e.senha", "Teste@12345");
        AppContext ctx = new AppContext();
        ctx.api.setBaseUrl(System.getProperty("quack.e2e.url"));
        AuthApi.User user = ctx.authApi.login(System.getProperty("quack.e2e.email", "caixa@teste.com"), senha);
        ctx.session.setUser(user);

        assertNull(ctx.fiscalApi.search("999999"), "venda inexistente: não encontrado");

        FiscalApi.FiscalDoc doc = ctx.fiscalApi.search(System.getProperty("quack.e2e.nfce"));
        assertNotNull(doc);
        log("encontrada: venda #" + doc.saleNumber() + " NFC-e " + doc.numeroNf() + " série " + doc.serie()
                + " status " + FiscalApi.statusLabel(doc.status(), doc.modelo()) + " total R$ " + doc.totalAmount()
                + " autorizada em " + doc.dhAutorizacao());
        assertTrue(doc.autorizado());
        assertNotNull(doc.numeroNf());

        PriceAuthApi.Supervisor g = ctx.priceAuthApi.supervisors().get(0);
        ApiException errada = assertThrows(ApiException.class,
                () -> ctx.fiscalApi.cancelWithSupervisor(doc, g.id(), "senha-errada", "Desistência da compra pelo cliente.", ctx.config.deviceId()));
        log("cancelar com senha errada -> " + errada.status() + " " + errada.getMessage());

        try {
            FiscalApi.CancelResult r = ctx.fiscalApi.cancelWithSupervisor(doc, g.id(), senha,
                    "Desistência da compra pelo cliente.", ctx.config.deviceId());
            log("cancelar com senha certa -> OK protocolo " + r.protocoloCancelamento() + " por " + r.supervisorNome());
        } catch (ApiException e) {
            log("cancelar com senha certa -> servidor respondeu " + e.status() + ": " + e.getMessage());
        }

        String expirada = System.getProperty("quack.e2e.nfceExpirada");
        if (expirada != null) {
            FiscalApi.FiscalDoc old = ctx.fiscalApi.search(expirada);
            assertNotNull(old);
            try {
                FiscalApi.DevolucaoResult d = ctx.fiscalApi.devolver(old, g.id(), senha,
                        "Estorno de venda com prazo de NFC-e expirado.", "", "");
                log("devolução -> OK NF-e " + d.numeroNfe() + " série " + d.serieNfe() + " protocolo " + d.protocolo());
            } catch (ApiException e) {
                log("devolução -> servidor respondeu " + e.status() + ": " + e.getMessage());
            }
        }
    }

    private static void log(String m) {
        System.out.println("[NFCE-E2E] " + m);
    }
}
