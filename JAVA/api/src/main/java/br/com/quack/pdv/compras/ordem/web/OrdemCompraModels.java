package br.com.quack.pdv.compras.ordem.web;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class OrdemCompraModels {

    private OrdemCompraModels() {}

    public record Ordem(
            String id,
            String companyId,
            String orderNumber,
            String supplierId,
            String supplierName,
            String supplierCnpj,
            int status,
            String createdBy,
            String createdByName,
            OffsetDateTime createdAt,
            OffsetDateTime receivedAt,
            OffsetDateTime canceledAt,
            OffsetDateTime previsaoEntrega,
            String condicaoPagamento,
            String formaPagamento,
            BigDecimal valorFrete,
            BigDecimal valorDesconto,
            String motivoCancelamento,
            String receivedBy,
            String receivedByName,
            String note,
            BigDecimal totalEstimado,
            List<Item> itens) {}

    public record Item(
            String productCode,
            String productName,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal itemTotal,
            BigDecimal quantityReceived,
            OffsetDateTime dataValidade) {}

    public record Sugestao(
            String productCode,
            String productName,
            BigDecimal currentStock,
            BigDecimal minStock,
            BigDecimal maxStock,
            String supplierId,
            String supplierName,
            BigDecimal unitCost) {}
}
