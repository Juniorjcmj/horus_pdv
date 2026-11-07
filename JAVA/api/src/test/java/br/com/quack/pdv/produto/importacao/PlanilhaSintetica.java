package br.com.quack.pdv.produto.importacao;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Apoio dos testes: le a amostra sintetica e gera planilhas grandes com o mesmo cabecalho de 114 colunas. */
public final class PlanilhaSintetica {

    public static final String RECURSO = "/importacao/amostra-legado.csv";
    private static final String CRLF = "\r\n";

    private PlanilhaSintetica() {}

    public static byte[] amostra() {
        try (InputStream in = PlanilhaSintetica.class.getResourceAsStream(RECURSO)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String amostraTexto() {
        return new String(amostra(), StandardCharsets.ISO_8859_1);
    }

    public static List<String> cabecalho() {
        return Arrays.asList(amostraTexto().split(CRLF)[0].split(";", -1));
    }

    /** Planilha de {@code n} produtos sinteticos (codigos 100000+, sem GTIN, categorias repetidas, saldo positivo). */
    public static byte[] gerar(int n) {
        List<String> cab = cabecalho();
        StringBuilder sb = new StringBuilder(String.join(";", cab)).append(CRLF);
        for (int i = 0; i < n; i++) {
            String[] cel = new String[cab.size()];
            Arrays.fill(cel, "");
            put(cab, cel, "Código", String.valueOf(100000 + i));
            put(cab, cel, "Descrição Completa", "PRODUTO VOLUME " + i);
            put(cab, cel, "Unidade Venda", "UN");
            put(cab, cel, "Código NCM", "25232910");
            put(cab, cel, "CST", "060");
            put(cab, cel, "Cód. CSOSN", "500");
            put(cab, cel, "CFOP", "5405");
            put(cab, cel, "Classe", "CLASSE " + (i % 18));
            put(cab, cel, "Grupo", "GRUPO " + (i % 116));
            put(cab, cel, "Sub-Grupo", "SUB " + (i % 501));
            put(cab, cel, "Qtd.Estoque (Física)", (i % 7) + ",5");
            put(cab, cel, "Vl.Custo Compra (C/Impostos)", "R$ " + (1 + i % 90) + ",33");
            put(cab, cel, "Preço Venda Tab.3", "R$ " + (2 + i % 90) + ",90");
            sb.append(String.join(";", new ArrayList<>(Arrays.asList(cel)))).append(CRLF);
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void put(List<String> cab, String[] cel, String coluna, String valor) {
        cel[cab.indexOf(coluna)] = valor;
    }
}
