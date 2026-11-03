package br.com.quack.pdv.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QuackPropertiesTest {

    @Test
    void padroesIguaisAoDotNet() {
        QuackProperties p = new QuackProperties();
        assertThat(p.getAuth().getSessionHours()).isEqualTo(8);
        assertThat(p.getAuth().getPasswordResetTokenMinutes()).isEqualTo(30);
        assertThat(p.getAuth().getIssuer()).isEqualTo("horus-pdv-api");
        assertThat(p.getAuth().getAudience()).isEqualTo("horus-pdv-web");
        assertThat(p.getSecurity().getMaxRequestBodyBytes()).isEqualTo(2_000_000L);
        assertThat(p.getSecurity().getRateLimit().getWindowSeconds()).isEqualTo(60);
        assertThat(p.getSecurity().getRateLimit().getMaxRequests()).isEqualTo(240);
        assertThat(p.getRecaptcha().getMinScore()).isEqualTo(0.5d);
    }

    @Test
    void limitesAjustamOsValores() {
        QuackProperties p = new QuackProperties();
        p.getAuth().setSessionHours(40);
        p.getAuth().setPasswordResetTokenMinutes(1);
        p.getSecurity().setMaxRequestBodyBytes(10);
        p.getSecurity().getRateLimit().setWindowSeconds(99999);
        p.getSecurity().getRateLimit().setMaxRequests(1);
        p.getRecaptcha().setMinScore(5);
        assertThat(p.getAuth().getSessionHours()).isEqualTo(24);
        assertThat(p.getAuth().getPasswordResetTokenMinutes()).isEqualTo(5);
        assertThat(p.getSecurity().getMaxRequestBodyBytes()).isEqualTo(32_768L);
        assertThat(p.getSecurity().getRateLimit().getWindowSeconds()).isEqualTo(3600);
        assertThat(p.getSecurity().getRateLimit().getMaxRequests()).isEqualTo(10);
        assertThat(p.getRecaptcha().getMinScore()).isEqualTo(1d);
        p.getAuth().setSessionHours(0);
        assertThat(p.getAuth().getSessionHours()).isEqualTo(1);
    }

    @Test
    void producaoRecusaSegredoCurto() {
        QuackProperties p = new QuackProperties();
        p.getAuth().setJwtSecret("curto-demais-20-chars");
        p.getSecurity().setEncryptionKey("x".repeat(40));
        assertThatThrownBy(() -> new ValidadorProducao(p).validar())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 caracteres");
    }

    @Test
    void producaoRecusaRecaptchaSemChave() {
        QuackProperties p = new QuackProperties();
        p.getAuth().setJwtSecret("j".repeat(40));
        p.getSecurity().setEncryptionKey("e".repeat(40));
        p.getRecaptcha().setEnabled(true);
        assertThatThrownBy(() -> new ValidadorProducao(p).validar())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("secret-key");
        p.getRecaptcha().setSecretKey("segredo");
        new ValidadorProducao(p).validar();
    }

    @Test
    void devNaoValida() {
        QuackProperties p = new QuackProperties();
        p.setDev(true);
        new ValidadorProducao(p).validar();
    }
}
