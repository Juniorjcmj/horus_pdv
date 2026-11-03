package br.com.quack.pdv.venda.registro;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.quack.pdv.venda.registro.app.VendaPayload;
import br.com.quack.pdv.venda.registro.domain.VendaRegistroResultado;
import br.com.quack.pdv.venda.registro.web.VendaRequests;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O ResponsePayload gravado pela .NET (linha real do ambiente de contrato) e legivel pela Java e vice-versa. */
class VendaPayloadTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final String HASH_DOTNET = "1f5bc2c8a304c3df2bd2a5039d5831a0cbb2d7d66013b3b056307f2e14b40aea";

    static String fixture(String nome) throws Exception {
        try (var in = VendaPayloadTest.class.getResourceAsStream("/contrato/fixtures/" + nome)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String hashDe(VendaRequests.Venda r) {
        List<HashPayload.Item> itens = r.items.stream().map(i -> new HashPayload.Item(i.productCode, i.quantity,
                i.getUnitPrice(), i.desconto, i.promocaoId)).toList();
        List<HashPayload.Pagamento> pags = r.payments.stream().map(p -> new HashPayload.Pagamento(p.paymentType,
                p.amount, p.cashGiven, p.changeAmount)).toList();
        return HashPayload.calcular(new HashPayload.Venda(r.customerName, r.customerCpf, r.paymentType,
                r.totalAmount, r.operatorName, itens, pags, null));
    }

    @Test
    void leOPayloadDaDotNet() throws Exception {
        VendaRegistroResultado r = VendaPayload.ler(fixture("response-payload-venda-dotnet.json"));

        assertThat(r.saleNumber()).isEqualTo("15045");
        assertThat(r.vendaId()).isEqualTo("sale-empresa-principal-15045");
        assertThat(r.clientSaleId()).isEqualTo("cs-fix-1");
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().getFirst().productName()).isEqualTo("Pão de Açúcar");
        assertThat(r.rows().getFirst().quantity()).isEqualByComparingTo("12.5");
        assertThat(r.payments().getFirst().createdAt().toString()).startsWith("2026-11-03T20:07:40.8808329");
        assertThat(r.payments().getFirst().cashGiven()).isNull();
        assertThat(r.warnings().getFirst().saldoResultante()).isEqualByComparingTo("-4.5");
        assertThat(r.fiadoSaldoAtual()).isEqualByComparingTo("37.50");
        assertThat(r.fiadoClienteId()).isEqualTo("cl-ct-folgado");
    }

    @Test
    void gravaNoFormatoCamelCaseComNulosEDatasDotNet() throws Exception {
        VendaRegistroResultado lido = VendaPayload.ler(fixture("response-payload-venda-dotnet.json"));
        JsonNode escrito = JSON.readTree(VendaPayload.serializar(lido));
        JsonNode original = JSON.readTree(fixture("response-payload-venda-dotnet.json"));

        List<String> camposEscritos = new ArrayList<>();
        escrito.fieldNames().forEachRemaining(camposEscritos::add);
        List<String> camposOriginais = new ArrayList<>();
        original.fieldNames().forEachRemaining(camposOriginais::add);
        assertThat(camposEscritos).containsExactlyElementsOf(camposOriginais);
        assertThat(escrito.get("payments").get(0).get("createdAt").asText())
                .isEqualTo(original.get("payments").get(0).get("createdAt").asText());
        assertThat(escrito.get("payments").get(0).get("cashGiven").isNull()).isTrue();
        assertThat(escrito.get("rows").get(0).get("quantity").decimalValue())
                .isEqualByComparingTo(original.get("rows").get(0).get("quantity").decimalValue());
        assertThat(VendaPayload.ler(VendaPayload.serializar(lido))).isEqualTo(lido);
    }

    @Test
    void hashDaVendaDaDotNetBateComOCalculadoPelaJava() throws Exception {
        VendaRequests.Venda r = JSON.readValue(fixture("venda-dotnet-request.json"), VendaRequests.Venda.class);

        assertThat(hashDe(r)).isEqualTo(HASH_DOTNET);
    }

    @Test
    void requestPreservaAEscalaDosNumerosEOPrecoAusente() throws Exception {
        VendaRequests.Venda r = JSON.readValue("{\"items\":[{\"productCode\":\"A\",\"quantity\":2.50,"
                + "\"unitPrice\":3.00},{\"productCode\":\"B\",\"quantity\":1}],\"payments\":[{\"amount\":6.10}]}",
                VendaRequests.Venda.class);

        assertThat(r.items.get(0).quantity.toPlainString()).isEqualTo("2.50");
        assertThat(r.items.get(0).getUnitPrice().toPlainString()).isEqualTo("3.00");
        assertThat(r.items.get(0).isUnitPriceSpecified()).isTrue();
        assertThat(r.items.get(1).isUnitPriceSpecified()).isFalse();
        assertThat(r.payments.get(0).amount.toPlainString()).isEqualTo("6.10");
    }
}
