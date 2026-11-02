package br.com.quack.pdv.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-SHA256, formato iteracoes.sal.chave (igual ao PasswordHasher da API .NET). */
public final class PasswordHasher {

    private static final int ITERATIONS = 100_000;
    private static final int SALT_SIZE = 16;
    private static final int KEY_SIZE = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_SIZE];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS, KEY_SIZE);
        return ITERATIONS + "." + Base64.getEncoder().encodeToString(salt) + "." + Base64.getEncoder().encodeToString(key);
    }

    public static boolean verify(String password, String hash) {
        if (password == null || hash == null) return false;
        String[] parts = hash.split("\\.", -1);
        if (parts.length != 3) return false;
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            if (iterations <= 0 || expected.length == 0) return false;
            byte[] actual = derive(password, salt, iterations, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations, int length) {
        // PBEKeySpec recebe char[]; o SunJCE codifica em UTF-8, como Rfc2898DeriveBytes.Pbkdf2(string).
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, length * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponivel", e);
        } finally {
            spec.clearPassword();
        }
    }
}
