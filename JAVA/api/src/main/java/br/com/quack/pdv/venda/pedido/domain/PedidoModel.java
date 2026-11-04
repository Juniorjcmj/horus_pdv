package br.com.quack.pdv.venda.pedido.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** PedidoModel da API .NET: valores monetarios em texto pt-BR. */
public record PedidoModel(
        @JsonProperty("orderNumber") String orderNumber,
        @JsonProperty("customerName") String customerName,
        @JsonProperty("customerCpf") String customerCpf,
        @JsonProperty("sellerName") String sellerName,
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("totalAmount") String totalAmount,
        @JsonProperty("itens") List<Item> itens) {

    /** PedidoItemModel. */
    public record Item(
            @JsonProperty("productCode") String productCode,
            @JsonProperty("productName") String productName,
            @JsonProperty("quantity") BigDecimal quantity,
            @JsonProperty("unitPrice") String unitPrice,
            @JsonProperty("itemTotal") String itemTotal) {}
}
