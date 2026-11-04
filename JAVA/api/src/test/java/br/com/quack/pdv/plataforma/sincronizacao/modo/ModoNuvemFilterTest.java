package br.com.quack.pdv.plataforma.sincronizacao.modo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModoNuvemFilterTest {

    @Test
    void permiteSoLojaSyncEHealth() {
        assertThat(ModoNuvemFilter.permitida("/api/loja-sync/lotes")).isTrue();
        assertThat(ModoNuvemFilter.permitida("/api/loja-sync")).isTrue();
        assertThat(ModoNuvemFilter.permitida("/actuator/health")).isTrue();
        assertThat(ModoNuvemFilter.permitida("/actuator/health/liveness")).isTrue();
    }

    @Test
    void recusaOResto() {
        assertThat(ModoNuvemFilter.permitida("/api/historicovendas")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/api/auth/login")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/actuator/info")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/api/loja-syncx")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/")).isFalse();
    }

    @Test
    void recusaTentativasDeContornarOCaminho() {
        assertThat(ModoNuvemFilter.permitida("/api/loja-sync/../auth/login")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/api//loja-sync/x")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/api/loja-sync;x/../historicovendas")).isFalse();
        assertThat(ModoNuvemFilter.permitida("/api/%2e%2e/historicovendas")).isFalse();
    }
}
