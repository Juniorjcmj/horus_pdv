package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.ProductDto;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Product(String id, String code, String name, String gtin, BigDecimal price, BigDecimal stock,
        String unit, String categoriaId, String marca, boolean controlaValidade, LocalDate dataValidade,
        int diasAlertaValidade) {

    public static Product fromDto(ProductDto d) {
        String unit = d.unidadeComercial() == null || d.unidadeComercial().isBlank() ? "UN"
                : d.unidadeComercial().trim();
        LocalDate validade = null;
        if (d.dataValidade() != null && d.dataValidade().length() >= 10) {
            try {
                validade = LocalDate.parse(d.dataValidade().substring(0, 10));
            } catch (DateTimeParseException ignored) {
                // sem validade valida
            }
        }
        int dias = d.diasAlertaValidade() == null || d.diasAlertaValidade() <= 0 ? 15 : d.diasAlertaValidade();
        return new Product(
                d.id(),
                d.productCode() == null ? "" : d.productCode().trim(),
                d.productName() == null ? "" : d.productName().trim(),
                d.gtin() == null ? "" : d.gtin().trim(),
                Money.parseBrOrZero(d.productSalePrice()),
                Money.parseBrOrZero(d.productQnt()),
                unit,
                d.categoriaId(),
                d.marca(),
                Boolean.TRUE.equals(d.controlaValidade()),
                validade,
                dias);
    }

    @JsonIgnore
    public boolean fractional() {
        return !"UN".equalsIgnoreCase(unit == null ? "UN" : unit.trim());
    }

    public Product withStock(BigDecimal newStock) {
        return new Product(id, code, name, gtin, price, newStock, unit, categoriaId, marca, controlaValidade,
                dataValidade, diasAlertaValidade);
    }
}
