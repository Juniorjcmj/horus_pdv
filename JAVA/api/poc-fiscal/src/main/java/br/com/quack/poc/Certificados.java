package br.com.quack.poc;

import br.com.swconsultoria.certificado.Certificado;
import br.com.swconsultoria.certificado.CertificadoService;
import br.com.swconsultoria.certificado.exception.CertificadoException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Certificado A1 sempre em memoria: o PFX entra como bytes (do banco, na API real) e nunca e gravado em disco. */
public final class Certificados {

    private Certificados() {}

    /** Carrega o A1 a partir de bytes e senha, sem arquivo. */
    public static Certificado doPfx(byte[] pfx, String senha) throws CertificadoException {
        return CertificadoService.certificadoPfxBytes(pfx, senha);
    }

    /**
     * Gera um PFX autoassinado so para o teste offline (assinatura + XSD). O keytool do JDK escreve um arquivo
     * temporario que e lido para a memoria e apagado na hora; o certificado de verdade nunca passa por disco.
     */
    public static byte[] pfxAutoassinado(String senha, String cnpj) throws Exception {
        Path pasta = Files.createTempDirectory("poc-cert");
        Path arquivo = pasta.resolve("autoassinado.p12");
        try {
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            Process p = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", "poc", "-keyalg", "RSA", "-keysize", "2048",
                    "-sigalg", "SHA256withRSA", "-validity", "30", "-storetype", "PKCS12", "-keystore", arquivo.toString(),
                    "-storepass", senha, "-keypass", senha,
                    "-dname", "CN=QUACK PDV COMERCIO LTDA:" + cnpj + ", OU=Teste, O=ICP-Brasil, C=BR",
                    // A biblioteca confere o CNPJ do certificado com o do emitente: ICP-Brasil guarda no SAN otherName 2.16.76.1.3.3
                    "-ext", "2.5.29.17=" + sanCnpj(cnpj)))
                    .redirectErrorStream(true).start();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("keytool falhou: " + new String(p.getInputStream().readAllBytes()));
            }
            return Files.readAllBytes(arquivo);
        } finally {
            Files.deleteIfExists(arquivo);
            Files.deleteIfExists(pasta);
        }
    }

/** GeneralNames { otherName { OID 2.16.76.1.3.3, [0] UTF8String(cnpj) } } em hexadecimal (DER). */
static String sanCnpj(String cnpj) {
    StringBuilder dado = new StringBuilder();
    for (byte b : cnpj.getBytes(java.nio.charset.StandardCharsets.US_ASCII)) dado.append(String.format("%02X", b));
    int n = cnpj.length();
    String utf8 = "0C" + String.format("%02X", n) + dado;                 // UTF8String
    String explicito = "A0" + String.format("%02X", utf8.length() / 2) + utf8;      // [0] EXPLICIT
    String oid = "0605604C010303";                                                    // 2.16.76.1.3.3
    String outro = "A0" + String.format("%02X", (oid.length() + explicito.length()) / 2) + oid + explicito; // [0] otherName
    return "30" + String.format("%02X", outro.length() / 2) + outro;
}

public static byte[] doBase64(String base64) {
        return Base64.getDecoder().decode(base64.trim());
    }
}
