package br.com.quack.pdv.plataforma.sincronizacao.protocolo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** JSON em gzip de uma {@link ParteLote} (D4). Usa um mapper proprio: o formato do fio nao depende da config da API. */
public final class SerializadorLote {

    /** Teto do JSON descomprimido (protege contra gzip bomba). */
    public static final long MAX_DESCOMPRIMIDO = 128L * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private SerializadorLote() {}

    public static byte[] paraGzip(ParteLote parte) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            JSON.writeValue(gz, parte);
            gz.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao serializar o lote.", e);
        }
    }

    public static ParteLote deGzip(byte[] gzip) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return JSON.readValue(limitar(in), ParteLote.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Corpo do lote invalido: " + e.getMessage(), e);
        }
    }

    private static InputStream limitar(InputStream in) {
        return new InputStream() {
            private long lidos;

            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b >= 0 && ++lidos > MAX_DESCOMPRIMIDO) throw new IOException("Lote descomprimido grande demais.");
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = in.read(buf, off, len);
                if (n > 0) {
                    lidos += n;
                    if (lidos > MAX_DESCOMPRIMIDO) throw new IOException("Lote descomprimido grande demais.");
                }
                return n;
            }
        };
    }
}
