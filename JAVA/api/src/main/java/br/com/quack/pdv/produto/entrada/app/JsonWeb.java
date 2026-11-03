package br.com.quack.pdv.produto.entrada.app;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Escritor de JSON byte a byte igual ao System.Text.Json com JsonSerializerDefaults.Web da API .NET: campos na ordem
 * recebida, nulos presentes e o escape padrao do JavaScriptEncoder.Default (tudo fora de ASCII imprimivel vira
 * \\uXXXX maiusculo, alem de " &amp; ' + &lt; &gt; ` e \). A "impressao" (fingerprint) das entradas manuais e o JSON guardado em
 * NotasEntradaArquivo.EntradaJson dependem desta forma.
 */
public final class JsonWeb {

    private JsonWeb() {}

    public static String escrever(Object valor) {
        StringBuilder sb = new StringBuilder();
        escrever(sb, valor);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void escrever(StringBuilder sb, Object valor) {
        switch (valor) {
            case null -> sb.append("null");
            case String s -> texto(sb, s);
            case Boolean b -> sb.append(b ? "true" : "false");
            case Integer i -> sb.append(i);
            case Long l -> sb.append(l);
            case BigDecimal d -> sb.append(d.toPlainString());
            case Map<?, ?> m -> {
                sb.append('{');
                boolean primeiro = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                    if (!primeiro) sb.append(',');
                    primeiro = false;
                    texto(sb, e.getKey());
                    sb.append(':');
                    escrever(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                boolean primeiro = true;
                for (Object o : l) {
                    if (!primeiro) sb.append(',');
                    primeiro = false;
                    escrever(sb, o);
                }
                sb.append(']');
            }
            default -> throw new IllegalArgumentException("Tipo nao suportado: " + valor.getClass());
        }
    }

    private static void texto(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    boolean seguro = c >= 0x20 && c <= 0x7E && "\"&'+<>`".indexOf(c) < 0;
                    if (seguro) {
                        sb.append(c);
                    } else {
                        sb.append(String.format("\\u%04X", (int) c));
                    }
                }
            }
        }
        sb.append('"');
    }
}
