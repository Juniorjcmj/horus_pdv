package br.com.quack.estoque.domain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exportação CSV no padrão brasileiro: separador ";", valores com vírgula decimal, UTF-8 com BOM (abre no Excel). */
public final class Csv {
    private Csv() {
    }

    public static String build(List<String> cabecalho, List<List<String>> linhas) {
        StringBuilder sb = new StringBuilder();
        sb.append(linha(cabecalho));
        for (List<String> l : linhas) {
            sb.append(linha(l));
        }
        return sb.toString();
    }

    static String linha(List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(campo(cols.get(i)));
        }
        return sb.append("\r\n").toString();
    }

    static String campo(String v) {
        String s = v == null ? "" : v;
        // Neutraliza fórmulas (=, @, + e - que não sejam número) que o Excel executaria; negativos (-4,00) ficam.
        if (!s.isEmpty() && (s.charAt(0) == '=' || s.charAt(0) == '@'
                || ((s.charAt(0) == '+' || s.charAt(0) == '-') && !s.matches("[-+]?[\\d.,]+(%| ?\\p{L}*)?")))) {
            s = "'" + s;
        }
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static void save(Path file, List<String> cabecalho, List<List<String>> linhas) throws IOException {
        byte[] body = build(cabecalho, linhas).getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(file, all);
    }
}
