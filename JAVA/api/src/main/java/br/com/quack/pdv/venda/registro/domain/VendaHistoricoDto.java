package br.com.quack.pdv.venda.registro.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** VendaHistoricoAD da API .NET: uma linha por item da venda (valores monetarios em texto pt-BR). */
public record VendaHistoricoDto(
        @JsonProperty("saleNumber") String saleNumber,
        @JsonProperty("status") String status,
        @JsonProperty("customerName") String customerName,
        @JsonProperty("customerCpf") String customerCpf,
        @JsonProperty("paymentType") String paymentType,
        @JsonProperty("totalAmount") String totalAmount,
        @JsonProperty("operatorName") String operatorName,
        @JsonProperty("productCode") String productCode,
        @JsonProperty("productName") String productName,
        @JsonProperty("quantity") BigDecimal quantity,
        @JsonProperty("unitPrice") String unitPrice,
        @JsonProperty("desconto") BigDecimal desconto,
        @JsonProperty("promocaoId") String promocaoId,
        @JsonProperty("itemTotal") String itemTotal,
        @JsonProperty("saleDate") String saleDate,
        @JsonProperty("clientSaleId") String clientSaleId,
        @JsonProperty("offlineReference") String offlineReference,
        @JsonProperty("fiscalDocId") String fiscalDocId,
        @JsonProperty("fiscalModelo") Integer fiscalModelo,
        @JsonProperty("fiscalNumeroNf") Integer fiscalNumeroNf,
        @JsonProperty("fiscalSerie") Integer fiscalSerie,
        @JsonProperty("fiscalStatus") Integer fiscalStatus,
        @JsonProperty("fiscalChaveAcesso") String fiscalChaveAcesso,
        @JsonProperty("paymentBreakdown") String paymentBreakdown,
        @JsonProperty("canceladoEm") String canceladoEm,
        @JsonProperty("canceladoPorOperadorNome") String canceladoPorOperadorNome,
        @JsonProperty("canceladoPorSupervisorNome") String canceladoPorSupervisorNome,
        @JsonProperty("canceladoJustificativa") String canceladoJustificativa) {}
