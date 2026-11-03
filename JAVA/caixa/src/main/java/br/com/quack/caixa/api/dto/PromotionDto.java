package br.com.quack.caixa.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PromotionDto(String id, String nome, String tipo, BigDecimal valorDesconto, BigDecimal precoFixo,
        BigDecimal quantidadeLeva, BigDecimal quantidadePaga, BigDecimal quantidadeMinima, String inicioVigencia,
        String fimVigencia, Boolean ativa, String categoriaId, List<String> produtoIds) {
}
