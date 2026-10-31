package br.com.quack.caixa.domain;

import br.com.quack.caixa.api.dto.CustomerDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Customer(String id, String name, String document, BigDecimal creditLimit, BigDecimal debt) {

    public static Customer fromDto(CustomerDto d) {
        return new Customer(d.id(), d.customerName() == null ? "" : d.customerName(),
                d.document() == null ? "" : d.document(),
                d.limiteCredito() == null ? BigDecimal.ZERO : d.limiteCredito(),
                d.saldoDevedor() == null ? BigDecimal.ZERO : d.saldoDevedor());
    }

    public Customer withDebt(BigDecimal newDebt) {
        return new Customer(id, name, document, creditLimit, newDebt);
    }
}
