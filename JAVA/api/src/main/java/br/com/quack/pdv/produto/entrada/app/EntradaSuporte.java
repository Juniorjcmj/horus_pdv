package br.com.quack.pdv.produto.entrada.app;

import br.com.quack.pdv.shared.NegocioException;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

final class EntradaSuporte {
    private EntradaSuporte() {}

    static NegocioException erro(String mensagem) {
        return new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }

    static String hash(String texto) {
        try {
            return HexFormat.of()
                    .withUpperCase()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static boolean vazio(String s) {
        return s == null || s.isBlank();
    }

    static String digitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    static String numero(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    static LocalDate data(String valor, String mensagem) {
        try {
            if (valor == null || !valor.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IllegalArgumentException();
            }
            return LocalDate.parse(valor);
        } catch (Exception ex) {
            throw erro(mensagem);
        }
    }
}
