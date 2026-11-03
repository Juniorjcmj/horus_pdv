package br.com.quack.pdv.compras.ordem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "OrdemCompraItens")
public class OrdemCompraItem {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "OrdemCompraId", length = 40, nullable = false)
    public String ordemCompraId = "";

    @Column(name = "ProductCode", length = 80, nullable = false)
    public String productCode = "";

    @Column(name = "ProductName", length = 180, nullable = false)
    public String productName = "";

    @Column(name = "Quantity", precision = 15, scale = 4, nullable = false)
    public BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "UnitCost", precision = 15, scale = 4, nullable = false)
    public BigDecimal unitCost = BigDecimal.ZERO;

    @Column(name = "ItemTotal", precision = 18, scale = 2, nullable = false)
    public BigDecimal itemTotal = BigDecimal.ZERO;

    @Column(name = "QuantityReceived", precision = 15, scale = 4, nullable = false)
    public BigDecimal quantityReceived = BigDecimal.ZERO;

    @Column(name = "DataValidade")
    public OffsetDateTime dataValidade;
}

