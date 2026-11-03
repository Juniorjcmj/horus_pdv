package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Campos de GET /api/Produto usados pelo caixa (valores em texto pt-BR). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductDto(
        String id,
        String productCode,
        String productName,
        String gtin,
        String productSalePrice,
        String productQnt,
        String unidadeComercial,
        String categoriaId,
        String marca,
        Boolean controlaValidade,
        String dataValidade,
        Integer diasAlertaValidade) {
}
