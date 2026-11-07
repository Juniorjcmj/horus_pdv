package br.com.quack.pdv.produto.importacao;

import br.com.quack.pdv.shared.NegocioException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Le o CSV exportado pelo sistema anterior (I1): UTF-8 com BOM ou ISO-8859-1, separador {@code ;}, aspas
 * ({@code "} e {@code ""}). As colunas sao localizadas pelo NOME do cabecalho (sem acento, sem diferenciar maiusculas,
 * espacos normalizados), em qualquer ordem. Nao conhece banco nem regra de produto.
 */
public final class LeitorCsvProdutos {

    public static final int MAX_BYTES = 15 * 1024 * 1024;
    public static final int MAX_LINHAS = 50_000;

    /** Colunas sem as quais o arquivo inteiro e recusado. */
    static final List<String> OBRIGATORIAS = List.of(
            "Código", "Descrição Completa", "Unidade Venda", "Código NCM", "Preço Venda Tab.3");

    /** Uma linha de dados: numero fisico no arquivo (cabecalho = 1) e valores por nome de coluna normalizado. */
    public record LinhaCsv(int numero, Map<String, String> valores) {
        /** Valor aparado da coluna (nome original do layout); vazio quando ausente. */
        public String valor(String coluna) {
            String v = valores.get(Textos.chaveColuna(coluna));
            return v == null ? "" : v.strip();
        }

        public boolean temColuna(String coluna) {
            return valores.containsKey(Textos.chaveColuna(coluna));
        }
    }

    public record Planilha(List<String> colunas, List<LinhaCsv> linhas) {}

    private static final byte[] BOM_UTF8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private LeitorCsvProdutos() {}

    private record Registro(int linha, List<String> celulas) {}

    public static Planilha ler(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalido("O arquivo está vazio.");
        }
        if (bytes.length > MAX_BYTES) {
            throw invalido("Arquivo maior que o permitido (15 MB).");
        }
        List<Registro> registros = registros(decodificar(bytes));
        if (registros.isEmpty()) {
            throw invalido("O arquivo está vazio.");
        }
        if (registros.size() - 1 > MAX_LINHAS) {
            throw invalido("O arquivo tem mais de " + MAX_LINHAS + " linhas de produtos.");
        }

        List<String> cabecalho = registros.getFirst().celulas();
        Map<String, Integer> indice = new LinkedHashMap<>();
        for (int i = 0; i < cabecalho.size(); i++) {
            indice.putIfAbsent(Textos.chaveColuna(cabecalho.get(i)), i);
        }
        List<String> faltando = OBRIGATORIAS.stream()
                .filter(c -> !indice.containsKey(Textos.chaveColuna(c)))
                .toList();
        if (!faltando.isEmpty()) {
            throw invalido("Colunas obrigatórias ausentes no cabeçalho: " + String.join(", ", faltando) + ".");
        }

        List<LinhaCsv> linhas = new ArrayList<>(registros.size() - 1);
        for (int r = 1; r < registros.size(); r++) {
            Registro reg = registros.get(r);
            Map<String, String> valores = new HashMap<>();
            for (Map.Entry<String, Integer> e : indice.entrySet()) {
                int pos = e.getValue();
                valores.put(e.getKey(), pos < reg.celulas().size() ? reg.celulas().get(pos) : "");
            }
            linhas.add(new LinhaCsv(reg.linha(), valores));
        }
        return new Planilha(List.copyOf(cabecalho), linhas);
    }

    /** UTF-8 com BOM; senao ISO-8859-1 (o que o sistema anterior exporta). */
    static String decodificar(byte[] bytes) {
        boolean bom = bytes.length >= 3
                && bytes[0] == BOM_UTF8[0] && bytes[1] == BOM_UTF8[1] && bytes[2] == BOM_UTF8[2];
        Charset cs = bom ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
        return new String(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0), cs);
    }

    /** Parser CSV com aspas; registros vazios (linha em branco) sao pulados, mas contam no numero da linha. */
    private static List<Registro> registros(String texto) {
        List<Registro> saida = new ArrayList<>();
        List<String> celulas = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean entreAspas = false;
        boolean registroComConteudo = false;
        int linha = 1;
        int linhaInicio = 1;
        int n = texto.length();
        for (int i = 0; i < n; i++) {
            char c = texto.charAt(i);
            if (entreAspas) {
                if (c == '"') {
                    if (i + 1 < n && texto.charAt(i + 1) == '"') {
                        atual.append('"');
                        i++;
                    } else {
                        entreAspas = false;
                    }
                } else {
                    if (c == '\n') {
                        linha++;
                    }
                    atual.append(c);
                }
                continue;
            }
            if (c == '"' && atual.length() == 0) {
                entreAspas = true;
                registroComConteudo = true;
            } else if (c == ';') {
                celulas.add(atual.toString());
                atual.setLength(0);
                registroComConteudo = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                fecharRegistro(saida, celulas, atual, registroComConteudo, linhaInicio);
                registroComConteudo = false;
                linha++;
                linhaInicio = linha;
            } else {
                atual.append(c);
                registroComConteudo = true;
            }
        }
        fecharRegistro(saida, celulas, atual, registroComConteudo, linhaInicio);
        return saida;
    }

    private static void fecharRegistro(
            List<Registro> saida, List<String> celulas, StringBuilder atual, boolean comConteudo, int linha) {
        if (comConteudo) {
            celulas.add(atual.toString());
            saida.add(new Registro(linha, new ArrayList<>(celulas)));
        }
        celulas.clear();
        atual.setLength(0);
    }

    private static NegocioException invalido(String mensagem) {
        return new NegocioException(HttpStatus.BAD_REQUEST, mensagem);
    }
}
