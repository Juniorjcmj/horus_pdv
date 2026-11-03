package br.com.quack.estoque.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lê o .xlsx de volta (zip + XML) e confere as células: números como números, textos escapados e cabeçalho em negrito. */
class XlsxWriterTest {
    private static String parte(Path xlsx, String nome) throws Exception {
        try (ZipFile z = new ZipFile(xlsx.toFile())) {
            ZipEntry e = z.getEntry(nome);
            assertNotNull(e, "parte ausente: " + nome);
            try (InputStream in = z.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    /** Todas as células da folha como referência → texto/numero (sem interpretar). */
    private static Map<String, String> celulas(String sheetXml) {
        Map<String, String> m = new HashMap<>();
        Matcher mt = Pattern.compile("<c r=\"([A-Z]+\\d+)\"([^>]*)>(.*?)</c>").matcher(sheetXml);
        while (mt.find()) {
            String corpo = mt.group(3);
            Matcher t = Pattern.compile("<t[^>]*>(.*?)</t>").matcher(corpo);
            Matcher v = Pattern.compile("<v>(.*?)</v>").matcher(corpo);
            m.put(mt.group(1), t.find() ? t.group(1) : v.find() ? v.group(1) : "");
        }
        return m;
    }

    @Test
    void gravaEleiaDeVolta(@TempDir Path pasta) throws Exception {
        Path arq = pasta.resolve("sub").resolve("reposicao.xlsx");
        XlsxWriter.save(arq, "Reposição", List.of("Código", "Produto", "Qtd. a pedir", "Custo (R$)"), List.of(
                Arrays.asList("A1", "Arroz 5kg & \"Feijão\" <tipo 1>", 12, new BigDecimal("4.50")),
                Arrays.asList("B2", "Leite", 2.5, null)));

        String sheet = parte(arq, "xl/worksheets/sheet1.xml");
        Map<String, String> c = celulas(sheet);
        assertEquals("Código", c.get("A1"));
        assertEquals("Qtd. a pedir", c.get("C1"));
        assertEquals("A1", c.get("A2"));
        assertEquals("Arroz 5kg &amp; &quot;Feijão&quot; &lt;tipo 1&gt;", c.get("B2"), "texto escapado em XML");
        assertEquals("12", c.get("C2"));
        assertEquals("4.5", c.get("D2"));
        assertEquals("2.5", c.get("C3"));
        assertNull(c.get("D3"), "valor nulo = célula vazia");
        assertTrue(sheet.contains("<c r=\"C2\"><v>12</v></c>"), "números não levam t=inlineStr");
        assertTrue(sheet.contains("t=\"inlineStr\" s=\"1\""), "cabeçalho em negrito");
        assertTrue(sheet.contains("state=\"frozen\""), "cabeçalho congelado");

        // todas as partes são XML bem formado e a aba tem o nome certo
        for (String p : List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml")) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(parte(arq, p).getBytes(StandardCharsets.UTF_8)));
            assertNotNull(doc.getDocumentElement(), p);
        }
        assertTrue(parte(arq, "xl/workbook.xml").contains("name=\"Reposição\""));
    }

    @Test
    void referenciasDeColuna() {
        assertEquals("A1", XlsxWriter.ref(0, 1));
        assertEquals("Z5", XlsxWriter.ref(25, 5));
        assertEquals("AA2", XlsxWriter.ref(26, 2));
        assertEquals("AB2", XlsxWriter.ref(27, 2));
    }

    @Test
    void nomeDaAbaValido() {
        assertEquals("Planilha", XlsxWriter.nomeAba(null));
        assertEquals("a b c", XlsxWriter.nomeAba("a:b/c"));
        assertEquals(31, XlsxWriter.nomeAba("x".repeat(60)).length());
    }

    @Test
    void controleInvalidoNaoQuebraOXml(@TempDir Path pasta) throws Exception {
        Path arq = pasta.resolve("c.xlsx");
        XlsxWriter.save(arq, "x", List.of("a"), List.of(List.of("bom\u0001ruim")));
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(parte(arq, "xl/worksheets/sheet1.xml").getBytes(StandardCharsets.UTF_8)));
        assertNotNull(doc);
        assertEquals("bomruim", celulas(parte(arq, "xl/worksheets/sheet1.xml")).get("A2"));
    }
}
