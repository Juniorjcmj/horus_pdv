package br.com.quack.pdv.plataforma.numeracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guarda arquitetural do D4: o calculo de proximo numero (MAX sobre SaleNumber, sobre Numero de Inventarios ou
 * escrita em FiscalSequencias) so pode existir no NumeradorDocumentos.
 */
class NumeracaoUnicaTest {

    /** Padroes que identificam um calculo de numeracao de documento. */
    private static final List<Pattern> CALCULOS_DE_NUMERO = List.of(
            Pattern.compile("MAX\\s*\\([^)]*SaleNumber", Pattern.CASE_INSENSITIVE),
            Pattern.compile("MAX\\s*\\(\\s*Numero\\s*\\)[\\s\\S]{0,200}?\\bInventarios\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bUPDATE\\s+(?:\\[?dbo\\]?\\.)?\\[?FiscalSequencias\\]?\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bINSERT\\s+INTO\\s+(?:\\[?dbo\\]?\\.)?\\[?FiscalSequencias\\]?\\b", Pattern.CASE_INSENSITIVE));

    @Test
    void nenhumCalculoDeNumeroDeDocumentoForaDoNumeradorDocumentos() throws Exception {
        Path raiz = raizDosFontes();
        List<String> violacoes = violacoesEm(raiz);

        assertThat(violacoes)
                .as("Somente NumeradorDocumentos pode calcular numero de venda, fiscal ou de inventario")
                .isEmpty();
        assertThat(Files.walk(raiz).anyMatch(p -> p.getFileName().toString().equals("NumeradorDocumentos.java")))
                .isTrue();
    }

    @Test
    void guardaDetectaMaxSobreSaleNumberColocadoForaDoNumerador(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("Intruso.java"), "class Intruso {\n"
                + "    static final String SQL = \"SELECT MAX(TRY_CONVERT(INT, SaleNumber)) + 1 FROM Vendas\";\n"
                + "}\n");

        assertThat(violacoesEm(tmp)).containsExactly("Intruso.java");
    }

    private static List<String> violacoesEm(Path raiz) throws Exception {
        List<String> violacoes = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path p : arquivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("NumeradorDocumentos.java")) {
                    continue;
                }
                String conteudo = Files.readString(p, StandardCharsets.UTF_8);
                if (CALCULOS_DE_NUMERO.stream().anyMatch(re -> re.matcher(conteudo).find())) {
                    violacoes.add(raiz.relativize(p).toString().replace('\\', '/'));
                }
            }
        }
        return violacoes;
    }

    private static Path raizDosFontes() {
        Path raiz = Path.of(System.getProperty("basedir", ".")).resolve("src/main/java");
        if (!Files.isDirectory(raiz)) {
            raiz = Path.of("JAVA/api/src/main/java");
        }
        assertThat(Files.isDirectory(raiz)).isTrue();
        return raiz;
    }
}
