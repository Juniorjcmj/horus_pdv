package br.com.quack.pdv.venda.registro;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Porta byte a byte do {@code HorusPayloadHash} da API .NET (D3): SHA-256 do JSON que o System.Text.Json produz
 * para o objeto canonico. O hash decide a idempotencia dos eventos offline, entao precisa ser identico ao do .NET
 * (e o que o .NET gravou em ProcessedEvents precisa ser reconhecido pela Java, e vice-versa).
 *
 * <p>Regras reproduzidas:
 * <ul>
 *   <li>ordem das propriedades e ordenacao estavel de itens/pagamentos por OrdinalIgnoreCase;
 *   <li>{@code string.Trim()} do .NET (espacos Unicode) e {@code ToLowerInvariant};
 *   <li>decimal preserva a escala ("2.50"); {@code Math.Round(d, n)} usa ToEven e so reduz a escala;
 *   <li>escape padrao do System.Text.Json: tudo fora de ASCII e as aspas, e comercial, apostrofo, mais, menor, maior,
 *       crase e barra invertida viram barra-u e 4 hexa MAIUSCULOS; controles usam as formas curtas (b, t, n, f, r) ou barra-u 00XX.
 * </ul>
 * O {@code PayloadHash} do Caixa JavaFX nao escapa nao-ASCII; o hash do .NET e a referencia.
 */
public final class HashPayload {

    private static final Pattern NUMERO_INVARIANTE =
            Pattern.compile("^(?:[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)|(?:\\d+(?:\\.\\d*)?|\\.\\d+)[+-])$");
    private static final int MAX_DIGITOS_DECIMAL = 29;

    private HashPayload() {}

    // ---------------------------------------------------------------- entradas (espelham os requests do .NET)

    public record Item(String productCode, BigDecimal quantity, BigDecimal unitPrice, BigDecimal desconto,
                       String promocaoId) {}

    public record Pagamento(String paymentType, BigDecimal amount, BigDecimal cashGiven, BigDecimal changeAmount) {}

    public record Venda(String customerName, String customerCpf, String paymentType, String totalAmount,
                        String operatorName, List<Item> items, List<Pagamento> payments,
                        String xmlNfceContingencia) {}

    // ---------------------------------------------------------------- overloads

    /** {@code ComputeHash(VendaRequest)}. */
    public static String calcular(Venda r) {
        StringBuilder json = new StringBuilder("{");
        propString(json, "customerName", trim(r.customerName())).append(',');
        propString(json, "customerCpf", trim(r.customerCpf())).append(',');
        propString(json, "paymentType", trim(r.paymentType())).append(',');
        propNumero(json, "totalAmount", parseDecimal(r.totalAmount())).append(',');
        propString(json, "operatorName", trim(r.operatorName())).append(',');
        json.append("\"items\":[");
        List<Item> itens = ordenar(r.items(), Item::productCode);
        for (int i = 0; i < itens.size(); i++) {
            Item it = itens.get(i);
            if (i > 0) json.append(',');
            json.append('{');
            propString(json, "productCode", trim(it.productCode())).append(',');
            propNumero(json, "quantity", arredondar(it.quantity(), 4)).append(',');
            propNumero(json, "unitPrice", arredondar(it.unitPrice(), 2)).append(',');
            propNumero(json, "desconto", arredondar(it.desconto(), 2)).append(',');
            propString(json, "promocaoId", it.promocaoId() == null ? "" : it.promocaoId());
            json.append('}');
        }
        json.append("],\"payments\":[");
        List<Pagamento> pagamentos = ordenar(r.payments(), Pagamento::paymentType);
        for (int i = 0; i < pagamentos.size(); i++) {
            Pagamento p = pagamentos.get(i);
            if (i > 0) json.append(',');
            json.append('{');
            propString(json, "paymentType", trim(p.paymentType())).append(',');
            propNumero(json, "amount", arredondar(p.amount(), 2)).append(',');
            propNumero(json, "cashGiven", arredondar(p.cashGiven() == null ? BigDecimal.ZERO : p.cashGiven(), 2))
                    .append(',');
            propNumero(json, "changeAmount",
                    arredondar(p.changeAmount() == null ? BigDecimal.ZERO : p.changeAmount(), 2));
            json.append('}');
        }
        json.append(']');
        if (r.xmlNfceContingencia() != null && !r.xmlNfceContingencia().isBlank()) {
            // Mantem o hash legado intacto; amarra o XML fiscal original quando ele vem.
            json.append(",\"xmlNfceContingenciaSha256\":\"")
                    .append(sha256Hex(r.xmlNfceContingencia())).append('"');
        }
        json.append('}');
        return sha256Hex(json.toString());
    }

    /** {@code ComputeHash(AbrirCaixaRequest)}. */
    public static String calcularAbertura(String openingAmount) {
        StringBuilder json = new StringBuilder("{");
        propNumero(json, "openingAmount", parseDecimal(openingAmount)).append('}');
        return sha256Hex(json.toString());
    }

    /** {@code ComputeHash(FecharCaixaRequest)}. */
    public static String calcularFechamento(String closingAmount, String note, String differenceReason) {
        StringBuilder json = new StringBuilder("{");
        propNumero(json, "closingAmount", parseDecimal(closingAmount)).append(',');
        propString(json, "note", trim(note)).append(',');
        propString(json, "differenceReason", trim(differenceReason)).append('}');
        return sha256Hex(json.toString());
    }

