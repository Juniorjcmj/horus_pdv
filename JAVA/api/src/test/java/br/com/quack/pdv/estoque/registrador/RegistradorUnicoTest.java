package br.com.quack.pdv.estoque.registrador;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.produto.produto.domain.Produto;
import jakarta.persistence.Column;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guarda arquitetural da D2: nenhum UPDATE Produtos ... ProductQnt fora do RegistradorEstoque, e JPA updatable=false. */
class RegistradorUnicoTest {

    private static final Pattern UPDATE_SALDO_PRODUTO = Pattern.compile(
            "UPDATE\\s+(?:\\[?dbo\\]?\\.)?\\[?Produtos\\]?\\b[\\s\\S]{0,600}?\\bProductQnt\\s*=",
            Pattern.CASE_INSENSITIVE);

    @Test
    void nenhumUpdateDeSaldoDeProdutoForaDoRegistradorEstoque() throws Exception {
        Path raiz = Path.of(System.getProperty("basedir", ".")).resolve("src/main/java");
        if (!Files.isDirectory(raiz)) {
            raiz = Path.of("JAVA/api/src/main/java");
        }
        assertThat(Files.isDirectory(raiz)).isTrue();

        List<String> violacoes = new ArrayList<>();
        boolean viuRegistrador = false;
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path p : arquivos.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("RegistradorEstoque.java")) {
                    viuRegistrador = true;
                    assertThat(UPDATE_SALDO_PRODUTO.matcher(Files.readString(p, StandardCharsets.UTF_8)).find())
                            .isTrue();
                    continue;
                }
                String conteudo = Files.readString(p, StandardCharsets.UTF_8);
                if (UPDATE_SALDO_PRODUTO.matcher(conteudo).find()) {
                    violacoes.add(raiz.relativize(p).toString());
                }
            }
        }
        assertThat(viuRegistrador).isTrue();
        assertThat(violacoes)
                .as("Somente RegistradorEstoque pode alterar Produtos.ProductQnt")
                .isEmpty();
    }

    @Test
    void entidadeProdutoNaoPermiteUpdateJpaDeSaldoNemTotal() throws Exception {
        Column qnt = Produto.class.getField("productQnt").getAnnotation(Column.class);
        Column total = Produto.class.getField("totalPriceOnProduct").getAnnotation(Column.class);
        assertThat(qnt).isNotNull();
        assertThat(qnt.updatable()).isFalse();
        assertThat(total).isNotNull();
        assertThat(total.updatable()).isFalse();
    }
}
