package br.com.quack.pdv.venda.registro.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Corpos de /api/HistoricoVendas (VendaRequest.cs). Os numeros sao lidos direto como BigDecimal: o Jackson preserva a
 * escala do texto ("2.50"), que entra no hash de idempotencia.
 */
public final class VendaRequests {

    private VendaRequests() {}

    public static class Venda {
        public String orderNumber;
        public JsonNode creditAuthorization;
        public String clientSaleId;
        public String eventId;
        public String eventType;
        public String offlineReference;
        @JsonDeserialize(using = DataHoraTolerante.class)
        public OffsetDateTime occurredAt;
        public String payloadHash;
        public boolean reenvioOffline;
        public String xmlNfceContingencia;
        public String customerName = "";
        public String customerCpf = "";
        public String paymentType = "";
        public String totalAmount = "";
        public String operatorName = "";
        public List<Item> items = new ArrayList<>();
        public List<Pagamento> payments = new ArrayList<>();

        public boolean temCreditAuthorization() {
            return creditAuthorization != null && !creditAuthorization.isNull();
        }
    }

    public static class Item {
        public String productCode = "";
        public String productName = "";
        public BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal unitPrice = BigDecimal.ZERO;
        private boolean unitPriceSpecified;
        public BigDecimal desconto = BigDecimal.ZERO;
        public BigDecimal itemTotal;
        public String promocaoId;
        public String autorizacaoPrecoId;

        @JsonProperty("unitPrice")
        public void setUnitPrice(BigDecimal valor) {
            this.unitPrice = valor == null ? BigDecimal.ZERO : valor;
            this.unitPriceSpecified = true;
        }

        @JsonProperty("unitPrice")
        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        @JsonIgnore
        public boolean isUnitPriceSpecified() {
            return unitPriceSpecified;
        }

        /** Para testes e chamadas internas. */
        public Item comPreco(BigDecimal valor) {
            setUnitPrice(valor);
            return this;
        }
    }

    public static class Pagamento {
        public String paymentType = "";
        public BigDecimal amount = BigDecimal.ZERO;
        public BigDecimal cashGiven;
        public BigDecimal changeAmount;
    }

    public static class AutorizarPreco {
        public String supervisorId = "";
        public String supervisorPassword = "";
        public String productCode = "";
        public BigDecimal precoNovo = BigDecimal.ZERO;
        public String motivo;
    }

    /** DateTimeOffset do .NET: aceita ISO com offset; sem offset assume UTC; invalido vira 400 de modelo. */
    public static class DataHoraTolerante extends JsonDeserializer<OffsetDateTime> {
        @Override
        public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            String texto = p.getValueAsString();
            if (texto == null || texto.isBlank()) return null;
            try {
                return OffsetDateTime.parse(texto.strip());
            } catch (DateTimeParseException e) {
                // tenta sem offset
            }
            try {
                return LocalDateTime.parse(texto.strip()).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw InvalidFormatException.from(p, "The JSON value could not be converted to System.Nullable`1[System.DateTimeOffset].", texto, OffsetDateTime.class);
            }
        }
    }
}
