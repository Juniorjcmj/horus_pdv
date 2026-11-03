package br.com.quack.pdv.empresa.app;

import br.com.quack.pdv.shared.NegocioException;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LeitorCertificado {
    public record Dados(String thumbprint, OffsetDateTime validoAte) {}

    public Dados ler(String base64, String senha) {
        try {
            String normalizado = base64.replaceAll("[\\t\\n\\r ]", "");
            if (normalizado.length() % 4 != 0) throw new IllegalArgumentException("Base64 invalido.");
            KeyStore armazenamento = KeyStore.getInstance("PKCS12");
            armazenamento.load(new ByteArrayInputStream(Base64.getDecoder().decode(normalizado)), senha.toCharArray());
            var entradas = armazenamento.aliases();
            while (entradas.hasMoreElements()) {
                String entrada = entradas.nextElement();
                if (!armazenamento.isKeyEntry(entrada)) continue;
                armazenamento.getKey(entrada, senha.toCharArray());
                X509Certificate certificado = (X509Certificate) armazenamento.getCertificate(entrada);
                return new Dados(HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(certificado.getEncoded())),
                        certificado.getNotAfter().toInstant().atOffset(ZoneOffset.UTC));
            }
            throw new IllegalArgumentException("Certificado sem chave privada.");
        } catch (Exception e) {
            throw new NegocioException(HttpStatus.BAD_REQUEST, "Certificado digital ou senha inválidos.");
        }
    }
}