    /** {@code ComputeHash(RegistrarMovimentoCaixaRequest)}. */
    public static String calcularMovimento(String tipo, String valor, String motivo) {
        StringBuilder json = new StringBuilder("{");
        propString(json, "tipo", trim(tipo).toLowerCase(Locale.ROOT)).append(',');
        propNumero(json, "valor", parseDecimal(valor)).append(',');
        propString(json, "motivo", trim(motivo)).append('}');
        return sha256Hex(json.toString());
    }

    /**
     * Hash que o {@code CaixaAB} calcula quando o cliente nao envia {@code payloadHash}: objeto anonimo com o valor ja
     * formatado em pt-BR (texto, nao numero): {@code { openingAmount = Format(openingAmount) }}.
     */
    public static String calcularAberturaSemHashDoCliente(String openingAmountFormatado) {
        StringBuilder json = new StringBuilder("{");
        propString(json, "openingAmount", openingAmountFormatado).append('}');
        return sha256Hex(json.toString());
    }

    /**
     * Hash do fechamento sem {@code payloadHash} do cliente: {@code { closingAmount = Format(...), note, differenceReason }}
     * com a justificativa nula serializada como {@code null}.
     */
    public static String calcularFechamentoSemHashDoCliente(String closingAmountFormatado, String note,
                                                            String differenceReason) {
        StringBuilder json = new StringBuilder("{");
        propString(json, "closingAmount", closingAmountFormatado).append(',');
        propString(json, "note", note == null ? "" : note).append(',');
        json.append("\"differenceReason\":").append(differenceReason == null ? "null" : string(differenceReason))
                .append('}');
        return sha256Hex(json.toString());
    }

    // ---------------------------------------------------------------- equivalentes do .NET

    /** {@code HorusMoneyFormat.ParseDecimal(valor)}: pt-BR ou invariante, escala preservada, 0 quando invalido. */
    public static BigDecimal parseDecimal(String valor) {
        if (valor == null || isBlank(valor)) return BigDecimal.ZERO;
        String normalizado = trim(valor).replaceAll("(?i)R\\$", "").replace(" ", "");
        if (normalizado.isEmpty() || normalizado.equals("-")) return BigDecimal.ZERO;
        if (normalizado.indexOf(',') >= 0) {
            normalizado = normalizado.replace(".", "").replace(",", ".");
        }
        if (!NUMERO_INVARIANTE.matcher(normalizado).matches()) return BigDecimal.ZERO;
        boolean negativo = normalizado.startsWith("-") || normalizado.endsWith("-");
        String digitos = normalizado.replace("+", "").replace("-", "");
        if (digitos.startsWith(".")) digitos = "0" + digitos;
        if (digitos.endsWith(".")) digitos = digitos.substring(0, digitos.length() - 1);
        BigDecimal numero = new BigDecimal(digitos);
        if (numero.precision() > MAX_DIGITOS_DECIMAL - 1) return BigDecimal.ZERO; // overflow do decimal do .NET
        return negativo ? numero.negate() : numero;
    }

    /** {@code Math.Round(d, casas)}: ToEven; so reduz a escala (nunca acrescenta zeros). */
    static BigDecimal arredondar(BigDecimal valor, int casas) {
        BigDecimal v = valor == null ? BigDecimal.ZERO : valor;
        return v.scale() > casas ? v.setScale(casas, RoundingMode.HALF_EVEN) : v;
    }

    /** {@code string.Trim()} do .NET: espacos Unicode (Zs, Zl, Zp), U+0009 a U+000D e U+0085. */
    public static String trim(String s) {
        if (s == null) return "";
        int ini = 0;
        int fim = s.length();
        while (ini < fim && espacoDotNet(s.charAt(ini))) ini++;
        while (fim > ini && espacoDotNet(s.charAt(fim - 1))) fim--;
        return s.substring(ini, fim);
    }

    private static boolean isBlank(String s) {
        return trim(s).isEmpty();
    }

    /** {@code string.IsNullOrWhiteSpace}. */
    public static boolean emBranco(String s) {
        return s == null || isBlank(s);
    }

    private static boolean espacoDotNet(char c) {
        return Character.isSpaceChar(c) || (c >= 0x09 && c <= 0x0D) || c == 0x85;
    }

    /** OrderBy(..., StringComparer.OrdinalIgnoreCase): estavel; nulo antes de qualquer texto. */
    private static <T> List<T> ordenar(List<T> lista, java.util.function.Function<T, String> chave) {
        List<T> copia = new ArrayList<>(lista == null ? List.of() : lista);
        copia.sort(Comparator.comparing(chave, HashPayload::compararOrdinalIgnoreCase));
        return copia;
    }

    private static int compararOrdinalIgnoreCase(String a, String b) {
        if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            char ca = Character.toUpperCase(a.charAt(i));
            char cb = Character.toUpperCase(b.charAt(i));
            if (ca != cb) return ca < cb ? -1 : 1;
        }
        return Integer.compare(a.length(), b.length());
    }

    // ---------------------------------------------------------------- JSON do System.Text.Json

    private static StringBuilder propString(StringBuilder sb, String nome, String valor) {
        return sb.append('"').append(nome).append("\":").append(string(valor));
    }

    private static StringBuilder propNumero(StringBuilder sb, String nome, BigDecimal valor) {
        return sb.append('"').append(nome).append("\":").append(valor.toPlainString());
    }

    /** String JSON com o encoder padrao do System.Text.Json (JavaScriptEncoder.Default). */
    static String string(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                case '\\' -> sb.append("\\\\");
                default -> {
                    boolean seguro = c >= 0x20 && c <= 0x7E
                            && "\"&'+<>`".indexOf(c) < 0;
                    if (seguro) {
                        sb.append(c);
                    } else {
                        sb.append("\\u").append(String.format("%04X", (int) c));
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String sha256Hex(String texto) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
