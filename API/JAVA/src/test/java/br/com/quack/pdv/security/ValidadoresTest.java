package br.com.quack.pdv.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidadoresTest {

    @Test
    void cnpj() {
        assertThat(CnpjValidator.valido("11.222.333/0001-81")).isTrue();
        assertThat(CnpjValidator.valido("11222333000181")).isTrue();
        assertThat(CnpjValidator.valido("11.222.333/0001-82")).isFalse();
        assertThat(CnpjValidator.valido("11111111111111")).isFalse();
        assertThat(CnpjValidator.valido("123")).isFalse();
        assertThat(CnpjValidator.valido("")).isFalse();
        assertThat(CnpjValidator.valido(null)).isFalse();
    }

    @Test
    void mascaraEmail() {
        assertThat(MascaraEmail.mascarar("joao@exemplo.com")).isEqualTo("j***o@exemplo.com");
        assertThat(MascaraEmail.mascarar("a@x.com")).isEqualTo("a@x.com");
        assertThat(MascaraEmail.mascarar("ab@x.com")).isEqualTo("a***b@x.com");
        assertThat(MascaraEmail.mascarar("semarroba")).isEqualTo("semarroba");
        assertThat(MascaraEmail.mascarar("@x.com")).isEqualTo("@x.com");
    }

    @Test
    void rotuloDeDispositivo() {
        assertThat(RotuloDispositivo.de("Mozilla/5.0 Firefox/120.0")).isEqualTo("Navegador - Firefox");
        assertThat(RotuloDispositivo.de("Mozilla/5.0 Chrome/120 Safari/537 Edg/120")).isEqualTo("Navegador - Edge");
        assertThat(RotuloDispositivo.de("Mozilla/5.0 Chrome/120 Safari/537")).isEqualTo("Navegador - Chrome");
        assertThat(RotuloDispositivo.de("Mozilla/5.0 Version/17 Safari/605")).isEqualTo("Navegador - Safari");
        assertThat(RotuloDispositivo.de("curl/8.0")).isEqualTo("Dispositivo web");
        assertThat(RotuloDispositivo.de("")).isEqualTo("Dispositivo web");
        assertThat(RotuloDispositivo.de(null)).isEqualTo("Dispositivo web");
    }
}
