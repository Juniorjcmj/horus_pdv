package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.PromotionDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Promotion(String id, String nome, String tipo, BigDecimal valorDesconto, BigDecimal precoFixo,
        BigDecimal quantidadeLeva, BigDecimal quantidadePaga, BigDecimal quantidadeMinima, Instant inicio,
        Instant fim, boolean ativa, String categoriaId, List<String> produtoIds) {

    public static Promotion fromDto(PromotionDto d) {
        return new Promotion(d.id(), d.nome(), d.tipo(), d.valorDesconto(), d.precoFixo(), d.quantidadeLeva(),
                d.quantidadePaga(), d.quantidadeMinima(), parse(d.inicioVigencia()), parse(d.fimVigencia()),
                Boolean.TRUE.equals(d.ativa()), d.categoriaId(),
                d.produtoIds() == null ? List.of() : List.copyOf(d.produtoIds()));
    }

    private static Instant parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
