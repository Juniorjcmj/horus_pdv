package br.com.quack.pdv.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Geracao e hash do token de redefinicao de senha (igual ao HorusSecurityStore). */
public final class TokenRedefinicao {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenRedefinicao() {}

    /** 32 bytes aleatorios em Base64URL sem padding. */
    public static String gerar() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex minusculo de "token:jwtSecret". */
    public static String hash(String token, String jwtSecret) {
        try {
            byte[] payload = ((token == null ? "" : token.trim()) + ":" + jwtSecret).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
