package br.com.quack.pdv.fiscal.fila;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * Guarda arquitetural da fila fiscal minima (D6): nenhuma classe de {@code fiscal/fila} chama provedor fiscal, SEFAZ
 * ou rede. Vale ate a Fase 7 trocar a fila pelo worker completo. Confere o codigo-fonte (importacoes e tipos de
 * rede/provedor) e as dependencias reais das classes da fila (campos, construtores e metodos).
 */
class FilaSemProvedorFiscalTest {

    private static final List<Pattern> PROIBIDOS_NO_FONTE = List.of(
            Pattern.compile("import\\s+java\\.net\\."),
            Pattern.compile("import\\s+javax\\.net\\."),
            Pattern.compile("import\\s+java\\.nio\\.channels\\."),
            Pattern.compile("import\\s+org\\.springframework\\.web\\.client\\."),
            Pattern.compile("import\\s+org\\.springframework\\.web\\.reactive\\."),
            Pattern.compile("import\\s+org\\.apache\\.hc\\."),
            Pattern.compile("import\\s+okhttp3\\."),
            Pattern.compile("import\\s+br\\.com\\.quack\\.pdv\\.fiscal\\.(provedor|sefaz|nfce|transmissao)\\."),
            Pattern.compile("\\b(HttpClient|RestTemplate|RestClient|WebClient|HttpURLConnection|URLConnection|Socket)\\b"),
            Pattern.compile("\\b(IFiscalProvider|FiscalProvider|TransmitirDocumento|PrepararNfce|InutilizarAsync)\\b"));

    private static final List<String> PACOTES_PROIBIDOS = List.of("java.net", "javax.net", "java.nio.channels",
            "org.springframework.web.client", "org.springframework.web.reactive", "org.apache.hc", "okhttp3",
            "br.com.quack.pdv.fiscal.provedor", "br.com.quack.pdv.fiscal.sefaz");

    private static final List<Class<?>> CLASSES_DA_FILA = List.of(
            br.com.quack.pdv.fiscal.fila.app.FilaFiscalMinima.class,
            br.com.quack.pdv.fiscal.fila.app.PendenciaFiscal.class,
            br.com.quack.pdv.fiscal.fila.app.EmitenteConfigurado.class,
            br.com.quack.pdv.fiscal.fila.repo.FilaFiscalRepository.class);

    @Test
    void nenhumaClasseDaFilaTemCodigoDeProvedorFiscalOuRede() throws IOException {
        Path raiz = raizDaFila();

        assertThat(violacoesEm(raiz)).as("Fila fiscal minima nao pode usar provedor fiscal nem rede").isEmpty();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            assertThat(arquivos.anyMatch(p -> p.getFileName().toString().equals("FilaFiscalMinima.java"))).isTrue();
        }
    }

    @Test
    void asDependenciasDasClassesDaFilaNaoSaoDeRedeNemDeProvedor() {
        List<String> violacoes = new ArrayList<>();
        for (Class<?> c : CLASSES_DA_FILA) {
            List<Class<?>> tipos = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) tipos.add(f.getType());
            for (Constructor<?> k : c.getDeclaredConstructors()) tipos.addAll(List.of(k.getParameterTypes()));
            for (Method m : c.getDeclaredMethods()) {
                tipos.add(m.getReturnType());
                tipos.addAll(List.of(m.getParameterTypes()));
            }
            for (Class<?> t : tipos) {
                String nome = t.getName();
                if (PACOTES_PROIBIDOS.stream().anyMatch(p -> nome.startsWith(p + "."))) {
                    violacoes.add(c.getSimpleName() + " -> " + nome);
                }
            }
        }
        assertThat(violacoes).isEmpty();
    }

    @Test
    void guardaDetectaChamadaDeRedeColocadaNaFila(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Intruso.java"), "import java.net.http.HttpClient;\nclass Intruso {}\n");
        Files.writeString(tmp.resolve("OutroIntruso.java"),
                "class OutroIntruso { void f(IFiscalProvider p) { p.TransmitirDocumento(); } }\n");

        assertThat(violacoesEm(tmp)).containsExactlyInAnyOrder("Intruso.java", "OutroIntruso.java");
    }

    private static List<String> violacoesEm(Path raiz) throws IOException {
        List<String> violacoes = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path p : arquivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                String conteudo = Files.readString(p, StandardCharsets.UTF_8);
                if (PROIBIDOS_NO_FONTE.stream().anyMatch(re -> re.matcher(conteudo).find())) {
                    violacoes.add(raiz.relativize(p).toString().replace('\\', '/'));
                }
            }
        }
        return violacoes;
    }

    private static Path raizDaFila() {
        Path raiz = Path.of(System.getProperty("basedir", ".")).resolve("src/main/java/br/com/quack/pdv/fiscal/fila");
        if (!Files.isDirectory(raiz)) {
            raiz = Path.of("JAVA/api/src/main/java/br/com/quack/pdv/fiscal/fila");
        }
        assertThat(Files.isDirectory(raiz)).isTrue();
        return raiz;
    }
}
