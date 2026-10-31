package br.com.quack.caixa.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hash da senha para o login offline (PBKDF2-SHA256, 256 bits). Formato atual, gravado a cada login online:
 * {@code pbkdf2-sha256$<iterações>$<salt hex>$<hash hex>}, com salt aleatório de 16 bytes e 310.000 iterações.
 *
 * <p>Ainda aceita o formato antigo (só o hash em hex, salt "horus-pdv-pwd-salt:" + e-mail e 100.000 iterações, o
 * mesmo do caixa web) para quem atualizar o programa sem internet; no próximo login online ele é regravado no
 * formato novo.
 */
public final class PasswordHash {
    private static final int LEGACY_ITERATIONS = 100_000;
    static final int ITERATIONS = 310_000;
    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {
    }

    /** Formato novo, com salt aleatório. */
    public static String create(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        HexFormat hex = HexFormat.of();
        return PREFIX + "$" + ITERATIONS + "$" + hex.formatHex(salt) + "$"
                + hex.formatHex(pbkdf2(password, salt, ITERATIONS));
    }

    /** Hash antigo (igual ao computePasswordHash de cryptoHash.ts do caixa web). */
    public static String compute(String email, String password) {
        return HexFormat.of().formatHex(pbkdf2(password, legacySalt(email), LEGACY_ITERATIONS));
    }

    /** Confere a senha contra um hash gravado (formato novo ou antigo), em tempo constante. */
    public static boolean matches(String email, String password, String stored) {
        if (stored == null || password == null) {
            return false;
        }
        byte[] expected;
        byte[] candidate;
        try {
            if (stored.startsWith(PREFIX + "$")) {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) {
                    return false;
                }
                int iterations = Integer.parseInt(parts[1]);
                if (iterations < LEGACY_ITERATIONS || iterations > 10_000_000) {
                    return false;
                }
                expected = HexFormat.of().parseHex(parts[3]);
                candidate = pbkdf2(password, HexFormat.of().parseHex(parts[2]), iterations);
            } else {
                expected = HexFormat.of().parseHex(stored);
                candidate = pbkdf2(password, legacySalt(email), LEGACY_ITERATIONS);
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(candidate, expected);
    }

    /** Hash no formato antigo (salt pelo e-mail)? */
    public static boolean isLegacy(String stored) {
        return stored != null && !stored.startsWith(PREFIX + "$");
    }

    private static byte[] legacySalt(String email) {
        return ("horus-pdv-pwd-salt:" + email.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponível", e);
        } finally {
            spec.clearPassword();
        }
    }
}
