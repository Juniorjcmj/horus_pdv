package br.com.quack.pdv.venda.registro;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Confere o {@link HashPayload} com os vetores gerados pelo codigo real da API .NET
 * ({@code contrato/vetores-dotnet}: {@code dotnet run -- gerar-hash ...}) e com o PayloadHash do Caixa JavaFX.
 */
class HashPayloadTest {

    private static JsonNode casos;

    @BeforeAll
    static void carregar() throws Exception {
        // withExactBigDecimals: sem isso o Jackson apara os zeros finais ao montar o JsonNode (e a escala e o que importa).
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));
        try (InputStream in = HashPayloadTest.class.getResourceAsStream("/contrato/vetores-hash.json")) {
            casos = mapper.readTree(in).get("casos");
        }
    }

    @Test
    void todosOsVetoresDoDotnetConferem() {
        assertThat(casos.size()).isGreaterThanOrEqualTo(20);
        List<String> divergencias = new ArrayList<>();
        for (JsonNode caso : casos) {
            String esperado = caso.get("hash").asText();
            String obtido = calcular(caso);
            if (!esperado.equals(obtido)) {
                divergencias.add(caso.get("nome").asText() + ": esperado " + esperado + " veio " + obtido);
            }
        }
        assertThat(divergencias).isEmpty();
    }

    /**
     * Hashes que o {@code PayloadHash} do Caixa JavaFX (JAVA/caixa) produz para os mesmos dados dos casos
     * {@code caixa-compat-*} (ASCII e decimais sem zeros finais). Gerados executando a classe do Caixa; aqui ficam como
     * literais para nao criar dependencia entre os modulos.
     */
    private static final Map<String, String> HASH_DO_CAIXA = Map.of(
            "caixa-compat-dinheiro", "3e717e1f934dcb3d4ac07dbcc3e52701e0cb9994e886508252267010cc3dbd67",
            "caixa-compat-multiplo", "8a609e26dc07067d6afaf6e302acac1eb1d83a25175dec7026c0647296ec5924",
            "caixa-compat-abertura", "50ab7718bbcdd6fa9c7c22898704041819f38e1ab9432ebf000cd3b6db26fcf1",
            "caixa-compat-fechamento", "fbac70d386b974d6b46e400b15bf0a3a037116a7b56531ca6ac1dfc2f2395ef5",
            "caixa-compat-movimento", "627f16f9b29be190fb78c8ceee052d7a09edbb70650b881c941e559383ca1ffd");

    @Test
    void hashDoCaixaJavaFxCoincideComODotnetEComAJavaNosCasosCompativeis() {
        int conferidos = 0;
        for (JsonNode caso : casos) {
            String nome = caso.get("nome").asText();
            if (!HASH_DO_CAIXA.containsKey(nome)) continue;
            assertThat(calcular(caso)).as(nome + " (Java)").isEqualTo(HASH_DO_CAIXA.get(nome));
            assertThat(caso.get("hash").asText()).as(nome + " (.NET)").isEqualTo(HASH_DO_CAIXA.get(nome));
            conferidos++;
        }
        assertThat(conferidos).isEqualTo(HASH_DO_CAIXA.size());
    }

    @Test
    void escapeDoSystemTextJsonEmMaiusculaParaNaoAscii() {
        assertThat(HashPayload.string("Açaí+\"x\" <&>'`\\ /")).isEqualTo(
                "\"A\\u00E7a\\u00ED\\u002B\\u0022x\\u0022 \\u003C\\u0026\\u003E\\u0027\\u0060\\\\ /\"");
        assertThat(HashPayload.string("😀\t\n")).isEqualTo("\"\\uD83D\\uDE00\\t\\n\"");
    }

    @Test
    void decimalPreservaEscalaERoundingToEven() {
        assertThat(HashPayload.parseDecimal("2,50").toPlainString()).isEqualTo("2.50");
        assertThat(HashPayload.parseDecimal("R$ 1.234,5").toPlainString()).isEqualTo("1234.5");
        assertThat(HashPayload.parseDecimal("abc")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(HashPayload.arredondar(new BigDecimal("2.505"), 2).toPlainString()).isEqualTo("2.50");
        assertThat(HashPayload.arredondar(new BigDecimal("2.515"), 2).toPlainString()).isEqualTo("2.52");
        assertThat(HashPayload.arredondar(new BigDecimal("2.5"), 2).toPlainString()).isEqualTo("2.5");
        assertThat(HashPayload.arredondar(new BigDecimal("1.00000"), 4).toPlainString()).isEqualTo("1.0000");
    }

    // ---------------------------------------------------------------- adaptacao dos vetores

    private static String calcular(JsonNode caso) {
        JsonNode e = caso.get("entrada");
        return switch (caso.get("tipo").asText()) {
            case "venda" -> HashPayload.calcular(venda(e));
            case "abertura" -> HashPayload.calcularAbertura(e.get("openingAmount").asText());
            case "fechamento" -> HashPayload.calcularFechamento(e.get("closingAmount").asText(),
                    texto(e, "note"), texto(e, "differenceReason"));
            case "movimento" -> HashPayload.calcularMovimento(texto(e, "tipo"), texto(e, "valor"), texto(e, "motivo"));
            default -> throw new IllegalStateException("tipo desconhecido");
        };
    }

    private static HashPayload.Venda venda(JsonNode e) {
        List<HashPayload.Item> itens = new ArrayList<>();
        for (JsonNode i : e.get("items")) {
            itens.add(new HashPayload.Item(texto(i, "productCode"), numero(i, "quantity"), numero(i, "unitPrice"),
                    numero(i, "desconto"), texto(i, "promocaoId")));
        }
        List<HashPayload.Pagamento> pagamentos = new ArrayList<>();
        for (JsonNode p : e.get("payments")) {
            pagamentos.add(new HashPayload.Pagamento(texto(p, "paymentType"), numero(p, "amount"),
                    numero(p, "cashGiven"), numero(p, "changeAmount")));
        }
        return new HashPayload.Venda(texto(e, "customerName"), texto(e, "customerCpf"), texto(e, "paymentType"),
                texto(e, "totalAmount"), texto(e, "operatorName"), itens, pagamentos,
                texto(e, "xmlNfceContingencia"));
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal numero(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.decimalValue();
    }
}
