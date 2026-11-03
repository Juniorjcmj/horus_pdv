package br.com.quack.pdv.security;

import br.com.quack.pdv.config.QuackProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** AES-GCM no formato enc:v1:base64(nonce).base64(tag).base64(cifra), igual ao HorusSecretProtector. */
@Component
public class SecretProtector {

    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String encryptionKey;
    private final String jwtSecret;

    @Autowired
    public SecretProtector(QuackProperties props) {
        this(props.getSecurity().getEncryptionKey(), props.getAuth().getJwtSecret());
    }

    public SecretProtector(String encryptionKey, String jwtSecret) {
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey;
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
    }

    public String protect(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.startsWith(PREFIX)) return value;
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(deriveKey(), "AES"), new GCMParameterSpec(128, nonce));
            byte[] out = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            // O Java devolve cifra||tag; o formato do .NET separa nonce, tag e cifra.
            byte[] cipherText = Arrays.copyOfRange(out, 0, out.length - 16);
            byte[] tag = Arrays.copyOfRange(out, out.length - 16, out.length);
            Base64.Encoder b64 = Base64.getEncoder();
            return PREFIX + b64.encodeToString(nonce) + "." + b64.encodeToString(tag) + "." + b64.encodeToString(cipherText);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao proteger segredo.", e);
        }
    }

    public String unprotect(String value) {
        if (value == null || value.isBlank()) return "";
        if (!value.startsWith(PREFIX)) return value;
        String[] parts = value.substring(PREFIX.length()).split("\\.", 3);
        if (parts.length != 3) throw new IllegalStateException("Segredo protegido invalido.");
        try {
            Base64.Decoder b64 = Base64.getDecoder();
            byte[] nonce = b64.decode(parts[0]);
            byte[] tag = b64.decode(parts[1]);
            byte[] cipherText = b64.decode(parts[2]);
            byte[] in = new byte[cipherText.length + tag.length];
            System.arraycopy(cipherText, 0, in, 0, cipherText.length);
            System.arraycopy(tag, 0, in, cipherText.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(deriveKey(), "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(in), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Segredo protegido invalido.", e);
        }
    }

    private byte[] deriveKey() throws GeneralSecurityException {
        String source = encryptionKey.isBlank() ? jwtSecret : encryptionKey;
        return MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
    }
}
