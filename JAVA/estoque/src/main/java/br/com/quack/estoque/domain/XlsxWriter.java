package br.com.quack.estoque.domain;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gravador mínimo de planilha .xlsx (uma aba) sem dependências: um zip com {@code [Content_Types].xml}, o workbook, os estilos
 * e a folha. Células de texto são gravadas como {@code inlineStr}; {@link Number} vira célula numérica (o Excel mostra no
 * formato do Windows do usuário, com vírgula decimal). A linha de cabeçalho sai em negrito e fica congelada.
 */
public final class XlsxWriter {
    private XlsxWriter() {
    }

    /** Uma linha por lista; valores {@link Number} viram números, o resto vira texto ({@code null} = célula vazia). */
    public static void save(Path arquivo, String aba, List<String> cabecalho, List<List<Object>> linhas) throws IOException {
        if (arquivo.getParent() != null) {
            Files.createDirectories(arquivo.getParent());
        }
        try (OutputStream os = Files.newOutputStream(arquivo); ZipOutputStream zip = new ZipOutputStream(os, StandardCharsets.UTF_8)) {
            entrada(zip, "[Content_Types].xml", CONTENT_TYPES);
            entrada(zip, "_rels/.rels", RELS);
            entrada(zip, "xl/workbook.xml", workbook(aba));
            entrada(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            entrada(zip, "xl/styles.xml", STYLES);
            entrada(zip, "xl/worksheets/sheet1.xml", folha(cabecalho, linhas));
        }
    }

    private static void entrada(ZipOutputStream zip, String nome, String conteudo) throws IOException {
        zip.putNextEntry(new ZipEntry(nome));
        zip.write(conteudo.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private static final String CONTENT_TYPES = XML
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String RELS = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK_RELS = XML
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = XML
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
            + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
            + "</styleSheet>";

    private static String workbook(String aba) {
        return XML + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"" + esc(nomeAba(aba)) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
    }

    /** Nome de aba válido no Excel: até 31 caracteres, sem : \ / ? * [ ]. */
    static String nomeAba(String aba) {
        String s = (aba == null || aba.isBlank() ? "Planilha" : aba).replaceAll("[:\\\\/?*\\[\\]]", " ").trim();
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    private static String folha(List<String> cabecalho, List<List<Object>> linhas) {
        StringBuilder sb = new StringBuilder(XML);
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        sb.append("<cols>");
        for (int i = 0; i < cabecalho.size(); i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                    .append(Math.max(10, Math.min(40, cabecalho.get(i).length() + 4))).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        sb.append("<row r=\"1\">");
        for (int c = 0; c < cabecalho.size(); c++) {
            texto(sb, ref(c, 1), cabecalho.get(c), 1);
        }
        sb.append("</row>");
        int r = 2;
        for (List<Object> linha : linhas) {
            sb.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < linha.size(); c++) {
                Object v = linha.get(c);
                if (v == null) {
                    continue;
                }
                if (v instanceof Number n) {
                    sb.append("<c r=\"").append(ref(c, r)).append("\"><v>").append(numero(n)).append("</v></c>");
                } else {
                    texto(sb, ref(c, r), String.valueOf(v), 0);
                }
            }
            sb.append("</row>");
            r++;
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    private static void texto(StringBuilder sb, String ref, String valor, int estilo) {
        sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(estilo == 0 ? "" : " s=\"" + estilo + "\"")
                .append("><is><t xml:space=\"preserve\">").append(esc(valor)).append("</t></is></c>");
    }

    private static String numero(Number n) {
        if (n instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString();
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "0";
        }
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : BigDecimal.valueOf(d).toPlainString();
    }

    /** Referência de célula: coluna 0 = A, 26 = AA. */
    static String ref(int coluna, int linha) {
        StringBuilder col = new StringBuilder();
        int c = coluna;
        do {
            col.insert(0, (char) ('A' + c % 26));
            c = c / 26 - 1;
        } while (c >= 0);
        return col.append(linha).toString();
    }

    /** Escapa o XML e descarta caracteres de controle que tornariam o arquivo inválido. */
    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
