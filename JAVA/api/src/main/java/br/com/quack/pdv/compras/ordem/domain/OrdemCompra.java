package br.com.quack.pdv.compras.ordem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "OrdemCompra")
public class OrdemCompra {

    @Id
    @Column(name = "Id", length = 40, nullable = false)
    public String id = "";

    @Column(name = "CompanyId", length = 40, nullable = false)
    public String companyId = "";

    @Column(name = "OrderNumber", length = 20, nullable = false)
    public String orderNumber = "";

    @Column(name = "SupplierId", length = 40)
    public String supplierId;

    @Column(name = "SupplierName", length = 180, nullable = false)
    public String supplierName = "Fornecedor não informado";

    @Column(name = "SupplierCnpj", length = 30)
    public String supplierCnpj;

    @Column(name = "Status", nullable = false)
    public byte status;

    @Column(name = "CreatedBy", length = 40)
    public String createdBy;

    @Column(name = "CreatedByName", length = 180)
    public String createdByName;

    @Column(name = "CreatedAt", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "ReceivedAt")
    public OffsetDateTime receivedAt;

    @Column(name = "CanceledAt")
    public OffsetDateTime canceledAt;

    @Column(name = "Note", length = 500)
    public String note;

    @Column(name = "TotalEstimado", precision = 18, scale = 2, nullable = false)
    public BigDecimal totalEstimado = BigDecimal.ZERO;

    @Column(name = "PrevisaoEntrega")
    public OffsetDateTime previsaoEntrega;

    @Column(name = "CondicaoPagamento", length = 100)
    public String condicaoPagamento;

    @Column(name = "FormaPagamento", length = 50)
    public String formaPagamento;

    @Column(name = "ValorFrete", precision = 18, scale = 2, nullable = false)
    public BigDecimal valorFrete = BigDecimal.ZERO;

    @Column(name = "ValorDesconto", precision = 18, scale = 2, nullable = false)
    public BigDecimal valorDesconto = BigDecimal.ZERO;

    @Column(name = "MotivoCancelamento", length = 300)
    public String motivoCancelamento;

    @Column(name = "ReceivedBy", length = 40)
    public String receivedBy;

    @Column(name = "ReceivedByName", length = 180)
    public String receivedByName;
}

