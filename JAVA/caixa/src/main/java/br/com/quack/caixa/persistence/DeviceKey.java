package br.com.quack.caixa.persistence;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;

/**
 * Chave de 256 bits que criptografa o banco local. Gerada uma vez e guardada em "chave.bin" protegida pela
 * DPAPI do Windows (CryptProtectData): só o mesmo usuário do Windows, na mesma máquina, consegue abri-la.
 * Copiar a pasta para outro computador ou outro usuário não dá acesso aos dados.
 * Fora do Windows (desenvolvimento) a chave fica no arquivo com permissão só do dono.
 */
final class DeviceKey {
    static final String FILE_NAME = "chave.bin";
    private static final byte[] DPAPI_HEADER = "QKDPAPI1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RAW_HEADER = "QKRAW001".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ENTROPY = "quack-caixa-banco-local-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    /** A chave existe mas não pôde ser aberta (outro usuário/máquina ou arquivo danificado). */
    static final class UnreadableKeyException extends Exception {
        UnreadableKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DeviceKey() {
    }

    static boolean exists(Path root) {
        return Files.exists(root.resolve(FILE_NAME));
    }

    /** Lê a chave existente. */
    static byte[] load(Path root) throws UnreadableKeyException {
        Path file = root.resolve(FILE_NAME);
        try {
            byte[] all = Files.readAllBytes(file);
            byte[] header = Arrays.copyOf(all, Math.min(all.length, 8));
            byte[] body = Arrays.copyOfRange(all, Math.min(all.length, 8), all.length);
            byte[] key;
            if (Arrays.equals(header, DPAPI_HEADER)) {
                key = Crypt32Util.cryptUnprotectData(body, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, null);
            } else if (Arrays.equals(header, RAW_HEADER)) {
                key = body;
            } else {
                throw new IOException("formato desconhecido");
            }
            if (key.length != 32) {
                throw new IOException("tamanho inválido");
            }
            return key;
        } catch (IOException | RuntimeException | Error e) {
            throw new UnreadableKeyException("Não foi possível abrir a chave do banco local (" + e.getMessage()
                    + "). Ela só abre no mesmo usuário do Windows em que foi criada.", e);
        }
    }

    /** Gera uma chave nova e grava protegida (substitui a anterior). */
    static byte[] create(Path root) {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        byte[] stored;
        if (isWindows()) {
            byte[] blob = Crypt32Util.cryptProtectData(key, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, "Quack Caixa", null);
            stored = concat(DPAPI_HEADER, blob);
        } else {
            stored = concat(RAW_HEADER, key);
        }
        Path file = root.resolve(FILE_NAME);
        Path tmp = root.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(root);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(java.nio.ByteBuffer.wrap(stored));
                ch.force(true);
            }
            if (!isWindows()) {
                try {
                    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // sistema de arquivos sem permissões POSIX
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível gravar a chave do banco local.", e);
        }
        return key;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
